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
        val lectures = kotlinx.coroutines.runBlocking { db.lectureDao().getAllForBackup() }
        val classes = kotlinx.coroutines.runBlocking { db.classDao().getAllForBackup() }
        val courses = kotlinx.coroutines.runBlocking { db.courseDao().getAllForBackup() }
        val folders = kotlinx.coroutines.runBlocking { db.teacherFolderDao().observeFoldersForBackup() }
        val root = JSONObject()
            .put("format", "darsyar-backup")
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("lectures", JSONArray().apply { lectures.forEach { put(it.toJson()) } })
            .put("classes", JSONArray().apply { classes.forEach { put(it.toJson()) } })
            .put("courses", JSONArray().apply { courses.forEach { put(it.toJson()) } })
            .put("folders", JSONArray().apply { folders.forEach { put(it.toJson()) } })
        return root.toString(2)
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

        val lectures = root.optJSONArray("lectures").toEntitiesTyped { it.toLecture() }
        val classes = root.optJSONArray("classes").toEntitiesTyped { it.toClassEntity() }
        val courses = root.optJSONArray("courses").toEntitiesTyped { it.toCourse() }
        val folders = root.optJSONArray("folders").toEntitiesTyped { it.toFolder() }
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
