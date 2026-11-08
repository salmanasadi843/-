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
    fun export(context: Context, uri: Uri) {
        val json = createBackupJson(context)
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(json)
        } ?: error("فایل پشتیبان برای نوشتن باز نشد.")
    }

    /** Stores a private recovery snapshot before destructive import. */
    fun createPreRestoreSafetyCopy(context: Context): java.io.File {
        val file = java.io.File(context.filesDir, "darsyar-pre-restore-backup.json")
        file.writeText(createBackupJson(context), Charsets.UTF_8)
        require(file.length() > 0L) { "پشتیبان اضطراری خالی است؛ بازیابی متوقف شد." }
        return file
    }

    /** Copies the last pre-restore snapshot to a user-selected document. */
    fun exportPreRestoreSafetyCopy(context: Context, uri: Uri) {
        val file = java.io.File(context.filesDir, "darsyar-pre-restore-backup.json")
        require(file.exists() && file.length() > 0L) { "پشتیبان اضطراری داخلی پیدا نشد." }
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            file.inputStream().bufferedReader(Charsets.UTF_8).use { reader -> reader.copyTo(it) }
        } ?: error("فایل مقصد برای ذخیره پشتیبان اضطراری باز نشد.")
    }

    private fun createBackupJson(context: Context): String {
        val db = AppDatabase.getDatabase(context)
        // Read every table inside one Room transaction so the exported file represents
        // one consistent database snapshot even if the user edits data during export.
        return kotlinx.coroutines.runBlocking {
            db.withTransaction {
                val lectures = db.lectureDao().getAllForBackup()
                val classes = db.classDao().getAllForBackup()
                val courses = db.courseDao().getAllForBackup()
                val folders = db.teacherFolderDao().observeFoldersForBackup()
                JSONObject()
                    .put("format", "darsyar-backup")
                    .put("version", 1)
                    .put("createdAt", System.currentTimeMillis())
                    .put("lectures", JSONArray().apply { lectures.forEach { put(it.toJson()) } })
                    .put("classes", JSONArray().apply { classes.forEach { put(it.toJson()) } })
                    .put("courses", JSONArray().apply { courses.forEach { put(it.toJson()) } })
                    .put("folders", JSONArray().apply { folders.forEach { put(it.toJson()) } })
                    .toString(2)
            }
        }
    }

    fun import(context: Context, uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        } ?: error("فایل پشتیبان خوانده نشد.")
        val root = JSONObject(text)
        require(root.optString("format") == "darsyar-backup") { "این فایل، پشتیبان معتبر درس‌یار نیست." }
        require(root.optInt("version") in 1..1) { "نسخه فایل پشتیبان پشتیبانی نمی‌شود." }
        require(root.optJSONArray("lectures") != null && root.optJSONArray("classes") != null &&
            root.optJSONArray("courses") != null && root.optJSONArray("folders") != null) {
            "ساختار فایل پشتیبان ناقص است؛ برای جلوگیری از پاک شدن اطلاعات، بازیابی متوقف شد."
        }
        // An accidentally selected empty/incomplete export must never erase a populated database.
        val incomingRecordCount = listOf("lectures", "classes", "courses", "folders")
            .sumOf { root.getJSONArray(it).length() }
        require(incomingRecordCount > 0) {
            "فایل پشتیبان هیچ رکوردی ندارد؛ برای جلوگیری از حذف اطلاعات فعلی، بازیابی متوقف شد."
        }

        val lectures = root.optJSONArray("lectures").toEntitiesTyped { it.toLecture() }
        val classes = root.optJSONArray("classes").toEntitiesTyped { it.toClassEntity() }
        val courses = root.optJSONArray("courses").toEntitiesTyped { it.toCourse() }
        val folders = root.optJSONArray("folders").toEntitiesTyped { it.toFolder() }
        validateRestoreRelations(lectures, classes, courses, folders)
        val db = AppDatabase.getDatabase(context)

        kotlinx.coroutines.runBlocking {
            db.withTransaction {
                // Clear child records first, then restore parent records before courses.
                db.lectureDao().clearForRestore()
                db.courseDao().clearForRestore()
                db.classDao().clearForRestore()
                db.teacherFolderDao().clearForRestore()
                db.teacherFolderDao().insertAllForRestore(folders)
                db.classDao().insertAllForRestore(classes)
                db.courseDao().insertAllForRestore(courses)
                db.lectureDao().insertAllForRestore(lectures)
            }
        }
        return "بازیابی انجام شد: ${lectures.size} جلسه، ${classes.size} کلاس و ${courses.size} درس."
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
        originalTranscript = optString("originalTranscript", optString("transcript")),
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
 * Validate the relationships in a parsed backup before the current database is cleared.
 * Keeping this pure makes malformed restore files testable without touching a real database.
 */
