package com.example.data.local

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import com.example.ui.screens.AuthPreferences
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Portable JSON backup for all textual lesson data.
 * Audio binaries are deliberately excluded; audioUrl is retained.
 */
object DatabaseBackup {
    fun export(context: Context, uri: Uri) {
        val db = AppDatabase.getDatabase(context)
        val lectures = kotlinx.coroutines.runBlocking { db.lectureDao().getAllForBackup() }
        val classes = kotlinx.coroutines.runBlocking { db.classDao().getAllForBackup() }
        val courses = kotlinx.coroutines.runBlocking { db.courseDao().getAllForBackup() }
        val folders = kotlinx.coroutines.runBlocking {
            db.teacherFolderDao().observeFoldersForBackup()
        }

        val root = JSONObject()
            .put("format", "darsyar-backup")
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("lectures", JSONArray().apply { lectures.forEach { put(it.toJson()) } })
            .put("classes", JSONArray().apply { classes.forEach { put(it.toJson()) } })
            .put("courses", JSONArray().apply { courses.forEach { put(it.toJson()) } })
            .put("folders", JSONArray().apply { folders.forEach { put(it.toJson()) } })

        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(root.toString(2))
        } ?: error("فایل پشتیبان برای نوشتن باز نشد.")
    }

    /**
     * Merge a portable backup without clearing any existing Room data.
     * Only records owned by the signed-in teacher are imported; shared/foreign
     * class records are ignored. Conflicting IDs abort the whole transaction.
     */
    fun import(context: Context, uri: Uri): String {
        val uid = AuthPreferences.currentUid(context)?.takeIf { it.isNotBlank() }
            ?: error("برای بازیابی امن، ابتدا با حساب استاد وارد شوید.")

        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        } ?: error("فایل پشتیبان خوانده نشد.")
        val root = JSONObject(text)
        require(root.optString("format") == "darsyar-backup") { "این فایل، پشتیبان معتبر درس‌یار نیست." }
        require(root.optInt("version") in 1..1) { "نسخه فایل پشتیبان پشتیبانی نمی‌شود." }
        val requiredCollections = listOf("lectures", "classes", "courses", "folders")
        validateBackupCollections(
            presentKeys = requiredCollections.filter { root.has(it) }.toSet(),
            arrayKeys = requiredCollections.filter { root.optJSONArray(it) != null }.toSet()
        )

        val sourceLectures = root.optJSONArray("lectures").toEntitiesTyped { it.toLecture() }
        val sourceClasses = root.optJSONArray("classes").toEntitiesTyped { it.toClassEntity() }
        val sourceCourses = root.optJSONArray("courses").toEntitiesTyped { it.toCourse() }
        val sourceFolders = root.optJSONArray("folders").toEntitiesTyped { it.toFolder() }

        val ownedClasses = sourceClasses
            .filter { it.ownerUid.isBlank() || it.ownerUid == uid }
            .map { if (it.ownerUid.isBlank()) it.copy(ownerUid = uid) else it }
        require(ownedClasses.all { it.id > 0L }) { "شناسه یکی از کلاس‌های متعلق به شما معتبر نیست." }
        require(ownedClasses.map { it.id }.distinct().size == ownedClasses.size) {
            "در فایل پشتیبان شناسه کلاس تکراری وجود دارد."
        }
        val ownedClassIds = ownedClasses.map { it.id }.toSet()

        // Exclude negative/shared IDs and every lecture/course attached to a foreign class.
        val lectures = sourceLectures.filter {
            it.id > 0L && (it.classId == null || it.classId in ownedClassIds)
        }
        val courses = sourceCourses.filter {
            it.id > 0L && it.classId in ownedClassIds
        }
        val folders = sourceFolders
            .filter { it.ownerUid.isBlank() || it.ownerUid == uid }
            .map { if (it.ownerUid.isBlank()) it.copy(ownerUid = uid) else it }

        require(lectures.map { it.id }.distinct().size == lectures.size) {
            "در فایل پشتیبان شناسه جلسه تکراری وجود دارد."
        }
        require(courses.map { it.id }.distinct().size == courses.size) {
            "در فایل پشتیبان شناسه درس تکراری وجود دارد."
        }
        require(folders.all { it.id > 0L && it.ownerUid == uid }) {
            "مالکیت یا شناسه یکی از پوشه‌های پشتیبان معتبر نیست."
        }
        require(folders.map { it.id }.distinct().size == folders.size) {
            "در فایل پشتیبان شناسه پوشه تکراری وجود دارد."
        }
        require(courses.all { it.classId in ownedClassIds }) {
            "یکی از درس‌ها به کلاس متعلق به شما متصل نیست."
        }
        require(lectures.all { it.classId == null || it.classId in ownedClassIds }) {
            "یکی از جلسه‌ها به کلاس متعلق به شما متصل نیست."
        }
        require(lectures.all { it.courseId == null || it.courseId in courses.map { c -> c.id }.toSet() }) {
            "ارتباط یکی از جلسه‌ها با درس موجود در فایل پشتیبان معتبر نیست."
        }
        val folderIds = folders.map { it.id }.toSet()
        require(ownedClasses.all { it.folderId == null || it.folderId in folderIds }) {
            "یکی از کلاس‌ها به پوشه‌ای خارج از فایل پشتیبان متصل است."
        }
        require(folders.all { it.parentFolderId == null || it.parentFolderId in folderIds }) {
            "ساختار پوشه‌های پشتیبان کامل نیست."
        }

        val db = AppDatabase.getDatabase(context)
        var addedClasses = 0
        var addedCourses = 0
        var addedLectures = 0
        var addedFolders = 0
        val ignoredForeignLectures = sourceLectures.size - lectures.size
        val ignoredForeignClasses = sourceClasses.size - ownedClasses.size

        kotlinx.coroutines.runBlocking {
            db.withTransaction {
                val existingClasses = db.classDao().getAllForBackup().associateBy { it.id }
                val existingCourses = db.courseDao().getAllForBackup().associateBy { it.id }
                val existingLectures = db.lectureDao().getAllForBackup().associateBy { it.id }
                val existingFolders = db.teacherFolderDao().observeFoldersForBackup().associateBy { it.id }

                ownedClasses.forEach { item ->
                    val existing = existingClasses[item.id]
                    if (existing == null) addedClasses++
                    else require(existing == item) {
                        "شناسه کلاس ${item.id} در دستگاه با اطلاعات متفاوتی وجود دارد؛ هیچ تغییری اعمال نشد."
                    }
                }
                courses.forEach { item ->
                    val existing = existingCourses[item.id]
                    if (existing == null) addedCourses++
                    else require(existing == item) {
                        "شناسه درس ${item.id} در دستگاه با اطلاعات متفاوتی وجود دارد؛ هیچ تغییری اعمال نشد."
                    }
                }
                lectures.forEach { item ->
                    val existing = existingLectures[item.id]
                    if (existing == null) addedLectures++
                    else {
                        val sameBackupFields =
                            existing.id == item.id &&
                            existing.title == item.title &&
                            existing.courseName == item.courseName &&
                            existing.professorName == item.professorName &&
                            existing.classId == item.classId &&
                            existing.courseId == item.courseId &&
                            existing.dateMillis == item.dateMillis &&
                            existing.audioUrl == item.audioUrl &&
                            existing.audioDurationMs == item.audioDurationMs &&
                            existing.transcript == item.transcript &&
                            existing.originalTranscript == item.originalTranscript &&
                            existing.isPublished == item.isPublished &&
                            existing.aiSummary == item.aiSummary &&
                            existing.aiKeyPoints == item.aiKeyPoints &&
                            existing.aiQuizJson == item.aiQuizJson &&
                            existing.tags == item.tags &&
                            existing.isFavorite == item.isFavorite &&
                            existing.lastEditedMillis == item.lastEditedMillis &&
                            existing.sortOrder == item.sortOrder
                        require(sameBackupFields) {
                            "شناسه جلسه ${item.id} در دستگاه با محتوای متفاوتی وجود دارد؛ هیچ تغییری اعمال نشد."
                        }
                    }
                }
                folders.forEach { item ->
                    val existing = existingFolders[item.id]
                    if (existing == null) addedFolders++
                    else require(existing == item) {
                        "شناسه پوشه ${item.id} در دستگاه با اطلاعات متفاوتی وجود دارد؛ هیچ تغییری اعمال نشد."
                    }
                }

                // Check all relationships against the union of existing and incoming records.
                val allClassIds = existingClasses.keys + ownedClasses.map { it.id }
                val allCourseIds = existingCourses.keys + courses.map { it.id }
                require(courses.all { it.classId in allClassIds }) {
                    "یکی از درس‌ها به کلاسی متصل است که در دستگاه یا بکاپ وجود ندارد."
                }
                require(lectures.all {
                    (it.classId == null || it.classId in allClassIds) &&
                        (it.courseId == null || it.courseId in allCourseIds)
                }) {
                    "ارتباط یکی از جلسه‌ها با کلاس یا درس معتبر نیست."
                }

                if (addedClasses > 0) classDaoInsert(db, ownedClasses.filter { it.id !in existingClasses })
                if (addedCourses > 0) courseDaoInsert(db, courses.filter { it.id !in existingCourses })
                if (addedLectures > 0) lectureDaoInsert(db, lectures.filter { it.id !in existingLectures })
                if (addedFolders > 0) folderDaoInsert(db, folders.filter { it.id !in existingFolders })
            }
        }

        return "ادغام امن انجام شد: $addedClasses کلاس، $addedLectures جلسه، $addedCourses درس و $addedFolders پوشه جدید اضافه شد؛ $ignoredForeignClasses کلاس و $ignoredForeignLectures جلسه متعلق به حساب‌های دیگر نادیده گرفته شد. اطلاعات قبلی دستگاه حذف نشد."
    }

    /** Reject incomplete backups before reading records or opening a Room transaction. */
    internal fun validateBackupCollections(
        presentKeys: Set<String>,
        arrayKeys: Set<String>
    ) {
        listOf("lectures", "classes", "courses", "folders").forEach { key ->
            require(key in presentKeys && key in arrayKeys) {
                "بخش $key در فایل پشتیبان وجود ندارد یا آرایه معتبر نیست؛ بازیابی متوقف شد و اطلاعات دستگاه تغییر نکرد."
            }
        }
    }

    private suspend fun classDaoInsert(db: AppDatabase, items: List<ClassEntity>) {
        if (items.isNotEmpty()) db.classDao().insertAllForRestore(items)
    }

    private suspend fun courseDaoInsert(db: AppDatabase, items: List<CourseEntity>) {
        if (items.isNotEmpty()) db.courseDao().insertAllForRestore(items)
    }

    private suspend fun lectureDaoInsert(db: AppDatabase, items: List<LectureEntity>) {
        if (items.isNotEmpty()) db.lectureDao().insertAllForRestore(items)
    }

    private suspend fun folderDaoInsert(db: AppDatabase, items: List<TeacherFolderEntity>) {
        if (items.isNotEmpty()) db.teacherFolderDao().insertAllForRestore(items)
    }

    private fun JSONArray?.toEntities(convert: (JSONObject) -> Any): List<Any> {
        if (this == null) return emptyList()
        return (0 until length()).map { convert(getJSONObject(it)) }
    }

    private inline fun <reified T> JSONArray?.toEntitiesTyped(convert: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { convert(getJSONObject(it)) }
    }

    private fun LectureEntity.toJson() = JSONObject()
        .put("id", id).put("title", title).put("courseName", courseName)
        .put("professorName", professorName).put("classId", classId ?: JSONObject.NULL)
        .put("courseId", courseId ?: JSONObject.NULL).put("dateMillis", dateMillis)
        .put("audioUrl", audioUrl ?: JSONObject.NULL).put("audioDurationMs", audioDurationMs)
        .put("transcript", transcript).put("originalTranscript", originalTranscript)
        .put("isPublished", isPublished).put("aiSummary", aiSummary ?: JSONObject.NULL)
        .put("aiKeyPoints", aiKeyPoints ?: JSONObject.NULL).put("aiQuizJson", aiQuizJson ?: JSONObject.NULL)
        .put("tags", tags).put("isFavorite", isFavorite).put("lastEditedMillis", lastEditedMillis).put("sortOrder", sortOrder)

    private fun JSONObject.toLecture() = LectureEntity(
        id = optLong("id"), title = getString("title"), courseName = optString("courseName"),
        professorName = optString("professorName"), classId = optNullableLong("classId"),
        courseId = optNullableLong("courseId"), dateMillis = optLong("dateMillis"),
        audioFilePath = null, audioUrl = optNullableString("audioUrl"),
        audioDurationMs = optLong("audioDurationMs"), transcript = optString("transcript"),
        originalTranscript = optString("originalTranscript"),
        isPublished = optBoolean("isPublished", true),
        aiSummary = optNullableString("aiSummary"), aiKeyPoints = optNullableString("aiKeyPoints"),
        aiQuizJson = optNullableString("aiQuizJson"), tags = optString("tags"),
        isFavorite = optBoolean("isFavorite"), lastEditedMillis = optLong("lastEditedMillis", System.currentTimeMillis()),
        sortOrder = optInt("sortOrder", 0)
    )

    private fun ClassEntity.toJson() = JSONObject()
        .put("id", id).put("name", name).put("teacherName", teacherName)
        .put("description", description).put("term", term)
        .put("classDateMillis", classDateMillis).put("isPublished", isPublished).put("createdAtMillis", createdAtMillis)
        .put("ownerUid", ownerUid).put("folderId", folderId ?: JSONObject.NULL)

    private fun JSONObject.toClassEntity() = ClassEntity(
        id = optLong("id"), name = getString("name"), teacherName = optString("teacherName"),
        description = optString("description"), term = optString("term"),
        classDateMillis = optLong("classDateMillis"), isPublished = optBoolean("isPublished", true),
        createdAtMillis = optLong("createdAtMillis"), ownerUid = optString("ownerUid"),
        folderId = optNullableLong("folderId")
    )

    private fun CourseEntity.toJson() = JSONObject()
        .put("id", id).put("classId", classId).put("name", name)
        .put("teacherName", teacherName).put("description", description)
        .put("createdAtMillis", createdAtMillis)

    private fun JSONObject.toCourse() = CourseEntity(
        id = optLong("id"), classId = optLong("classId"), name = getString("name"),
        teacherName = optString("teacherName"), description = optString("description"),
        createdAtMillis = optLong("createdAtMillis")
    )

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key)

    private fun TeacherFolderEntity.toJson() = JSONObject()
        .put("id", id).put("ownerUid", ownerUid).put("name", name)
        .put("parentFolderId", parentFolderId ?: JSONObject.NULL).put("createdAtMillis", createdAtMillis)

    private fun JSONObject.toFolder() = TeacherFolderEntity(
        id = optLong("id"), ownerUid = optString("ownerUid"), name = optString("name"),
        parentFolderId = optNullableLong("parentFolderId"), createdAtMillis = optLong("createdAtMillis")
    )

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)
}