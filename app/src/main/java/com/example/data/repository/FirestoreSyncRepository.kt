package com.example.data.repository

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
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private companion object {
        const val SCHEMA_VERSION = 2L
        const val LEGACY_SCHEMA_VERSION = 1L
        const val TEACHER_DATA = "teacher_data"
        const val SHARED_MATERIALS = "shared_materials"
        const val RECORDS = "records"
        const val BATCH_LIMIT = 400
    }


    /**
     * Read-only diagnostics. This method never restores, migrates, or writes data.
     */
    suspend fun inspectCloudBackup(uid: String): String {
        // Read each backup independently: an offline/error on one document must
        // not prevent inspection of the other backup source.
        val privateReport = try {
            val snapshot = firestore.collection(TEACHER_DATA).document(uid).get().awaitResult()
            if (!snapshot.exists()) {
                "پشتیبان خصوصی: سند وجود ندارد."
            } else {
                when (snapshot.getLong("schemaVersion")) {
                    SCHEMA_VERSION -> {
                        val records = firestore.collection(TEACHER_DATA).document(uid)
                            .collection(RECORDS).get().awaitResult()
                        val classes = records.documents.count { it.getString("recordType") == "class" }
                        val courses = records.documents.count { it.getString("recordType") == "course" }
                        val lectures = records.documents.count { it.getString("recordType") == "lecture" }
                        val expected = snapshot.getLong("recordCount")
                        "پشتیبان خصوصی: موجود؛ نسخه ۲؛ رکورد ثبت‌شده در ریشه: ${expected ?: "نامشخص"}؛ دریافت‌شده: ${records.documents.size} (کلاس: $classes، درس: $courses، جلسه: $lectures)."
                    }
                    LEGACY_SCHEMA_VERSION -> {
                        val classes = (snapshot.get("classes") as? List<*>)?.size
                        val courses = (snapshot.get("courses") as? List<*>)?.size
                        val lectures = (snapshot.get("lectures") as? List<*>)?.size
                        "پشتیبان خصوصی: موجود؛ نسخه ۱؛ کلاس: ${classes ?: "نامشخص"}، درس: ${courses ?: "نامشخص"}، جلسه: ${lectures ?: "نامشخص"}."
                    }
                    else -> "پشتیبان خصوصی: سند موجود است، اما نسخهٔ آن نامشخص است (${snapshot.getLong("schemaVersion") ?: "بدون نسخه"})."
                }
            }
        } catch (error: Exception) {
            "خواندن پشتیبان خصوصی ناموفق بود: ${error.localizedMessage ?: error.javaClass.simpleName}"
        }

        val legacyReport = try {
            val snapshot = firestore.collection(SHARED_MATERIALS).document(uid).get().awaitResult()
            if (!snapshot.exists()) {
                "پشتیبان قدیمی shared_materials: سند وجود ندارد."
            } else {
                val payloadText = snapshot.getString("payload")
                if (payloadText != null) {
                    val payload = JSONObject(payloadText)
                    val classes = payload.optJSONArray("classes")?.length()
                    val courses = payload.optJSONArray("courses")?.length()
                    val lectures = payload.optJSONArray("lectures")?.length()
                    "پشتیبان قدیمی shared_materials: سند موجود؛ کلاس: ${classes ?: "نامشخص"}، درس: ${courses ?: "نامشخص"}، جلسه: ${lectures ?: "نامشخص"}."
                } else {
                    val classes = (snapshot.get("classes") as? List<*>)?.size
                    val courses = (snapshot.get("courses") as? List<*>)?.size
                    val lectures = (snapshot.get("lectures") as? List<*>)?.size
                    "پشتیبان قدیمی shared_materials: سند موجود؛ کلاس: ${classes ?: "نامشخص"}، درس: ${courses ?: "نامشخص"}، جلسه: ${lectures ?: "نامشخص"}."
                }
            }
        } catch (error: Exception) {
            "خواندن پشتیبان قدیمی ناموفق بود: ${error.localizedMessage ?: error.javaClass.simpleName}"
        }

        return "$privateReport\n$legacyReport\nاین گزارش فقط‌خواندنی است؛ هیچ داده‌ای تغییر نکرد."
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
            SCHEMA_VERSION -> restoreRecordDocuments(uid)
            else -> throw IllegalStateException(
                "نسخه پشتیبان ابری قابل‌شناسایی نیست؛ برای جلوگیری از حذف اطلاعات، بازیابی متوقف شد."
            )
        }
    }

    /**
     * Imports both legacy layouts: direct Firestore arrays and the older JSON
     * string stored in shared_materials/{uid}.payload.
     */
    suspend fun restoreLegacySharedMaterials(uid: String): Boolean {
        val snapshot = firestore.collection(SHARED_MATERIALS).document(uid).get().awaitResult()
        if (!snapshot.exists()) return false
        val data = snapshot.data ?: return false

        val payloadText = data["payload"] as? String
        if (payloadText != null) {
            val payload = try { JSONObject(payloadText) } catch (error: Exception) {
                throw IllegalStateException("خواندن پشتیبان قدیمی ناموفق بود؛ هیچ اطلاعاتی جایگزین نشد.", error)
            }
            if (payload.optString("format") != "darsyar-shared-materials") {
                throw IllegalStateException("قالب پشتیبان قدیمی شناخته‌شده نیست؛ اطلاعات دست‌نخورده باقی ماند.")
            }
            val rawClasses = payload.optJSONArray("classes")
                ?: throw IllegalStateException("فهرست کلاس‌ها در پشتیبان قدیمی وجود ندارد.")
            val rawCourses = payload.optJSONArray("courses")
                ?: throw IllegalStateException("فهرست درس‌ها در پشتیبان قدیمی وجود ندارد.")
            val rawLectures = payload.optJSONArray("lectures")
                ?: throw IllegalStateException("فهرست جلسات در پشتیبان قدیمی وجود ندارد.")
            val classes = (0 until rawClasses.length()).map {
                rawClasses.getJSONObject(it).toStringMap().toClassEntity()
            }
            val courses = (0 until rawCourses.length()).map {
                rawCourses.getJSONObject(it).toStringMap().toCourseEntity()
            }
            val lectures = (0 until rawLectures.length()).map {
                rawLectures.getJSONObject(it).toStringMap().toLectureEntity()
            }
            if (classes.isEmpty() && courses.isEmpty() && lectures.isEmpty()) {
                throw IllegalStateException("پشتیبان قدیمی خالی است؛ اطلاعات محلی جایگزین نشد.")
            }
            validateAndMergeLocalData(uid, classes, courses, lectures)
            return true
        }

        if (data["classes"] !is List<*> || data["courses"] !is List<*> ||
            data["lectures"] !is List<*>) return false
        restoreSnapshot(snapshot, requireAllCollections = true, ownerUid = uid)
        return true
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

        // Safe recovery: absence from Room never means deletion from cloud.
        val existing = records.get().awaitResult().documents
        if (existing.isNotEmpty()) {
            val rootSnapshot = root.get().awaitResult()
            if (!rootSnapshot.exists() || rootSnapshot.getString("ownerUid") != uid ||
                rootSnapshot.getLong("schemaVersion") != SCHEMA_VERSION ||
                rootSnapshot.getLong("recordCount") != existing.size.toLong()) {
                throw IllegalStateException("ساختار پشتیبان ابری با رکوردهای موجود تطبیق ندارد؛ ارسال متوقف شد.")
            }
        }
        if (desired.isEmpty() && existing.isNotEmpty()) {
            throw IllegalStateException("ارسال پشتیبان خالی متوقف شد؛ ${existing.size} رکورد ابری موجود است.")
        }
        desired.entries.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { (id, data) -> batch.set(records.document(id), data) }
            batch.commit().awaitResult()
        }
        val mergedRecordCount = (existing.map { it.id }.toSet() + desired.keys).size

        // Keep folder metadata in the existing owner document, avoiding new Firebase rule paths.
        val folderRows = teacherFolderDao?.observeFoldersForBackup()?.filter { it.ownerUid == uid }.orEmpty()

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

    private suspend fun restoreRecordDocuments(uid: String): Boolean {
        val rootSnapshot = firestore.collection(TEACHER_DATA).document(uid).get().awaitResult()
        val rootOwner = rootSnapshot.getString("ownerUid")
        if (!rootOwner.isNullOrBlank() && rootOwner != uid) {
            throw IllegalStateException("مالک پشتیبان ابری با حساب فعلی تطبیق ندارد؛ بازیابی متوقف شد.")
        }
        val expectedCount = rootSnapshot.getLong("recordCount")
            ?: throw IllegalStateException("تعداد رکوردهای پشتیبان مشخص نیست؛ بازیابی برای حفظ اطلاعات متوقف شد.")
        val documents = firestore.collection(TEACHER_DATA)
            .document(uid)
            .collection(RECORDS)
            .get()
            .awaitResult()
            .documents

        if (documents.size.toLong() != expectedCount) {
            throw IllegalStateException(
                "پشتیبان ابری ناقص است (تعداد مورد انتظار: $expectedCount، دریافت‌شده: ${documents.size})؛ اطلاعات محلی دست‌نخورده باقی ماند."
            )
        }
        // An empty v2 marker is not proof that the account has no older backup.
        if (expectedCount == 0L) return false

        val classes = mutableListOf<ClassEntity>()
        val courses = mutableListOf<CourseEntity>()
        val lectures = mutableListOf<LectureEntity>()
        val folders = teacherFolderDao?.let {
            (rootSnapshot.get("folders") as? List<*>)?.mapNotNull { value ->
                (value as? Map<*, *>)?.asStringMap()?.toFolderEntity()
            }.orEmpty()
        }.orEmpty()

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
        // A marker containing only class/course records is not a complete
        // lecture backup when the legacy shared snapshot may still hold lessons.
        // Return false BEFORE touching Room so the caller can try that source.
        if (lectures.isEmpty() && classes.isNotEmpty()) return false

        validateAndMergeLocalData(uid, classes, courses, lectures)
        teacherFolderDao?.let { dao ->
            val existingIds = dao.observeFoldersForBackup().map { it.id }.toSet()
            val missing = folders.filter { it.id !in existingIds && it.ownerUid == uid && it.id > 0L }
            if (missing.isNotEmpty()) dao.insertAllForRestore(missing)
        }
        return true
    }

    private suspend fun restoreSnapshot(
        snapshot: DocumentSnapshot,
        requireAllCollections: Boolean,
        ownerUid: String
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
        validateAndMergeLocalData(ownerUid, classes, courses, lectures)
    }

    /** Additive recovery: never clear Room based on a potentially incomplete cloud snapshot. */
    private suspend fun validateAndMergeLocalData(
        uid: String,
        classes: List<ClassEntity>,
        courses: List<CourseEntity>,
        lectures: List<LectureEntity>
    ) {
        if (classes.any { it.id <= 0L || (it.ownerUid.isNotBlank() && it.ownerUid != uid) } ||
            courses.any { it.id <= 0L } || lectures.any { it.id <= 0L }) {
            throw IllegalStateException("شناسه یا مالکیت یکی از رکوردهای پشتیبان معتبر نیست؛ اطلاعات محلی تغییر نکرد.")
        }
        require(classes.map { it.id }.distinct().size == classes.size &&
            courses.map { it.id }.distinct().size == courses.size &&
            lectures.map { it.id }.distinct().size == lectures.size) {
            "شناسه تکراری در پشتیبان وجود دارد؛ اطلاعات محلی تغییر نکرد."
        }
        val classIds = classDao.getAllForBackup().map { it.id }.toSet()
        val courseIds = courseDao.getAllForBackup().map { it.id }.toSet()
        val lectureIds = lectureDao.getAllForBackup().map { it.id }.toSet()
        val unionClasses = classIds + classes.map { it.id }
        val unionCourses = courseIds + courses.map { it.id }
        val addClasses = classes.filter { it.id !in classIds }
        val addCourses = courses.filter { it.id !in courseIds && it.classId in unionClasses }
        val addLectures = lectures.filter { it.id !in lectureIds && (it.classId == null || it.classId in unionClasses) && (it.courseId == null || it.courseId in unionCourses) }
        if (addClasses.isNotEmpty()) classDao.insertAllForRestore(addClasses)
        if (addCourses.isNotEmpty()) courseDao.insertAllForRestore(addCourses)
        if (addLectures.isNotEmpty()) lectureDao.insertAllForRestore(addLectures)
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

    private fun JSONObject.toStringMap(): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val value = get(key)
            result[key] = if (value === JSONObject.NULL) null else value
        }
        return result
    }

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