internal fun validateRestoreRelations(
    lectures: List<LectureEntity>,
    classes: List<ClassEntity>,
    courses: List<CourseEntity>,
    folders: List<TeacherFolderEntity>
) {
    fun <T> requireUniqueIds(items: List<T>, id: (T) -> Long, label: String) {
        require(items.all { id(it) != 0L }) {
            "شناسه صفر در بخش $label معتبر نیست؛ بازیابی متوقف شد."
        }
        require(items.map(id).toSet().size == items.size) {
            "شناسه‌های تکراری در بخش $label وجود دارد؛ بازیابی متوقف شد."
        }
    }

    requireUniqueIds(lectures, { it.id }, "جلسات")
    requireUniqueIds(classes, { it.id }, "کلاس‌ها")
    requireUniqueIds(courses, { it.id }, "درس‌ها")
    requireUniqueIds(folders, { it.id }, "پوشه‌ها")

    val classById = classes.associateBy { it.id }
    val courseById = courses.associateBy { it.id }
    val folderById = folders.associateBy { it.id }

    courses.forEach { course ->
        require(classById.containsKey(course.classId)) {
            "یکی از درس‌ها به کلاسی اشاره می‌کند که در فایل پشتیبان وجود ندارد؛ بازیابی متوقف شد."
        }
    }

    lectures.forEach { lecture ->
        lecture.classId?.let { classId ->
            require(classById.containsKey(classId)) {
                "یکی از جلسات به کلاسی اشاره می‌کند که در فایل پشتیبان وجود ندارد؛ بازیابی متوقف شد."
            }
        }
        lecture.courseId?.let { courseId ->
            val course = requireNotNull(courseById[courseId]) {
                "یکی از جلسات به درسی اشاره می‌کند که در فایل پشتیبان وجود ندارد؛ بازیابی متوقف شد."
            }
            require(lecture.classId == null || lecture.classId == course.classId) {
                "کلاس و درس مرتبط با یکی از جلسات با هم سازگار نیستند؛ بازیابی متوقف شد."
            }
        }
    }

    folders.forEach { folder ->
        folder.parentFolderId?.let { parentId ->
            val parent = requireNotNull(folderById[parentId]) {
                "پوشه والد در فایل پشتیبان وجود ندارد؛ بازیابی متوقف شد."
            }
            require(parent.ownerUid == folder.ownerUid && parent.id != folder.id) {
                "ساختار مالکیت یا والد پوشه‌ها ناسازگار است؛ بازیابی متوقف شد."
            }
        }
    }

    folders.forEach { folder ->
        val visited = mutableSetOf<Long>()
        var current: TeacherFolderEntity? = folder
        while (current != null) {
            require(visited.add(current.id)) {
                "درخت پوشه‌ها در فایل پشتیبان حلقه دارد؛ بازیابی متوقف شد."
            }
            current = current.parentFolderId?.let(folderById::get)
        }
    }

    classes.forEach { item ->
        item.folderId?.let { folderId ->
            val folder = requireNotNull(folderById[folderId]) {
                "یکی از کلاس‌ها به پوشه‌ای اشاره می‌کند که در فایل پشتیبان وجود ندارد؛ بازیابی متوقف شد."
            }
            require(folder.ownerUid == item.ownerUid) {
                "مالک پوشه و کلاس با هم مطابقت ندارند؛ بازیابی متوقف شد."
            }
        }
    }
}
