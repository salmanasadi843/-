package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.data.local.ClassEntity
import com.example.data.local.CourseEntity
import com.example.data.local.LectureEntity
import com.example.data.local.TeacherFolderEntity
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Private cloud backup for one authenticated teacher.
 *
 * Version 1 used one large document with three arrays. Version 2 stores one
 * class/course/lecture per document to avoid Firestore's 1 MiB document limit.
 * The root document is updated last, so an interrupted migration still leaves
 * the previous version-1 snapshot available for recovery.
 */
class FirestoreSyncRepository(
    private val database: AppDatabase,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val lectureDao get() = database.lectureDao()
    private val classDao get() = database.classDao()
    private val courseDao get() = database.courseDao()
    private val teacherFolderDao get() = database.teacherFolderDao()
    private companion object {
        const val SCHEMA_VERSION = 2L
        const val LEGACY_SCHEMA_VERSION = 1L
        const val TEACHER_DATA = "teacher_data"
        const val SHARED_MATERIALS = "shared_materials"
        const val RECORDS = "records"
        const val BATCH_LIMIT = 400
    }

    suspend fun restoreFromCloud(uid: String): Boolean {
        val root = firestore.collection(TEACHER_DATA).document(uid)
        val snapshot = root.get().awaitResult()
        if (!snapshot.exists()) return false

        return when (snapshot.getLong("schemaVersion")) {
            LEGACY_SCHEMA_VERSION -> {
                // Validate and restore the old snapshot before migrating it.
                restoreSnapshot(snapshot, requireAllCollections = true, ownerUid = uid)
                uploadSnapshot(uid)
                true
            }
            SCHEMA_VERSION -> {
                restoreRecordDocuments(uid)
                true
            }
            else -> throw IllegalStateException(
                "نسخه پشتیبان ابری قابل‌شناسایی نیست؛ برای جلوگیری از حذف اطلاعات، بازیابی متوقف شد."
            )
        }
    }

    /**
     * Read-only recovery diagnostic. This method must never write to Firestore or Room.
     * It reports metadata only; transcript/audio contents are deliberately excluded.
     */
    suspend fun diagnoseCloudState(uid: String): String {
        val rootRef = firestore.collection(TEACHER_DATA).document(uid)
        val root = rootRef.get().awaitResult()
        val recordDocuments = rootRef.collection(RECORDS).get().awaitResult().documents
        val shared = firestore.collection(SHARED_MATERIALS).document(uid).get().awaitResult()
        val typeCounts = recordDocuments.groupingBy {
            it.getString("recordType") ?: "unknown"
        }.eachCount()

        fun listSize(value: Any?): Int = (value as? List<*>)?.size ?: 0

        val payloadText = shared.getString("payload")
        val sharedClassRows = mutableListOf<String>()
        val sharedLectureRows = mutableListOf<String>()
        val payloadSummary = if (payloadText.isNullOrBlank()) {
            "absent"
        } else {
            runCatching {
                val payload = JSONObject(payloadText)
                val classArray = payload.optJSONArray("classes")
                val lectureArray = payload.optJSONArray("lectures")
                for (i in 0 until (classArray?.length() ?: 0)) {
                    val item = classArray!!.optJSONObject(i) ?: continue
                    sharedClassRows += "${item.optLong("id")}:${item.optString("name")}:${item.optString("teacherName")}"
                }
                for (i in 0 until (lectureArray?.length() ?: 0)) {
                    val item = lectureArray!!.optJSONObject(i) ?: continue
                    sharedLectureRows += "${item.optLong("id")}:${item.optString("title")}:class=${item.optLong("classId", -1)}"
                }
                "format=" + payload.optString("format", "unknown") +
                    ",version=" + payload.optInt("version", -1) +
                    ",classes=" + (classArray?.length() ?: 0) +
                    ",courses=" + (payload.optJSONArray("courses")?.length() ?: 0) +
                    ",lectures=" + (lectureArray?.length() ?: 0)
            }.getOrElse { "invalid-json" }
        }

        val classRows = recordDocuments
            .filter { it.getString("recordType") == "class" }
            .take(30)
            .joinToString(";") { doc ->
                doc.id + ":" + (doc.getString("name") ?: doc.getString("title") ?: "")
            }
        val lectureRows = recordDocuments
            .filter { it.getString("recordType") == "lecture" }
            .take(60)
            .joinToString(";") { doc ->
                doc.id + ":" + (doc.getString("title") ?: "")
            }

        return buildString {
            appendLine("DARSYAR_READ_ONLY_DIAGNOSTIC_V1")
            appendLine("uid=" + uid)
            appendLine("privateRoot.exists=" + root.exists())
            appendLine("privateRoot.schemaVersion=" + (root.getLong("schemaVersion") ?: -1L))
            appendLine("privateRoot.ownerUid=" + (root.getString("ownerUid") ?: "absent"))
            appendLine("privateRoot.recordCount=" + (root.getLong("recordCount") ?: -1L))
            appendLine("privateRoot.folders=" + listSize(root.get("folders")))
            appendLine("privateRecords.total=" + recordDocuments.size)
            appendLine("privateRecords.classes=" + (typeCounts["class"] ?: 0))
            appendLine("privateRecords.courses=" + (typeCounts["course"] ?: 0))
            appendLine("privateRecords.lectures=" + (typeCounts["lecture"] ?: 0))
            appendLine("privateRecords.unknown=" + (typeCounts["unknown"] ?: 0))
            appendLine("privateRecords.classRows=" + classRows)
            appendLine("privateRecords.lectureRows=" + lectureRows)
            appendLine("sharedDoc.exists=" + shared.exists())
            appendLine("sharedDoc.ownerUid=" + (shared.getString("ownerUid") ?: "absent"))
            appendLine("sharedDoc.payloadChars=" + (payloadText?.length ?: 0))
            appendLine("sharedDoc.payloadSummary=" + payloadSummary)
            appendLine("sharedDoc.classRows=" + sharedClassRows.joinToString(";"))
            appendLine("sharedDoc.lectureRows=" + sharedLectureRows.joinToString(";"))
            appendLine("sharedDoc.legacyArrays.classes=" + listSize(shared.get("classes")))
            appendLine("sharedDoc.legacyArrays.courses=" + listSize(shared.get("courses")))
            appendLine("sharedDoc.legacyArrays.lectures=" + listSize(shared.get("lectures")))
            appendLine("NOTE=no Firestore or local data was changed")
        }
    }

    /**
     * Published materials are not a private backup. They may contain only a
     * subset of the teacher's published classes, so importing them by replacing
     * Room would silently discard private classes and unpublished sessions.
     *
     * If this legacy document exists but no private backup exists, stop the
     * first-run sync and preserve both sides. Published content is still handled
     * by CloudMaterialsSync's separate read-only feed.
     */
    suspend fun restoreLegacySharedMaterials(uid: String): Boolean {
        val snapshot = firestore.collection(SHARED_MATERIALS).document(uid).get().awaitResult()
        if (!snapshot.exists()) return false

        throw IllegalStateException(
            "مطالب منتشرشدهٔ ابری، پشتیبان کامل شخصی نیستند؛ برای جلوگیری از جایگزینی اطلاعات، همگام‌سازی متوقف شد. بازیابی پشتیبان خصوصی باید جداگانه بررسی شود."
        )
    }

    suspend fun uploadSnapshot(uid: String) {
        // Shared published records are imported with negative IDs. They belong to
        // their original teachers and must never enter this account's private backup.
        val classes = classDao.getAllForBackup().filter { it.id > 0L && (it.ownerUid == uid || it.ownerUid.isBlank()) }
        val ownedClassIds = classes.map { it.id }.toSet()
        val courses = courseDao.getAllForBackup().filter {
            it.id > 0L && it.classId in ownedClassIds
        }
        val lectures = lectureDao.getAllForBackup()
            .filter { it.id > 0L && it.classId in ownedClassIds }

        val root = firestore.collection(TEACHER_DATA).document(uid)
        val records = root.collection(RECORDS)
        val desired = linkedMapOf<String, Map<String, Any?>>()
        classes.forEach { desired["class_${it.id}"] = mapOf("recordType" to "class") + it.toCloudMap() }
        courses.forEach { desired["course_${it.id}"] = mapOf("recordType" to "course") + it.toCloudMap() }
        lectures.forEach { desired["lecture_${it.id}"] = mapOf("recordType" to "lecture") + it.toCloudMap() }

        // During recovery, never infer cloud deletions from a possibly partial local DB.
        // Upsert local records only; stale cloud records remain available until an explicit,
        // separately verified deletion/tombstone workflow is implemented.
        val existing = records.get().awaitResult().documents
        if (desired.isEmpty() && existing.isNotEmpty()) {
            throw IllegalStateException(
                "ارسال پشتیبان خالی متوقف شد؛ در فضای ابری ${existing.size} رکورد موجود است اما در دستگاه هیچ رکورد مالکانه‌ای یافت نشد. ابتدا بازیابی و تطبیق اطلاعات لازم است."
            )
        }
        if (existing.isNotEmpty()) {
            val rootSnapshot = root.get().awaitResult()
            val storedCount = rootSnapshot.getLong("recordCount")
            if (!rootSnapshot.exists() ||
                rootSnapshot.getLong("schemaVersion") != SCHEMA_VERSION ||
                rootSnapshot.getString("ownerUid") != uid ||
                storedCount != existing.size.toLong()
            ) {
                throw IllegalStateException(
                    "ساختار پشتیبان ابری با رکوردهای موجود تطبیق ندارد؛ برای جلوگیری از بازنویسی، ارسال متوقف شد."
                )
            }
        }

        val operations = desired.map { (id, data) ->
            records.document(id) to data
        }

        // Firestore batches have a 500-write maximum; leave room below the limit.
        operations.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { (ref, data) -> batch.set(ref, data) }
            batch.commit().awaitResult()
        }

        // Count the union because pre-existing cloud records are intentionally preserved.
        val mergedRecordCount = (existing.map { it.id }.toSet() + desired.keys).size

        // Keep folder metadata in the existing owner document, avoiding new Firebase rule paths.
        val folderRows = teacherFolderDao.observeFoldersForBackup().filter { it.ownerUid == uid }

        // Commit the version marker only after all individual records were written.
        root.set(
            mapOf(
                "schemaVersion" to SCHEMA_VERSION,
                "ownerUid" to uid,
                "recordCount" to mergedRecordCount,
                "folders" to folderRows.map { it.toCloudMap() },
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).awaitResult()
    }

    private suspend fun restoreRecordDocuments(uid: String) {
        val documents = firestore.collection(TEACHER_DATA)
            .document(uid)
            .collection(RECORDS)
            .get()
            .awaitResult()
            .documents

        val classes = mutableListOf<ClassEntity>()
        val courses = mutableListOf<CourseEntity>()
        val lectures = mutableListOf<LectureEntity>()
        val rootSnapshot = firestore.collection(TEACHER_DATA).document(uid).get().awaitResult()
        val storedOwner = rootSnapshot.getString("ownerUid")
        if (!storedOwner.isNullOrBlank() && storedOwner != uid) {
            throw IllegalStateException("مالک پشتیبان ابری با حساب فعلی تطبیق ندارد؛ اطلاعات محلی دست‌نخورده باقی ماند.")
        }
        val expectedCount = rootSnapshot.getLong("recordCount")
            ?: throw IllegalStateException("تعداد رکوردهای پشتیبان ابری مشخص نیست؛ بازیابی برای حفظ اطلاعات متوقف شد.")
        if (expectedCount != documents.size.toLong()) {
            throw IllegalStateException(
                "پشتیبان ابری ناقص دریافت شد (انتظار ${expectedCount} رکورد، دریافت ${documents.size})؛ اطلاعات محلی تغییر نکرد."
            )
        }
        val rawFolders = rootSnapshot.get("folders")
        val folders = when (rawFolders) {
            null -> emptyList()
            is List<*> -> rawFolders.map { value ->
                val row = (value as? Map<*, *>)?.asStringMap()
                    ?: throw IllegalStateException("ساختار پوشه‌های پشتیبان معتبر نیست؛ اطلاعات محلی تغییر نکرد.")
                row.toFolderEntity().also { folder ->
                    if (folder.id <= 0L || folder.ownerUid != uid) {
                        throw IllegalStateException("مالکیت یا شناسه یکی از پوشه‌های پشتیبان معتبر نیست.")
                    }
                }
            }
            else -> throw IllegalStateException("فهرست پوشه‌های پشتیبان معتبر نیست؛ اطلاعات محلی تغییر نکرد.")
        }

        documents.forEach { document ->
            val data = document.data ?: throw IllegalStateException(
                "یکی از رکوردهای پشتیبان ابری خالی است؛ اطلاعات محلی دست‌نخورده باقی ماند."
            )
            when (data["recordType"] as? String) {
                "class" -> classes += data.toClassEntity()
                "course" -> courses += data.toCourseEntity()
                "lecture" -> lectures += data.toLectureEntity()
                else -> throw IllegalStateException(
                    "نوع یکی از رکوردهای پشتیبان ابری معتبر نیست؛ اطلاعات محلی دست‌نخورده باقی ماند."
                )
            }
        }
        mergeCloudData(classes, courses, lectures, folders, uid)
    }

    private suspend fun restoreSnapshot(
        snapshot: DocumentSnapshot,
        requireAllCollections: Boolean,
        ownerUid: String
    ) {
        val storedOwner = snapshot.getString("ownerUid")
        if (!storedOwner.isNullOrBlank() && storedOwner != ownerUid) {
            throw IllegalStateException("مالک پشتیبان قدیمی با حساب فعلی تطبیق ندارد؛ اطلاعات محلی تغییر نکرد.")
        }
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
            val row = (it as? Map<*, *>)?.asStringMap()
                ?: throw IllegalStateException("رکورد کلاس در پشتیبان ابری معتبر نیست.")
            row.toClassEntity().let { entity ->
                if (entity.ownerUid.isBlank()) entity.copy(ownerUid = ownerUid) else entity
            }
        }
        val courses = rawCourses.orEmpty().map {
            (it as? Map<*, *>)?.asStringMap()?.toCourseEntity()
                ?: throw IllegalStateException("رکورد درس در پشتیبان ابری معتبر نیست.")
        }
        val lectures = rawLectures.orEmpty().map {
            (it as? Map<*, *>)?.asStringMap()?.toLectureEntity()
                ?: throw IllegalStateException("رکورد جلسه در پشتیبان ابری معتبر نیست.")
        }
        mergeCloudData(classes, courses, lectures, ownerUid = ownerUid)
    }

    /**
     * Merge a validated private cloud snapshot into Room without deleting or overwriting
     * local records. Existing IDs must match exactly; any conflict aborts the transaction.
     */
    private suspend fun mergeCloudData(
        classes: List<ClassEntity>,
        courses: List<CourseEntity>,
        lectures: List<LectureEntity>,
        folders: List<TeacherFolderEntity>? = null,
        ownerUid: String
    ) {
        val normalizedClasses = classes.map {
            if (it.ownerUid.isBlank()) it.copy(ownerUid = ownerUid) else it
        }
        require(normalizedClasses.all { it.id > 0L && it.ownerUid == ownerUid }) {
            "مالکیت یا شناسه یکی از کلاس‌های پشتیبان معتبر نیست؛ اطلاعات محلی تغییر نکرد."
        }
        require(courses.all { it.id > 0L } && lectures.all { it.id > 0L }) {
            "شناسه یکی از رکوردهای پشتیبان معتبر نیست؛ اطلاعات محلی تغییر نکرد."
        }
        require(normalizedClasses.map { it.id }.distinct().size == normalizedClasses.size &&
            courses.map { it.id }.distinct().size == courses.size &&
            lectures.map { it.id }.distinct().size == lectures.size
        ) {
            "شناسه تکراری در پشتیبان وجود دارد؛ اطلاعات محلی تغییر نکرد."
        }

        val normalizedFolders = folders?.map {
            if (it.ownerUid.isBlank()) it.copy(ownerUid = ownerUid) else it
        }
        if (normalizedFolders != null) {
            require(normalizedFolders.all { it.id > 0L && it.ownerUid == ownerUid } &&
                normalizedFolders.map { it.id }.distinct().size == normalizedFolders.size
            ) {
                "مالکیت یا شناسه یکی از پوشه‌های پشتیبان معتبر نیست؛ اطلاعات محلی تغییر نکرد."
            }
        }

        database.withTransaction {
            val existingClasses = classDao.getAllForBackup().associateBy { it.id }
            val existingCourses = courseDao.getAllForBackup().associateBy { it.id }
            val existingLectures = lectureDao.getAllForBackup().associateBy { it.id }
            val existingFolders = teacherFolderDao.observeFoldersForBackup().associateBy { it.id }

            val incomingClassIds = normalizedClasses.map { it.id }.toSet()
            val allClassIds = existingClasses.keys + incomingClassIds
            val incomingCourseIds = courses.map { it.id }.toSet()
            val allCourseIds = existingCourses.keys + incomingCourseIds
            val incomingFolderIds = normalizedFolders.orEmpty().map { it.id }.toSet()
            val allFolderIds = existingFolders.keys + incomingFolderIds

            require(courses.all { it.classId in allClassIds }) {
                "یکی از درس‌ها به کلاسی متصل است که در دستگاه یا پشتیبان وجود ندارد."
            }
            require(lectures.all {
                (it.classId == null || it.classId in allClassIds) &&
                    (it.courseId == null || it.courseId in allCourseIds)
            }) {
                "ارتباط یکی از جلسه‌ها با کلاس یا درس معتبر نیست."
            }
            if (normalizedFolders != null) {
                require(normalizedFolders.all {
                    it.parentFolderId == null || it.parentFolderId in allFolderIds
                }) { "ساختار پوشه‌های پشتیبان کامل نیست." }
                normalizedClasses.forEach { item ->
                    require(item.folderId == null || item.folderId in allFolderIds) {
                        "یکی از کلاس‌ها به پوشه‌ای متصل است که در دستگاه یا پشتیبان وجود ندارد."
                    }
                }
                val foldersById = (existingFolders.values + normalizedFolders).associateBy { it.id }
                normalizedFolders.forEach { folder ->
                    val visited = mutableSetOf<Long>()
                    var parentId = folder.parentFolderId
                    while (parentId != null) {
                        require(visited.add(parentId) && parentId != folder.id) {
                            "ساختار پوشه‌های پشتیبان دارای چرخه است؛ اطلاعات محلی تغییر نکرد."
                        }
                        parentId = foldersById[parentId]?.parentFolderId
                    }
                }
            }

            normalizedClasses.forEach { item ->
                val existing = existingClasses[item.id]
                require(existing == null || existing == item) {
                    "شناسه کلاس ${item.id} در دستگاه با اطلاعات متفاوتی وجود دارد؛ هیچ تغییری اعمال نشد."
                }
            }
            courses.forEach { item ->
                val existing = existingCourses[item.id]
                require(existing == null || existing == item) {
                    "شناسه درس ${item.id} در دستگاه با اطلاعات متفاوتی وجود دارد؛ هیچ تغییری اعمال نشد."
                }
            }
            lectures.forEach { item ->
                val existing = existingLectures[item.id]
                require(existing == null || existing == item) {
                    "شناسه جلسه ${item.id} در دستگاه با محتوای متفاوتی وجود دارد؛ هیچ تغییری اعمال نشد."
                }
            }
            normalizedFolders.orEmpty().forEach { item ->
                val existing = existingFolders[item.id]
                require(existing == null || existing == item) {
                    "شناسه پوشه ${item.id} در دستگاه با اطلاعات متفاوتی وجود دارد؛ هیچ تغییری اعمال نشد."
                }
            }

            val newClasses = normalizedClasses.filter { it.id !in existingClasses }
            val newCourses = courses.filter { it.id !in existingCourses }
            val newLectures = lectures.filter { it.id !in existingLectures }
            val newFolders = normalizedFolders.orEmpty().filter { it.id !in existingFolders }

            if (newClasses.isNotEmpty()) classDao.insertAllForRestore(newClasses)
            if (newCourses.isNotEmpty()) courseDao.insertAllForRestore(newCourses)
            if (newLectures.isNotEmpty()) lectureDao.insertAllForRestore(newLectures)
            if (newFolders.isNotEmpty()) teacherFolderDao.insertAllForRestore(newFolders)
        }
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
