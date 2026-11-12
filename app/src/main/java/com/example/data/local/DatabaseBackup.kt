package com.example.data.local

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Portable JSON backup for all textual lesson data.
 * Audio binaries are deliberately excluded; audioUrl is retained.
 */
object DatabaseBackup {
    private data class ExportSnapshot(
        val lectures: List<LectureEntity>,
        val classes: List<ClassEntity>,
        val courses: List<CourseEntity>,
        val folders: List<TeacherFolderEntity>
    )

    fun export(context: Context, uri: Uri) {
        val db = AppDatabase.getDatabase(context)
        // Read every table in one Room transaction so the exported JSON is a
        // consistent snapshot even if edits or syncs happen during backup creation.
        val snapshot = kotlinx.coroutines.runBlocking {
            db.withTransaction {
                ExportSnapshot(
                    lectures = db.lectureDao().getAllForBackup(),
                    classes = db.classDao().getAllForBackup(),
                    courses = db.courseDao().getAllForBackup(),
                    folders = db.teacherFolderDao().observeFoldersForBackup()
                )
            }
        }
        val lectures = snapshot.lectures
        val classes = snapshot.classes
        val courses = snapshot.courses
        val folders = snapshot.folders

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

    fun import(context: Context, uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        } ?: error("فایل پشتیبان خوانده نشد.")
        val root = JSONObject(text)
        require(root.optString("format") == "darsyar-backup") { "این فایل، پشتیبان معتبر درس‌یار نیست." }
        require(root.optInt("version") in 1..1) { "نسخه فایل پشتیبان پشتیبانی نمی‌شود." }

        // Core collections must be present and correctly typed. Treating a missing
        // or malformed array as empty could silently erase valid local records.
        val lectures = root.requiredArray("lectures").toEntitiesTyped { it.toLecture() }
        val classes = root.requiredArray("classes").toEntitiesTyped { it.toClassEntity() }
        val courses = root.requiredArray("courses").toEntitiesTyped { it.toCourse() }
        val folders = if (root.has("folders")) {
            root.requiredArray("folders").toEntitiesTyped { it.toFolder() }
        } else {
            emptyList()
        }
        validateBackupIntegrity(classes, courses, lectures, folders)
        val db = AppDatabase.getDatabase(context)

        kotlinx.coroutines.runBlocking {
            db.withTransaction {
                // Keep device-local audio paths for matching lecture IDs. Portable
                // JSON intentionally excludes audio binaries and private file paths.
                val existingAudioPaths = db.lectureDao().getAllForBackup()
                    .mapNotNull { lecture -> lecture.audioFilePath?.let { lecture.id to it } }
                    .toMap()
                val lecturesToRestore = preserveLocalAudioPaths(lectures, existingAudioPaths)

                // Clear child records first, then restore parent records before courses.
                db.lectureDao().clearForRestore()
                db.courseDao().clearForRestore()
                db.classDao().clearForRestore()
                db.teacherFolderDao().clearForRestore()
                db.teacherFolderDao().insertAllForRestore(folders)
                db.classDao().insertAllForRestore(classes)
                db.courseDao().insertAllForRestore(courses)
                db.lectureDao().insertAllForRestore(lecturesToRestore)
            }
        }
        return "بازیابی انجام شد: ${lectures.size} جلسه، ${classes.size} کلاس و ${courses.size} درس."
    }

