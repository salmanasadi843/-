package com.example.data.repository

import com.example.data.local.ClassDao
import com.example.data.local.ClassEntity
import com.example.data.local.CourseDao
import com.example.data.local.CourseEntity
import com.example.data.local.LectureDao
import com.example.data.local.LectureEntity
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cloud backup for one authenticated teacher.
 *
 * Private backups live in teacher_data/{uid}; published material remains in
 * shared_materials/{uid}. We never write private data to the public collection.
 */
class FirestoreSyncRepository(
    private val lectureDao: LectureDao,
    private val classDao: ClassDao,
    private val courseDao: CourseDao,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private companion object {
        const val SCHEMA_VERSION = 1L
        const val TEACHER_DATA = "teacher_data"
        const val SHARED_MATERIALS = "shared_materials"
    }

    /**
     * Returns true if a compatible snapshot was restored.
     * A missing or incompatible document never clears local data.
     */
    suspend fun restoreFromCloud(uid: String): Boolean {
        val snapshot = firestore.collection(TEACHER_DATA).document(uid).get().awaitResult()
        if (!snapshot.exists()) return false

        val version = snapshot.getLong("schemaVersion")
        if (version != SCHEMA_VERSION) {
            throw IllegalStateException(
                "نسخه پشتیبان ابری قابل‌شناسایی نیست؛ برای جلوگیری از حذف اطلاعات، بازیابی متوقف شد."
            )
        }
        restoreSnapshot(snapshot, requireAllCollections = true)
        return true
    }

    /**
     * Older shared documents are read-only fallback sources. They are never
     * overwritten by this method, and are imported only if they contain the
     * three expected lists.
     */
    suspend fun restoreLegacySharedMaterials(uid: String): Boolean {
        val snapshot = firestore.collection(SHARED_MATERIALS).document(uid).get().awaitResult()
        if (!snapshot.exists()) return false
        val data = snapshot.data ?: return false
        if (data["classes"] !is List<*> ||
            data["courses"] !is List<*> ||
            data["lectures"] !is List<*>
        ) return false

        restoreSnapshot(snapshot, requireAllCollections = true)
        return true
    }

    suspend fun uploadSnapshot(uid: String) {
        val classes = classDao.getAllForBackup()
        val courses = courseDao.getAllForBackup()
        val lectures = lectureDao.getAllForBackup()
            // These two records are built-in demo data, not teacher-authored content.
            .filterNot {
                it.title == "جلسه ۴: شبکه‌های عصبی و یادگیری عمیق" ||
                    it.title == "جلسه ۷: سری و تبدیل فوریه و کاربرد در سیگنال"
            }

        val payload = hashMapOf<String, Any>(
            "schemaVersion" to SCHEMA_VERSION,
            "ownerUid" to uid,
            "updatedAt" to FieldValue.serverTimestamp(),
            "classes" to classes.map { it.toCloudMap() },
            "courses" to courses.map { it.toCloudMap() },
            "lectures" to lectures.map { it.toCloudMap() }
        )
        firestore.collection(TEACHER_DATA).document(uid).set(payload).awaitResult()
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

        val classes = rawClasses.orEmpty().mapNotNull { (it as? Map<*, *>)?.asStringMap()?.toClassEntity() }
        val courses = rawCourses.orEmpty().mapNotNull { (it as? Map<*, *>)?.asStringMap()?.toCourseEntity() }
        val lectures = rawLectures.orEmpty().mapNotNull { (it as? Map<*, *>)?.asStringMap()?.toLectureEntity() }

        // Delete children before parents, then recreate parents before children.
        lectureDao.clearForRestore()
        courseDao.clearForRestore()
        classDao.clearForRestore()
        if (classes.isNotEmpty()) classDao.insertAllForRestore(classes)
        if (courses.isNotEmpty()) courseDao.insertAllForRestore(courses)
        if (lectures.isNotEmpty()) lectureDao.insertAllForRestore(lectures)
    }

    private fun ClassEntity.toCloudMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "teacherName" to teacherName,
        "description" to description,
        "term" to term,
        "classDateMillis" to classDateMillis,
        "createdAtMillis" to createdAtMillis
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
        // Local filesystem paths cannot be used on another phone.
        "audioUrl" to audioUrl,
        "audioDurationMs" to audioDurationMs,
        "transcript" to transcript,
        "aiSummary" to aiSummary,
        "aiKeyPoints" to aiKeyPoints,
        "aiQuizJson" to aiQuizJson,
        "tags" to tags,
        "isFavorite" to isFavorite,
        "lastEditedMillis" to lastEditedMillis
    )

    private fun Map<String, Any?>.toClassEntity() = ClassEntity(
        id = number("id"),
        name = text("name"),
        teacherName = text("teacherName"),
        description = text("description"),
        term = text("term"),
        classDateMillis = number("classDateMillis", System.currentTimeMillis()),
        createdAtMillis = number("createdAtMillis", System.currentTimeMillis())
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
        aiSummary = nullableText("aiSummary"),
        aiKeyPoints = nullableText("aiKeyPoints"),
        aiQuizJson = nullableText("aiQuizJson"),
        tags = text("tags"),
        isFavorite = this["isFavorite"] as? Boolean ?: false,
        lastEditedMillis = number("lastEditedMillis", System.currentTimeMillis())
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
