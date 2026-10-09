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
        val db = AppDatabase.getDatabase(context)
        val lectures = kotlinx.coroutines.runBlocking { db.lectureDao().getAllForBackup() }
        val classes = kotlinx.coroutines.runBlocking { db.classDao().getAllForBackup() }
        val courses = kotlinx.coroutines.runBlocking { db.courseDao().getAllForBackup() }

        val root = JSONObject()
            .put("format", "darsyar-backup")
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("lectures", JSONArray().apply { lectures.forEach { put(it.toJson()) } })
            .put("classes", JSONArray().apply { classes.forEach { put(it.toJson()) } })
            .put("courses", JSONArray().apply { courses.forEach { put(it.toJson()) } })

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

        val lectures = root.optJSONArray("lectures").toEntitiesTyped { it.toLecture() }
        val classes = root.optJSONArray("classes").toEntitiesTyped { it.toClassEntity() }
        val courses = root.optJSONArray("courses").toEntitiesTyped { it.toCourse() }
        val db = AppDatabase.getDatabase(context)

        kotlinx.coroutines.runBlocking {
            db.withTransaction {
                // Clear child records first, then restore parent records before courses.
                db.lectureDao().clearForRestore()
                db.courseDao().clearForRestore()
                db.classDao().clearForRestore()
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
        .put("transcript", transcript).put("aiSummary", aiSummary ?: JSONObject.NULL)
        .put("aiKeyPoints", aiKeyPoints ?: JSONObject.NULL).put("aiQuizJson", aiQuizJson ?: JSONObject.NULL)
        .put("tags", tags).put("isFavorite", isFavorite).put("lastEditedMillis", lastEditedMillis)

    private fun JSONObject.toLecture() = LectureEntity(
        id = optLong("id"), title = getString("title"), courseName = optString("courseName"),
        professorName = optString("professorName"), classId = optNullableLong("classId"),
        courseId = optNullableLong("courseId"), dateMillis = optLong("dateMillis"),
        audioFilePath = null, audioUrl = optNullableString("audioUrl"),
        audioDurationMs = optLong("audioDurationMs"), transcript = optString("transcript"),
        aiSummary = optNullableString("aiSummary"), aiKeyPoints = optNullableString("aiKeyPoints"),
        aiQuizJson = optNullableString("aiQuizJson"), tags = optString("tags"),
        isFavorite = optBoolean("isFavorite"), lastEditedMillis = optLong("lastEditedMillis", System.currentTimeMillis())
    )

    private fun ClassEntity.toJson() = JSONObject()
        .put("id", id).put("name", name).put("teacherName", teacherName)
        .put("description", description).put("term", term)
        .put("classDateMillis", classDateMillis).put("createdAtMillis", createdAtMillis)

    private fun JSONObject.toClassEntity() = ClassEntity(
        id = optLong("id"), name = getString("name"), teacherName = optString("teacherName"),
        description = optString("description"), term = optString("term"),
        classDateMillis = optLong("classDateMillis"), createdAtMillis = optLong("createdAtMillis")
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

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)
}