    private fun JSONObject.requiredArray(key: String): JSONArray {
        val value = opt(key)
        require(value is JSONArray) {
            "فهرست «$key» در فایل پشتیبان وجود ندارد یا ساختار آن خراب است؛ اطلاعات فعلی حفظ شد."
        }
        return value
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
        .put("aiSummary", aiSummary ?: JSONObject.NULL)
        .put("aiKeyPoints", aiKeyPoints ?: JSONObject.NULL).put("aiQuizJson", aiQuizJson ?: JSONObject.NULL)
        .put("tags", tags).put("isFavorite", isFavorite).put("isPublished", isPublished)
        .put("lastEditedMillis", lastEditedMillis).put("sortOrder", sortOrder)

    private fun JSONObject.toLecture() = LectureEntity(
        id = optLong("id"), title = getString("title"), courseName = optString("courseName"),
        professorName = optString("professorName"), classId = optNullableLong("classId"),
        courseId = optNullableLong("courseId"), dateMillis = optLong("dateMillis"),
        audioFilePath = null, audioUrl = optNullableString("audioUrl"),
        audioDurationMs = optLong("audioDurationMs"), transcript = optString("transcript"),
        originalTranscript = if (has("originalTranscript")) optString("originalTranscript") else optString("transcript"),
        aiSummary = optNullableString("aiSummary"), aiKeyPoints = optNullableString("aiKeyPoints"),
        aiQuizJson = optNullableString("aiQuizJson"), tags = optString("tags"),
        isFavorite = optBoolean("isFavorite"), isPublished = optBoolean("isPublished", true),
        lastEditedMillis = optLong("lastEditedMillis", System.currentTimeMillis()),
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


/**
 * Preserve audio paths stored on this device when a portable backup restores
 * matching lecture IDs. A backup from another device has no matching path and
 * therefore keeps the restored lecture's null audioFilePath.
 */
internal fun preserveLocalAudioPaths(
    restoredLectures: List<LectureEntity>,
    existingAudioPaths: Map<Long, String>
): List<LectureEntity> = restoredLectures.map { lecture ->
    if (lecture.audioFilePath == null) {
        lecture.copy(audioFilePath = existingAudioPaths[lecture.id])
    } else lecture
}

/**
 * Validate the complete portable snapshot before opening a destructive Room
 * replacement transaction. Negative IDs are allowed for read-only shared rows;
 * zero IDs and duplicate IDs are never valid persisted records.
 */
internal fun validateBackupIntegrity(
    classes: List<ClassEntity>,
    courses: List<CourseEntity>,
    lectures: List<LectureEntity>,
    folders: List<TeacherFolderEntity>
) {
    require(classes.isNotEmpty() || courses.isNotEmpty() || lectures.isNotEmpty()) {
        "فایل پشتیبان خالی است؛ برای جلوگیری از پاک شدن اطلاعات فعلی، بازیابی متوقف شد."
    }

    fun requireUniqueNonZeroIds(ids: List<Long>, label: String) {
        require(ids.all { it != 0L }) { "شناسهٔ یکی از رکوردهای $label صفر است." }
        require(ids.size == ids.toSet().size) { "شناسهٔ تکراری در رکوردهای $label وجود دارد." }
    }

    requireUniqueNonZeroIds(classes.map { it.id }, "کلاس")
    requireUniqueNonZeroIds(courses.map { it.id }, "درس")
    requireUniqueNonZeroIds(lectures.map { it.id }, "جلسه")
    requireUniqueNonZeroIds(folders.map { it.id }, "پوشه")

    val classIds = classes.map { it.id }.toSet()
    val coursesById = courses.associateBy { it.id }
    val foldersById = folders.associateBy { it.id }

    require(courses.all { it.classId in classIds }) {
        "یکی از درس‌ها به کلاسی اشاره می‌کند که در فایل پشتیبان وجود ندارد."
    }
    require(lectures.all { it.classId == null || it.classId in classIds }) {
        "یکی از جلسه‌ها به کلاسی اشاره می‌کند که در فایل پشتیبان وجود ندارد."
    }
    require(lectures.all { lecture ->
        lecture.courseId == null || coursesById.containsKey(lecture.courseId)
    }) {
        "یکی از جلسه‌ها به درسی اشاره می‌کند که در فایل پشتیبان وجود ندارد."
    }
    require(lectures.all { lecture ->
        val course = lecture.courseId?.let(coursesById::get)
        lecture.classId == null || course == null || course.classId == lecture.classId
    }) {
        "کلاس ثبت‌شده برای یکی از جلسه‌ها با کلاس درس آن سازگار نیست."
    }

    require(classes.all { it.folderId == null || foldersById.containsKey(it.folderId) }) {
        "یکی از کلاس‌ها به پوشه‌ای اشاره می‌کند که در فایل پشتیبان وجود ندارد."
    }
    require(folders.all { it.ownerUid.isNotBlank() && it.name.isNotBlank() }) {
        "مشخصات یکی از پوشه‌ها ناقص است."
    }
    require(folders.all { folder ->
        folder.parentFolderId == null ||
            foldersById[folder.parentFolderId]?.ownerUid == folder.ownerUid
    }) {
        "پوشهٔ والد یکی از پوشه‌ها وجود ندارد یا متعلق به استاد دیگری است."
    }

    folders.forEach { folder ->
        val visited = mutableSetOf<Long>()
        var current: TeacherFolderEntity? = folder
        while (current != null) {
            require(visited.add(current.id)) { "در ساختار پوشه‌ها چرخهٔ ارجاع وجود دارد." }
            current = current.parentFolderId?.let(foldersById::get)
        }
    }
}
