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
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.Executor
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

    suspend fun restoreFromCloud(uid: String): Boolean {
        val root = firestore.collection(TEACHER_DATA).document(uid)
        val snapshot = root.get().awaitResult()
        if (!snapshot.exists()) return false

        return when (snapshot.getLong("schemaVersion")) {
            LEGACY_SCHEMA_VERSION -> {
                // Legacy arrays cannot be compared safely without a generation marker.
                // Never replace populated local data during this one-time migration.
                if (shouldBlockLegacyRestore(localRecordCount(), legacyCloudBackupExists = true)) {
                    throw IllegalStateException(
                        "اطلاعات محلی و پشتیبان قدیمی ابری هم‌زمان موجودند؛ برای جلوگیری از جایگزینی بی‌اجازه، مهاجرت خودکار متوقف شد. ابتدا از اطلاعات محلی فایل پشتیبان بگیرید."
                    )
                }
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
        if (shouldBlockLegacyRestore(localRecordCount(), legacyCloudBackupExists = true)) {
            return false
        }

        return try {
            restoreSnapshot(snapshot, requireAllCollections = true)
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    private suspend fun localRecordCount(): Int {
        // restore replaces these Room tables, including negative-ID shared cache rows.
        // Count every affected local row, not only records owned by this teacher.
        val classes = classDao.getAllForBackup().size
        val courses = courseDao.getAllForBackup().size
        val lectures = lectureDao.getAllForBackup().size
        val folders = teacherFolderDao?.observeFoldersForBackup()?.size ?: 0
        return classes + courses + lectures + folders
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
            .filterNot {
                it.title == "جلسه ۴: شبکه‌های عصبی و یادگیری عمیق" ||
                    it.title == "جلسه ۷: سری و تبدیل فوریه و کاربرد در سیگنال"
            }

        // Never replace a previously valid cloud backup with a temporarily empty local database.
        if (isEmptyCloudBackup(classes.size, courses.size, lectures.size)) {
            throw IllegalStateException(
                "اطلاعات محلی برای تهیه پشتیبان خالی است؛ نسخهٔ سالم ابری دست‌نخورده باقی ماند."
            )
        }

        val root = firestore.collection(TEACHER_DATA).document(uid)
        val records = root.collection(RECORDS)
        val desired = linkedMapOf<String, Map<String, Any?>>()
        classes.forEach { desired["class_${it.id}"] = mapOf("recordType" to "class") + it.toCloudMap() }
        courses.forEach { desired["course_${it.id}"] = mapOf("recordType" to "course") + it.toCloudMap() }
        lectures.forEach { desired["lecture_${it.id}"] = mapOf("recordType" to "lecture") + it.toCloudMap() }

        // Stage every backup under a fresh generation. Existing active records are
        // never overwritten or deleted while the new generation is being uploaded.
        val generation = UUID.randomUUID().toString()
        val stagedRecords = desired.map { (id, data) ->
            records.document("${generation}__${id}") to (data + ("generation" to generation))
        }

        // Read folders before staging so any local read failure leaves cloud untouched.
        val folderRows = teacherFolderDao?.observeFoldersForBackup()?.filter { it.ownerUid == uid }.orEmpty()

        // Firestore batches have a 500-write maximum; leave room below the limit.
        stagedRecords.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { (ref, data) -> batch.set(ref, data) }
            batch.commit().awaitResult()
        }

        // Switch the active pointer only after every staged batch succeeded.
        // Old generations are retained so an interrupted upload cannot destroy
        // the last known-good backup. Cleanup is intentionally a separate concern.
        root.set(
            mapOf(
                "schemaVersion" to SCHEMA_VERSION,
                "ownerUid" to uid,
                "activeGeneration" to generation,
                "recordCount" to desired.size,
                "folders" to folderRows.map { it.toCloudMap() },
                // Remove legacy schema-1 arrays so migration cannot leave a huge root document.
                "classes" to FieldValue.delete(),
                "courses" to FieldValue.delete(),
                "lectures" to FieldValue.delete(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).awaitResult()
    }

    private suspend fun restoreRecordDocuments(uid: String) {
        val rootSnapshot = firestore.collection(TEACHER_DATA).document(uid).get().awaitResult()
        val activeGeneration = rootSnapshot.getString("activeGeneration")?.takeIf { it.isNotBlank() }
        val records = firestore.collection(TEACHER_DATA).document(uid).collection(RECORDS)
        val documents = if (activeGeneration != null) {
            records.whereEqualTo("generation", activeGeneration).get().awaitResult().documents
        } else {
            records.get().awaitResult().documents.filter { document ->
                isRecordInGeneration(document.getString("generation"), null)
            }
        }

        val classes = mutableListOf<ClassEntity>()
        val courses = mutableListOf<CourseEntity>()
        val lectures = mutableListOf<LectureEntity>()
        val expectedRecordCount = rootSnapshot.getLong("recordCount")
        if (isRecordCountMismatch(expectedRecordCount, documents.size)) {
            throw IllegalStateException(
                "تعداد رکوردهای پشتیبان ابری با فهرست واقعی آن سازگار نیست؛ اطلاعات محلی دست‌نخورده باقی ماند."
            )
        }
        val rawFolders = rootSnapshot.get("folders")
        if (teacherFolderDao != null && !isValidFolderBackup(rawFolders)) {
            throw IllegalStateException(
                "فهرست پوشه‌های پشتیبان ابری ناقص یا نامعتبر است؛ اطلاعات محلی دست‌نخورده باقی ماند."
            )
        }
        val folders = if (teacherFolderDao != null) {
            (rawFolders as List<*>).map { value ->
                (value as Map<*, *>).asStringMap()!!.toFolderEntity()
            }.toMutableList()
        } else {
            mutableListOf()
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

        // Compare the account-owned local snapshot with cloud before replacing it.
        // Identical data can sync normally; divergent data is preserved for explicit
        // recovery rather than choosing a winner silently.
        val allLocalClasses = classDao.getAllForBackup()
        val allLocalCourses = courseDao.getAllForBackup()
        val allLocalLectures = lectureDao.getAllForBackup()
        val allLocalFolders = teacherFolderDao?.observeFoldersForBackup().orEmpty()
        val ownedLocalClasses = allLocalClasses.filter {
            it.id > 0L && (it.ownerUid == uid || it.ownerUid.isBlank())
        }
        val ownedClassIds = ownedLocalClasses.map { it.id }.toSet()
        val ownedLocalCourses = allLocalCourses.filter { it.id > 0L && it.classId in ownedClassIds }
        val ownedLocalLectures = allLocalLectures.filter { it.id > 0L && it.classId in ownedClassIds }
        val ownedLocalFolders = allLocalFolders.filter { it.ownerUid == uid }
        val hasLocalTeacherData = ownedLocalClasses.isNotEmpty() ||
            ownedLocalCourses.isNotEmpty() || ownedLocalLectures.isNotEmpty() || ownedLocalFolders.isNotEmpty()

        val sameAsCloud = areTeacherSnapshotsEquivalent(
            ownedLocalClasses, ownedLocalCourses, ownedLocalLectures, ownedLocalFolders,
            classes, courses, lectures, folders.filter { it.ownerUid == uid }
        )

        if (hasLocalTeacherData && !sameAsCloud) {
            throw IllegalStateException(
                "اطلاعات محلی و ابری این استاد یکسان نیستند؛ برای جلوگیری از حذف تغییرات، بازیابی خودکار متوقف شد. ابتدا از اطلاعات محلی فایل پشتیبان بگیرید."
            )
        }

        // The private cloud snapshot is per teacher. Retain other teachers' and
        // negative-ID shared rows because this restore replaces the local Room tables.
        classes += allLocalClasses.filterNot { it in ownedLocalClasses }
        courses += allLocalCourses.filter { it.id < 0L || it.classId !in ownedClassIds }
        lectures += allLocalLectures.filter { it.id < 0L || it.classId !in ownedClassIds }
        folders += allLocalFolders.filter { it.ownerUid != uid }

        // Cloud does not store device-local audio paths. Keep paths for matching
        // session IDs so an equivalent cloud restore cannot break local audio links.
        val localAudioPaths = allLocalLectures.mapNotNull { lecture ->
            lecture.audioFilePath?.let { lecture.id to it }
        }.toMap()
        val restoredLectures = lectures.map { lecture ->
            if (lecture.audioFilePath == null) {
                lecture.copy(audioFilePath = localAudioPaths[lecture.id])
            } else lecture
        }

        validateAndReplaceLocalData(classes, courses, restoredLectures, folders)
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
        val folders = (snapshot.get("folders") as? List<*>)?.map {
            (it as? Map<*, *>)?.asStringMap()?.toFolderEntity()
                ?: throw IllegalStateException("رکورد پوشه در پشتیبان ابری معتبر نیست.")
        }
        validateAndReplaceLocalData(classes, courses, lectures, folders)
    }

    internal suspend fun validateAndReplaceLocalData(
        classes: List<ClassEntity>,
        courses: List<CourseEntity>,
        lectures: List<LectureEntity>,
        folders: List<TeacherFolderEntity>? = null
    ) {
        // An empty cloud snapshot must never be interpreted as an instruction to
        // erase a populated local database. Treat it as missing/incomplete backup.
        if (isEmptyCloudBackup(classes.size, courses.size, lectures.size)) {
            throw IllegalStateException(
                "نسخه پشتیبان ابری هیچ کلاس، درس یا جلسه‌ای ندارد؛ برای جلوگیری از پاک شدن اطلاعات محلی، بازیابی متوقف شد."
            )
        }

        if (classes.any { it.id == 0L } || courses.any { it.id == 0L } || lectures.any { it.id == 0L }) {
            throw IllegalStateException("شناسه یکی از رکوردهای پشتیبان ابری خالی است.")
        }

        // Replace all related local tables atomically. If any clear/insert fails,
        // Room rolls back every table so the previous local backup remains usable.
        database.withTransaction {
            if (folders != null && teacherFolderDao != null) {
                teacherFolderDao.clearForRestore()
            }
            lectureDao.clearForRestore()
            courseDao.clearForRestore()
            classDao.clearForRestore()
            if (classes.isNotEmpty()) classDao.insertAllForRestore(classes)
            if (courses.isNotEmpty()) courseDao.insertAllForRestore(courses)
            if (lectures.isNotEmpty()) lectureDao.insertAllForRestore(lectures)
            if (folders != null && teacherFolderDao != null && folders.isNotEmpty()) {
                teacherFolderDao.insertAllForRestore(folders)
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
            addOnCompleteListener(Executor { command -> command.run() }) { task ->
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


/** Pure safety predicates are shared with unit tests. */
internal fun shouldBlockLegacyRestore(localRecordCount: Int, legacyCloudBackupExists: Boolean): Boolean =
    legacyCloudBackupExists && localRecordCount > 0

internal fun areTeacherSnapshotsEquivalent(
    localClasses: List<ClassEntity>,
    localCourses: List<CourseEntity>,
    localLectures: List<LectureEntity>,
    localFolders: List<TeacherFolderEntity>,
    cloudClasses: List<ClassEntity>,
    cloudCourses: List<CourseEntity>,
    cloudLectures: List<LectureEntity>,
    cloudFolders: List<TeacherFolderEntity>
): Boolean =
    localClasses.sortedBy { it.id } == cloudClasses.sortedBy { it.id } &&
        localCourses.sortedBy { it.id } == cloudCourses.sortedBy { it.id } &&
        localLectures.map { it.copy(audioFilePath = null) }.sortedBy { it.id } ==
            cloudLectures.map { it.copy(audioFilePath = null) }.sortedBy { it.id } &&
        localFolders.sortedBy { it.id } == cloudFolders.sortedBy { it.id }

internal fun isRecordCountMismatch(expected: Long?, actual: Int): Boolean =
    expected != null && expected != actual.toLong()

internal fun isEmptyCloudBackup(classCount: Int, courseCount: Int, lectureCount: Int): Boolean =
    classCount == 0 && courseCount == 0 && lectureCount == 0

/** A missing active generation denotes the pre-generation schema-2 backup. */
internal fun isRecordInGeneration(recordGeneration: String?, activeGeneration: String?): Boolean =
    if (activeGeneration.isNullOrBlank()) recordGeneration.isNullOrBlank()
    else recordGeneration == activeGeneration

internal fun isValidFolderBackup(raw: Any?): Boolean =
    raw is List<*> && raw.all { value ->
        val row = value as? Map<*, *> ?: return@all false
        row.keys.all { it is String } &&
            row["id"] is Number &&
            (row["ownerUid"] as? String)?.isNotBlank() == true &&
            (row["name"] as? String)?.isNotBlank() == true &&
            (row["parentFolderId"] == null || row["parentFolderId"] is Number)
    }
