package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.ClassDao
import com.example.data.local.ClassEntity
import com.example.data.local.CourseDao
import com.example.data.local.CourseEntity
import com.example.data.local.LectureDao
import com.example.data.local.LectureEntity
import com.example.data.local.TeacherFolderDao
import com.example.data.local.TeacherFolderEntity
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.UUID

/**
 * Private cloud backup for one authenticated teacher.
 *
 * Version 1 used one large document with three arrays. Version 2 stores one
 * class/course/lecture per document to avoid Firestore's 1 MiB document limit.
 * The root document is updated last, so an interrupted migration still leaves
 * the previous version-1 snapshot available for recovery.
 */
class FirestoreSyncRepository(
    private val lectureDao: LectureDao,
    private val classDao: ClassDao,
    private val courseDao: CourseDao,
    private val teacherFolderDao: TeacherFolderDao? = null,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val database: AppDatabase
) {
    private companion object {
        const val SCHEMA_VERSION = 3L
        const val LEGACY_RECORD_SCHEMA_VERSION = 2L
        const val LEGACY_SCHEMA_VERSION = 1L
        const val TEACHER_DATA = "teacher_data"
        const val SHARED_MATERIALS = "shared_materials"
        const val RECORDS = "records"
        const val GENERATIONS = "generations"
        const val BATCH_LIMIT = 400
        const val MAX_TEXT_CHUNK_BYTES = 256 * 1024
        const val MAX_RECORD_BYTES = 700 * 1024
    }

    suspend fun restoreFromCloud(uid: String): Boolean {
        val root = firestore.collection(TEACHER_DATA).document(uid)
        val snapshot = root.get().awaitResult()
        if (!snapshot.exists()) return false

        return when (snapshot.getLong("schemaVersion")) {
            LEGACY_SCHEMA_VERSION -> {
                // Validate and restore the old snapshot before migrating it.
                restoreSnapshot(snapshot, requireAllCollections = true)
                uploadSnapshot(uid)
                true
            }
            SCHEMA_VERSION -> {
                val generation = snapshot.getString("activeGeneration")
                    ?: throw IllegalStateException("نسخهٔ فعال پشتیبان ابری مشخص نیست؛ اطلاعات محلی دست‌نخورده باقی ماند.")
                restoreRecordDocuments(uid, generation)
                true
            }
            LEGACY_RECORD_SCHEMA_VERSION -> {
                restoreRecordDocuments(uid, null)
                uploadSnapshot(uid)
                true
            }
            else -> throw IllegalStateException(
                "نسخه پشتیبان ابری قابل‌شناسایی نیست؛ برای جلوگیری از حذف اطلاعات، بازیابی متوقف شد."
            )
        }
    }

    /**
     * Older shared documents are read-only fallback sources. They are imported
     * only if they contain all three expected arrays, then migrated privately.
     */
    suspend fun restoreLegacySharedMaterials(uid: String): Boolean {
        val snapshot = firestore.collection(SHARED_MATERIALS).document(uid).get().awaitResult()
        if (!snapshot.exists()) return false
        val data = snapshot.data ?: return false
        if (data["classes"] !is List<*> ||
            data["courses"] !is List<*> ||
            data["lectures"] !is List<*>
        ) return false

        return try {
            restoreSnapshot(snapshot, requireAllCollections = true)
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    suspend fun uploadSnapshot(uid: String) {
        data class BackupRows(
            val classes: List<ClassEntity>, val courses: List<CourseEntity>,
            val lectures: List<LectureEntity>, val folders: List<TeacherFolderEntity>
        )
        val rows = database.withTransaction {
            BackupRows(classDao.getAllForBackup(), courseDao.getAllForBackup(),
                lectureDao.getAllForBackup(), teacherFolderDao?.observeFoldersForBackup().orEmpty())
        }
        // Shared published records have negative IDs and never enter this private backup.
        val allClasses = rows.classes.filter { it.id > 0L }
        val classesWithUnknownOwner = allClasses.filter { it.ownerUid.isBlank() }
        require(classesWithUnknownOwner.isEmpty()) {
            "پشتیبان‌گیری متوقف شد: ${classesWithUnknownOwner.size} کلاس قدیمی مالک مشخصی ندارند. " +
                "برای جلوگیری از انتساب اشتباه اطلاعات، ابتدا مالکیت این کلاس‌ها باید بررسی شود؛ نسخهٔ ابری قبلی حفظ می‌شود."
        }
        val classes = allClasses.filter { it.ownerUid == uid }
        // Classes owned by other teachers are deliberately excluded from this
        // private account snapshot; they remain under their original owners.
        val ownedClassIds = classes.map { it.id }.toSet()
        val courses = rows.courses.filter { it.id > 0L && it.classId in ownedClassIds }
        val lectures = rows.lectures.filter { it.id > 0L && it.classId in ownedClassIds }

        val root = firestore.collection(TEACHER_DATA).document(uid)
        val generationId = System.currentTimeMillis().toString() + "_" + UUID.randomUUID().toString().replace("-", "")
        val records = root.collection(GENERATIONS).document(generationId).collection(RECORDS)
        val desired = linkedMapOf<String, Map<String, Any?>>()
        classes.forEach { desired["class_${it.id}"] = mapOf("recordType" to "class") + it.toCloudMap() }
        courses.forEach { desired["course_${it.id}"] = mapOf("recordType" to "course", "ownerUid" to uid) + it.toCloudMap() }
        lectures.forEach { lecture ->
            val textFields = linkedMapOf<String, String?>(
                "transcript" to lecture.transcript, "originalTranscript" to lecture.originalTranscript,
                "aiSummary" to lecture.aiSummary, "aiKeyPoints" to lecture.aiKeyPoints,
                "aiQuizJson" to lecture.aiQuizJson, "tags" to lecture.tags
            )
            val manifest = linkedMapOf<String, Int>()
            textFields.forEach { (field, value) ->
                if (value != null) {
                    val chunks = splitUtf8Text(value, MAX_TEXT_CHUNK_BYTES)
                    manifest[field] = chunks.size
                    chunks.forEachIndexed { index, chunk ->
                        desired["lecture_text_${lecture.id}_${field}_${index}"] = mapOf(
                            "recordType" to "lecture_text_chunk", "ownerUid" to uid,
                            "lectureId" to lecture.id, "field" to field, "index" to index, "value" to chunk
                        )
                    }
                }
            }
            val metadata = lecture.toCloudMap().toMutableMap().apply {
                textFields.forEach { (field, value) -> if (value != null) this[field] = "" }
                put("aiSummary", null); put("aiKeyPoints", null); put("aiQuizJson", null)
                put("textChunkCounts", manifest); put("ownerUid", uid)
            }
            desired["lecture_${lecture.id}"] = mapOf("recordType" to "lecture") + metadata
        }
        desired.forEach { (id, data) ->
            require(JSONObject(data).toString().toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) {
                "رکورد $id بیش از اندازه بزرگ است؛ پشتیبان‌گیری متوقف شد تا اطلاعات ناقص ذخیره نشود."
            }
        }

        // Keep each Firestore commit below 8 MiB as well as below 400 writes.
        val pending = mutableListOf<Pair<String, Map<String, Any?>>>()
        var pendingBytes = 0
        fun commitPendingBatch() {
            if (pending.isEmpty()) return
            val batch = firestore.batch()
            pending.forEach { (id, data) -> batch.set(records.document(id), data) }
            batch.commit().awaitResult()
            pending.clear()
            pendingBytes = 0
        }
        desired.forEach { (id, data) ->
            val bytes = JSONObject(data).toString().toByteArray(Charsets.UTF_8).size
            if (pending.isNotEmpty() &&
                (pending.size >= BATCH_LIMIT || pendingBytes + bytes > 8 * 1024 * 1024)
            ) commitPendingBatch()
            pending += id to data
            pendingBytes += bytes
        }
        commitPendingBatch()

        val verified = records.get().awaitResult().documents
        val verifiedById = verified.associateBy { it.id }
        if (verified.size != desired.size || verified.map { it.id }.toSet() != desired.keys ||
            desired.any { (id, expected) ->
                normalizeForVerification(verifiedById[id]?.data) != normalizeForVerification(expected)
            }
        ) throw IllegalStateException("محتوای نسخهٔ تازهٔ پشتیبان کامل تأیید نشد؛ نسخهٔ قبلی همچنان فعال باقی ماند.")

        val folderRows = rows.folders.filter { it.ownerUid == uid }
        // This root update is the commit point. Previous generations are retained.
        root.set(
            mapOf(
                "schemaVersion" to SCHEMA_VERSION,
                "ownerUid" to uid,
                "activeGeneration" to generationId,
                "recordCount" to desired.size,
                "folders" to folderRows.map { it.toCloudMap() },
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).awaitResult()
    }

    private suspend fun restoreRecordDocuments(uid: String, generationId: String?) {
        val rootSnapshot = firestore.collection(TEACHER_DATA).document(uid).get().awaitResult()
        if (generationId != null && rootSnapshot.getString("activeGeneration") != generationId) {
            throw IllegalStateException(
                "نسخهٔ فعال پشتیبان هنگام بازیابی تغییر کرده است؛ برای جلوگیری از ترکیب نسخه‌ها، دوباره تلاش کنید."
            )
        }
        val records = if (generationId == null) {
            firestore.collection(TEACHER_DATA).document(uid).collection(RECORDS)
        } else {
            firestore.collection(TEACHER_DATA).document(uid)
                .collection(GENERATIONS).document(generationId).collection(RECORDS)
        }
        val documents = records.get().awaitResult().documents
        val latestRoot = firestore.collection(TEACHER_DATA).document(uid).get().awaitResult()
        if (generationId != null && latestRoot.getString("activeGeneration") != generationId) {
            throw IllegalStateException("نسخهٔ فعال پشتیبان در زمان خواندن تغییر کرد؛ اطلاعات محلی حفظ شد.")
        }
        val expectedCount = latestRoot.getLong("recordCount")?.toInt()
            ?: throw IllegalStateException(
                "تعداد رکوردهای پشتیبان ابری مشخص نیست؛ برای جلوگیری از بازیابی ناقص، اطلاعات محلی دست‌نخورده باقی ماند."
            )
        if (documents.size != expectedCount || rootSnapshot.getLong("recordCount") != latestRoot.getLong("recordCount")) {
            throw IllegalStateException(
                "تعداد رکوردهای ابری با مقدار ثبت‌شده سازگار نیست (${documents.size} از $expectedCount). " +
                    "احتمالاً همگام‌سازی ناقص بوده است؛ اطلاعات محلی دست‌نخورده باقی ماند."
            )
        }

        val classes = mutableListOf<ClassEntity>()
        val courses = mutableListOf<CourseEntity>()
        val lectures = mutableListOf<LectureEntity>()
        val lectureChunks = mutableMapOf<Long, MutableMap<String, MutableMap<Int, String>>>()
        val folders = if (teacherFolderDao == null) {
            emptyList()
        } else {
            val rawFolders = latestRoot.get("folders") as? List<*>
                ?: throw IllegalStateException(
                    "فهرست پوشه‌های پشتیبان ابری موجود نیست؛ برای جلوگیری از حذف پوشه‌های محلی، بازیابی متوقف شد."
                )
            rawFolders.map { value ->
                (value as? Map<*, *>)?.asStringMap()?.toFolderEntity()
                    ?: throw IllegalStateException(
                        "یکی از پوشه‌های پشتیبان ابری معتبر نیست؛ اطلاعات محلی دست‌نخورده باقی ماند."
                    )
            }
        }

        documents.forEach { document ->
            val data = document.data ?: throw IllegalStateException(
                "یکی از رکوردهای پشتیبان ابری خالی است؛ اطلاعات محلی دست‌نخورده باقی ماند."
            )
            when (data["recordType"] as? String) {
                "class" -> {
                    if (data["ownerUid"] != uid) {
                        throw IllegalStateException(
                            "مالک یکی از کلاس‌های پشتیبان با حساب فعلی سازگار نیست؛ اطلاعات محلی دست‌نخورده باقی ماند."
                        )
                    }
                    classes += data.toClassEntity()
                }
                "course" -> {
                    val recordOwner = data["ownerUid"] as? String
                    if (recordOwner != null && recordOwner != uid) {
                        throw IllegalStateException("مالک یکی از درس‌های پشتیبان معتبر نیست؛ اطلاعات محلی حفظ شد.")
                    }
                    courses += data.toCourseEntity()
                }
                "lecture" -> {
                    val recordOwner = data["ownerUid"] as? String
                    if (recordOwner != null && recordOwner != uid) {
                        throw IllegalStateException("مالک یکی از جلسات پشتیبان معتبر نیست؛ اطلاعات محلی حفظ شد.")
                    }
                    lectures += data.toLectureEntity()
                }
                "lecture_text_chunk" -> {
                    if (data["ownerUid"] != uid) throw IllegalStateException("مالک بخش متن جلسه معتبر نیست.")
                    val lectureId = (data["lectureId"] as? Number)?.toLong()
                        ?: throw IllegalStateException("شناسهٔ جلسه در بخش متن معتبر نیست.")
                    val field = data["field"] as? String
                        ?: throw IllegalStateException("نوع بخش متن جلسه مشخص نیست.")
                    if (field !in setOf("transcript", "originalTranscript", "aiSummary", "aiKeyPoints", "aiQuizJson", "tags")) {
                        throw IllegalStateException("نوع فیلد متن جلسه در پشتیبان معتبر نیست.")
                    }
                    val index = (data["index"] as? Number)?.toInt()
                        ?: throw IllegalStateException("شمارهٔ بخش متن جلسه معتبر نیست.")
                    val value = data["value"] as? String
                        ?: throw IllegalStateException("محتوای بخش متن جلسه معتبر نیست.")
                    val parts = lectureChunks.getOrPut(lectureId) { mutableMapOf() }.getOrPut(field) { mutableMapOf() }
                    if (index < 0 || parts.put(index, value) != null) throw IllegalStateException("بخش تکراری یا نامعتبر در متن جلسه پیدا شد.")
                }
                else -> throw IllegalStateException(
                    "نوع یکی از رکوردهای پشتیبان ابری معتبر نیست؛ اطلاعات محلی دست‌نخورده باقی ماند."
                )
            }
        }
        lectures.indices.forEach { i ->
            val lecture = lectures[i]
            val metadata = documents.firstOrNull { it.id == "lecture_${lecture.id}" }?.data
                ?: throw IllegalStateException("اطلاعات اصلی یکی از جلسات پشتیبان موجود نیست.")
            val manifest = (metadata["textChunkCounts"] as? Map<*, *>)?.mapNotNull { (k, v) ->
                val field = k as? String ?: return@mapNotNull null
                val count = (v as? Number)?.toInt() ?: return@mapNotNull null
                field to count
            }?.toMap().orEmpty()
            val rebuilt = mutableMapOf<String, String>()
            manifest.forEach { (field, count) ->
                val parts = lectureChunks[lecture.id]?.get(field)
                    ?: throw IllegalStateException("بخش‌های متن جلسه ناقص است؛ اطلاعات محلی حفظ شد.")
                if (count <= 0 || parts.size != count || (0 until count).any { it !in parts }) {
                    throw IllegalStateException("بخش‌های متن جلسه ناقص است؛ اطلاعات محلی حفظ شد.")
                }
                rebuilt[field] = buildString { for (n in 0 until count) append(parts.getValue(n)) }
            }
            lectures[i] = lecture.copy(
                transcript = rebuilt["transcript"] ?: lecture.transcript,
                originalTranscript = rebuilt["originalTranscript"] ?: lecture.originalTranscript,
                aiSummary = rebuilt["aiSummary"] ?: lecture.aiSummary,
                aiKeyPoints = rebuilt["aiKeyPoints"] ?: lecture.aiKeyPoints,
                aiQuizJson = rebuilt["aiQuizJson"] ?: lecture.aiQuizJson,
                tags = rebuilt["tags"] ?: lecture.tags
            )
        }
        val classIds = classes.map { it.id }.toSet()
        if (courses.any { it.classId !in classIds } ||
            lectures.any { it.classId != null && it.classId !in classIds }
        ) {
            throw IllegalStateException(
                "ارتباط کلاس و درس یا جلسه در پشتیبان ابری ناقص است؛ اطلاعات محلی دست‌نخورده باقی ماند."
            )
        }
        validateAndReplaceLocalData(classes, courses, lectures, folders, uid)
    }

    private suspend fun restoreSnapshot(
        snapshot: DocumentSnapshot,
        requireAllCollections: Boolean
    ) {
        val rawClasses = snapshot.get("classes") as? List<*>
        val rawCourses = snapshot.get("courses") as? List<*>
        val rawLectures = snapshot.get("lectures") as? List<*>

        if (requireAllCollections &&
            (rawClasses == null || rawCourses == null || rawLectures == null)
        ) {
            throw IllegalStateException(
                "ساختار اطلاعات ابری کامل نیست؛ اطلاعات محلی دست‌نخورده باقی ماند."
            )
        }

        val classes = rawClasses.orEmpty().map {
            (it as? Map<*, *>)?.asStringMap()?.toClassEntity()
                ?: throw IllegalStateException("رکورد کلاس در پشتیبان ابری معتبر نیست.")
        }
        val courses = rawCourses.orEmpty().map {
            (it as? Map<*, *>)?.asStringMap()?.toCourseEntity()
                ?: throw IllegalStateException("رکورد درس در پشتیبان ابری معتبر نیست.")
        }
        val lectures = rawLectures.orEmpty().map {
            (it as? Map<*, *>)?.asStringMap()?.toLectureEntity()
                ?: throw IllegalStateException("رکورد جلسه در پشتیبان ابری معتبر نیست.")
        }
        validateAndReplaceLocalData(classes, courses, lectures)
    }

    private suspend fun validateAndReplaceLocalData(
        classes: List<ClassEntity>,
        courses: List<CourseEntity>,
        lectures: List<LectureEntity>,
        folders: List<TeacherFolderEntity>? = null,
        ownerUid: String? = null
    ) {
        if (classes.any { it.id <= 0L } || courses.any { it.id <= 0L } || lectures.any { it.id <= 0L }) {
            throw IllegalStateException("شناسه یکی از رکوردهای پشتیبان ابری معتبر نیست.")
        }
        if (classes.map { it.id }.distinct().size != classes.size ||
            courses.map { it.id }.distinct().size != courses.size ||
            lectures.map { it.id }.distinct().size != lectures.size
        ) {
            throw IllegalStateException("شناسهٔ تکراری در پشتیبان ابری پیدا شد؛ اطلاعات محلی دست‌نخورده باقی ماند.")
        }
        val classIds = classes.map { it.id }.toSet()
        val courseIds = courses.map { it.id }.toSet()
        if (courses.any { it.classId !in classIds } ||
            lectures.any { (it.classId != null && it.classId !in classIds) ||
                (it.courseId != null && it.courseId !in courseIds) }
        ) {
            throw IllegalStateException("ارتباط یکی از جلسات یا درس‌ها با کلاس/درس موجود نیست؛ بازیابی متوقف شد.")
        }
        if (folders != null) {
            val folderIds = folders.map { it.id }.toSet()
            if (folders.any { it.id <= 0L || it.ownerUid.isBlank() ||
                    (ownerUid != null && it.ownerUid != ownerUid) } ||
                folders.map { it.id }.distinct().size != folders.size ||
                folders.any { it.parentFolderId != null && it.parentFolderId !in folderIds } ||
                classes.any { it.folderId != null && it.folderId !in folderIds }
            ) {
                throw IllegalStateException("ساختار یا مالکیت پوشه‌ها در پشتیبان معتبر نیست؛ بازیابی متوقف شد.")
            }
        }

        // One Room transaction: a failed insert rolls back the deletes as well.
        database.withTransaction {
            lectureDao.clearForRestore()
            courseDao.clearForRestore()
            classDao.clearForRestore()
            if (folders != null && teacherFolderDao != null) {
                teacherFolderDao.clearForRestore()
                if (folders.isNotEmpty()) teacherFolderDao.insertAllForRestore(folders)
            }
            if (classes.isNotEmpty()) classDao.insertAllForRestore(classes)
            if (courses.isNotEmpty()) courseDao.insertAllForRestore(courses)
            if (lectures.isNotEmpty()) lectureDao.insertAllForRestore(lectures)
        }
    }

    private fun normalizeForVerification(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (key, item) ->
            key.toString() to normalizeForVerification(item)
        }.toSortedMap()
        is List<*> -> value.map { normalizeForVerification(it) }
        is Number -> value.toString().toBigDecimal().stripTrailingZeros()
        else -> value
    }

    private fun splitUtf8Text(value: String, maxBytes: Int): List<String> {
        if (value.isEmpty()) return listOf("")
        val chunks = mutableListOf<String>()
        var start = 0; var index = 0; var bytes = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val pieceBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            require(pieceBytes <= maxBytes)
            if (bytes > 0 && bytes + pieceBytes > maxBytes) {
                chunks += value.substring(start, index)
                start = index; bytes = 0
            }
            bytes += pieceBytes
            index += chars
        }
        chunks += value.substring(start)
        return chunks
    }

    private fun ClassEntity.toCloudMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "teacherName" to teacherName,
        "description" to description,
        "term" to term,
        "classDateMillis" to classDateMillis,
        "isPublished" to isPublished,
        "createdAtMillis" to createdAtMillis,
        "ownerUid" to ownerUid,
        "folderId" to folderId
    )

    private fun CourseEntity.toCloudMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "classId" to classId,
        "name" to name,
        "teacherName" to teacherName,
        "description" to description,
        "createdAtMillis" to createdAtMillis
    )

    private fun LectureEntity.toCloudMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "courseName" to courseName,
        "professorName" to professorName,
        "classId" to classId,
        "courseId" to courseId,
        "dateMillis" to dateMillis,
        "audioUrl" to audioUrl,
        "audioDurationMs" to audioDurationMs,
        "transcript" to transcript,
        "originalTranscript" to originalTranscript,
        "aiSummary" to aiSummary,
        "aiKeyPoints" to aiKeyPoints,
        "aiQuizJson" to aiQuizJson,
        "tags" to tags,
        "isFavorite" to isFavorite,
        "isPublished" to isPublished,
        "lastEditedMillis" to lastEditedMillis,
        "sortOrder" to sortOrder
    )

    private fun Map<String, Any?>.toClassEntity() = ClassEntity(
        id = number("id"),
        name = text("name"),
        teacherName = text("teacherName"),
        description = text("description"),
        term = text("term"),
        classDateMillis = number("classDateMillis", System.currentTimeMillis()),
        isPublished = this["isPublished"] as? Boolean ?: true,
        createdAtMillis = number("createdAtMillis", System.currentTimeMillis()),
        ownerUid = text("ownerUid"),
        folderId = nullableNumber("folderId")
    )

    private fun Map<String, Any?>.toCourseEntity() = CourseEntity(
        id = number("id"),
        classId = number("classId"),
        name = text("name"),
        teacherName = text("teacherName"),
        description = text("description"),
        createdAtMillis = number("createdAtMillis", System.currentTimeMillis())
    )

    private fun Map<String, Any?>.toLectureEntity() = LectureEntity(
        id = number("id"),
        title = text("title"),
        courseName = text("courseName"),
        professorName = text("professorName"),
        classId = nullableNumber("classId"),
        courseId = nullableNumber("courseId"),
        dateMillis = number("dateMillis", System.currentTimeMillis()),
        audioFilePath = null,
        audioUrl = nullableText("audioUrl"),
        audioDurationMs = number("audioDurationMs"),
        transcript = text("transcript"),
        originalTranscript = text("originalTranscript").ifBlank { text("transcript") },
        aiSummary = nullableText("aiSummary"),
        aiKeyPoints = nullableText("aiKeyPoints"),
        aiQuizJson = nullableText("aiQuizJson"),
        tags = text("tags"),
        isFavorite = this["isFavorite"] as? Boolean ?: false,
        isPublished = this["isPublished"] as? Boolean ?: true,
        lastEditedMillis = number("lastEditedMillis", System.currentTimeMillis()),
        sortOrder = number("sortOrder").toInt()
    )

    private fun TeacherFolderEntity.toCloudMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "ownerUid" to ownerUid,
        "name" to name,
        "parentFolderId" to parentFolderId,
        "createdAtMillis" to createdAtMillis
    )

    private fun Map<String, Any?>.toFolderEntity() = TeacherFolderEntity(
        id = number("id"),
        ownerUid = text("ownerUid"),
        name = text("name"),
        parentFolderId = nullableNumber("parentFolderId"),
        createdAtMillis = number("createdAtMillis", System.currentTimeMillis())
    )

    private fun Map<*, *>.asStringMap(): Map<String, Any?>? {
        if (keys.any { it !is String }) return null
        return entries.associate { (key, value) -> key as String to value }
    }

    private fun Map<String, Any?>.text(key: String): String = this[key] as? String ?: ""
    private fun Map<String, Any?>.nullableText(key: String): String? = this[key] as? String
    private fun Map<String, Any?>.number(key: String, default: Long = 0L): Long =
        (this[key] as? Number)?.toLong() ?: default
    private fun Map<String, Any?>.nullableNumber(key: String): Long? =
        (this[key] as? Number)?.toLong()

    private suspend fun <T> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    continuation.resume(task.result)
                } else {
                    continuation.resumeWithException(
                        task.exception ?: IllegalStateException("درخواست Firebase ناموفق بود.")
                    )
                }
            }
        }
}
