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
import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
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
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val database: AppDatabase
) {
    private companion object {
        const val SCHEMA_VERSION = 2L
        const val LEGACY_SCHEMA_VERSION = 1L
        const val TEACHER_DATA = "teacher_data"
        const val SHARED_MATERIALS = "shared_materials"
        const val RECORDS = "records"
        const val BATCH_LIMIT = 400
    }

    suspend fun isAdminRestorePending(uid: String): Boolean {
        val snapshot = firestore.collection(TEACHER_DATA).document(uid).get().awaitResult()
        return snapshot.getBoolean("adminPending") == true
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
                restoreRecordDocuments(uid)
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
        val root = firestore.collection(TEACHER_DATA).document(uid)
        val records = root.collection(RECORDS)
        val lockToken = UUID.randomUUID().toString()
        val lockLeaseMillis = 10 * 60 * 1000L

        // Acquire a server-visible lease before touching any record documents.
        // Admin callables check the same root document inside their transaction.
        val backupVersion = firestore.runTransaction { transaction ->
            val current = transaction.get(root)
            if (current.getBoolean("adminPending") == true) {
                throw IllegalStateException(
                    "مدیر اطلاعات این حساب را تغییر داده است؛ ابتدا بازیابی ابری را کامل کنید."
                )
            }
            val now = System.currentTimeMillis()
            val activeLease = current.getLong("syncLockUntilMillis") ?: 0L
            if (activeLease > now) {
                throw IllegalStateException(
                    "پشتیبان‌گیری دیگری در حال انجام است؛ کمی بعد دوباره تلاش کنید."
                )
            }
            val version = current.getLong("adminPendingVersion") ?: 0L
            val lockData = mutableMapOf<String, Any?>(
                "syncLockToken" to lockToken,
                "syncLockUntilMillis" to now + lockLeaseMillis
            )
            if (!current.exists()) {
                // Bootstrap a valid schema-v2 root atomically with the first backup lease.
                // These fields are required by the restricted Firestore create rule.
                lockData["ownerUid"] = uid
                lockData["schemaVersion"] = SCHEMA_VERSION
                lockData["adminPending"] = false
                lockData["adminPendingVersion"] = 0L
                lockData["recordCount"] = 0
                lockData["folders"] = emptyList<Map<String, Any?>>()
            }
            transaction.set(
                root,
                lockData,
                com.google.firebase.firestore.SetOptions.merge()
            )
            version
        }.awaitResult()

        var completed = false
        try {
            // Shared published records are imported with negative IDs. They belong to
            // their original teachers and must never enter this account's private backup.
            val classes = classDao.getAllForBackup().filter { it.id > 0L && (it.ownerUid == uid || it.ownerUid.isBlank()) }
            val ownedClassIds = classes.map { it.id }.toSet()
            val courses = courseDao.getAllForBackup().filter {
                it.id > 0L && it.classId in ownedClassIds
            }
            // Back up every locally owned positive-ID lecture. Never omit user
            // content based on a hard-coded title.
            val lectures = lectureDao.getAllForBackup()
                .filter { it.id > 0L && it.classId in ownedClassIds }

            val desired = linkedMapOf<String, Map<String, Any?>>()
            classes.forEach { desired["class_${it.id}"] = mapOf("recordType" to "class") + it.toCloudMap() }
            courses.forEach { desired["course_${it.id}"] = mapOf("recordType" to "course") + it.toCloudMap() }
            lectures.forEach { desired["lecture_${it.id}"] = mapOf("recordType" to "lecture") + it.toCloudMap() }

            // A missing local row is not proof of intentional deletion: local data
            // may be incomplete after reinstall, partial restore, or offline use.
            // Preserve cloud-only rows until a durable deletion-tombstone protocol
            // exists. Ordinary backup is merge-only; deletion uses admin callables.
            val existing = records.get().awaitResult().documents
            val resultingRecordCount = (existing.map { it.id }.toSet() + desired.keys).size
            val operations = desired.map { (id, data) -> records.document(id) to data }

            // Renew the lease before each write batch. A live administrator operation
            // cannot overlap these writes; an expired/crashed client lease is recoverable.
            operations.chunked(BATCH_LIMIT).forEach { chunk ->
                firestore.runTransaction { transaction ->
                    val latest = transaction.get(root)
                    val now = System.currentTimeMillis()
                    if (latest.getString("syncLockToken") != lockToken ||
                        (latest.getLong("syncLockUntilMillis") ?: 0L) <= now ||
                        latest.getBoolean("adminPending") == true ||
                        (latest.getLong("adminPendingVersion") ?: 0L) != backupVersion
                    ) {
                        throw IllegalStateException(
                            "وضعیت پشتیبان هنگام ارسال تغییر کرده است؛ برای جلوگیری از بازنویسی، عملیات متوقف شد."
                        )
                    }
                    transaction.update(root, "syncLockUntilMillis", now + lockLeaseMillis)
                    true
                }.awaitResult()

                val batch = firestore.batch()
                chunk.forEach { (ref, data) ->
                    if (data == null) batch.delete(ref) else batch.set(ref, data)
                }
                batch.commit().awaitResult()
            }

            // Keep folder metadata in the existing owner document, avoiding new Firebase rule paths.
            val folderRows = teacherFolderDao?.observeFoldersForBackup()?.filter { it.ownerUid == uid }.orEmpty()

            // The final root update is conditional and atomic. If an admin operation
            // won the race, this transaction fails and cannot clear its pending marker.
            firestore.runTransaction { transaction ->
                val latest = transaction.get(root)
                val now = System.currentTimeMillis()
                if (latest.getString("syncLockToken") != lockToken ||
                    (latest.getLong("syncLockUntilMillis") ?: 0L) <= now ||
                    latest.getBoolean("adminPending") == true ||
                    (latest.getLong("adminPendingVersion") ?: 0L) != backupVersion
                ) {
                    throw IllegalStateException(
                        "تغییر مدیریتی یا همگام‌سازی دیگری هم‌زمان انجام شده است؛ پشتیبان محلی روی نسخه ابری نوشته نشد."
                    )
                }
                transaction.update(
                    root,
                    mapOf(
                        "schemaVersion" to SCHEMA_VERSION,
                        "ownerUid" to uid,
                        "recordCount" to resultingRecordCount,
                        "folders" to folderRows.map { it.toCloudMap() },
                        "adminPending" to false,
                        "adminPendingVersion" to backupVersion,
                        "syncLockToken" to FieldValue.delete(),
                        "syncLockUntilMillis" to FieldValue.delete(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                true
            }.awaitResult()
            completed = true
        } finally {
            // On failure, deliberately leave the short lease in place: some record
            // batches may already have committed. This prevents an admin edit from
            // treating a partially-written snapshot as stable. The lease expires.
            if (completed) {
                runCatching {
                    firestore.runTransaction { transaction ->
                        val latest = transaction.get(root)
                        if (latest.getString("syncLockToken") == lockToken) {
                            transaction.update(
                                root,
                                mapOf(
                                    "syncLockToken" to FieldValue.delete(),
                                    "syncLockUntilMillis" to FieldValue.delete()
                                )
                            )
                        }
                        true
                    }.awaitResult()
                }
            }
        }
    }

    private suspend fun restoreRecordDocuments(uid: String) {
        val functions = FirebaseFunctions.getInstance("europe-west1")
        val beginResult = functions.getHttpsCallable("beginCloudRestore")
            .call()
            .awaitResult()
        val lease = beginResult.data as? Map<*, *>
            ?: throw IllegalStateException("قفل امن بازیابی از سرور دریافت نشد.")
        val lockToken = lease["restoreLockToken"] as? String
            ?: throw IllegalStateException("شناسه قفل بازیابی معتبر نیست.")
        val expectedVersion = (lease["adminPendingVersion"] as? Number)?.toLong()
            ?: throw IllegalStateException("نسخه پشتیبان برای بازیابی معتبر نیست.")

        var completed = false
        try {
            val root = firestore.collection(TEACHER_DATA).document(uid)
            val documents = root.collection(RECORDS).get().awaitResult().documents
            val rootSnapshot = root.get().awaitResult()

            // The server lease blocks both admin mutations and normal cloud uploads.
            // Verify that the lock and version still match before touching Room.
            if (rootSnapshot.getString("syncLockToken") != lockToken ||
                (rootSnapshot.getLong("adminPendingVersion") ?: 0L) != expectedVersion ||
                rootSnapshot.getLong("schemaVersion") != SCHEMA_VERSION
            ) {
                throw IllegalStateException(
                    "نسخه پشتیبان هنگام بازیابی تغییر کرده است؛ اطلاعات محلی دست‌نخورده باقی ماند."
                )
            }

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

            validateAndReplaceLocalData(classes, courses, lectures, folders)
            functions.getHttpsCallable("completeAdminRestore")
                .call(
                    mapOf(
                        "expectedAdminPendingVersion" to expectedVersion,
                        "restoreLockToken" to lockToken,
                        "complete" to true
                    )
                )
                .awaitResult()
            completed = true
        } finally {
            // If validation or the Room transaction fails, release the lease but
            // leave adminPending intact so the teacher can retry the restore.
            if (!completed) {
                runCatching {
                    functions.getHttpsCallable("completeAdminRestore")
                        .call(
                            mapOf(
                                "expectedAdminPendingVersion" to expectedVersion,
                                "restoreLockToken" to lockToken,
                                "complete" to false
                            )
                        )
                        .awaitResult()
                }
            }
        }
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
        folders: List<TeacherFolderEntity> = emptyList()
    ) {
        if (classes.any { it.id == 0L } || courses.any { it.id == 0L } || lectures.any { it.id == 0L }) {
            throw IllegalStateException("شناسه یکی از رکوردهای پشتیبان ابری خالی است.")
        }

        // Replace all related Room tables as one atomic operation. If any insert
        // fails (disk full, constraint violation, process interruption), Room rolls
        // the transaction back and the previous local data remains intact.
        database.withTransaction {
            lectureDao.clearForRestore()
            courseDao.clearForRestore()
            classDao.clearForRestore()
            if (classes.isNotEmpty()) classDao.insertAllForRestore(classes)
            if (courses.isNotEmpty()) courseDao.insertAllForRestore(courses)
            if (lectures.isNotEmpty()) lectureDao.insertAllForRestore(lectures)
            teacherFolderDao?.let { dao ->
                dao.clearForRestore()
                val folderRows = folders
                if (folderRows.isNotEmpty()) dao.insertAllForRestore(folderRows)
            }
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
