package com.example.data.local

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File

/**
 * Portable JSON backup for all textual lesson data.
 * Audio binaries are deliberately excluded; audioUrl is retained.
 */
object DatabaseBackup {
    fun export(context: Context, uri: Uri) {
        val root = createBackupJson(context)
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(root.toString(2))
        } ?: error("فایل پشتیبان برای نوشتن باز نشد.")
    }

    /**
     * Keeps an internal point-in-time copy immediately before any destructive restore.
     * The copy is retained in app-private storage until the user exports it or removes the app.
     */
    fun hasSafetySnapshot(context: Context): Boolean =
        latestSafetySnapshot(context) != null

    fun exportLatestSafetySnapshot(context: Context, uri: Uri) {
        val snapshot = latestSafetySnapshot(context)
            ?: error("نسخهٔ ایمنی پیش از بازیابی پیدا نشد.")
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(snapshot.readText(Charsets.UTF_8))
        } ?: error("فایل مقصد برای ذخیرهٔ نسخهٔ ایمنی باز نشد.")
    }

    private fun createSafetySnapshot(context: Context): File {
        val directory = File(context.filesDir, "backup-safety").apply {
            if (!exists() && !mkdirs()) error("ساخت پوشهٔ نسخهٔ ایمنی ناموفق بود.")
        }
        val snapshot = File(directory, "before-restore-${System.currentTimeMillis()}.json")
        snapshot.writeText(createBackupJson(context).toString(2), Charsets.UTF_8)
        require(snapshot.length() > 0L) { "نسخهٔ ایمنی خالی ایجاد شد؛ بازیابی متوقف شد." }
        return snapshot
    }

    private fun latestSafetySnapshot(context: Context): File? =
        File(context.filesDir, "backup-safety")
            .listFiles()
            ?.filter { it.isFile && it.name.startsWith("before-restore-") && it.extension == "json" }
            ?.maxByOrNull { it.lastModified() }

    private fun createBackupJson(context: Context): JSONObject {
        val db = AppDatabase.getDatabase(context)
        return kotlinx.coroutines.runBlocking {
            // Read every table under one Room transaction so the backup is a
            // consistent point-in-time snapshot even if background sync is active.
            db.withTransaction {
                val lectures = db.lectureDao().getAllForBackup()
                val classes = db.classDao().getAllForBackup()
                val courses = db.courseDao().getAllForBackup()
                val folders = db.teacherFolderDao().observeFoldersForBackup()

                JSONObject()
                    .put("format", "darsyar-backup")
                    .put("version", 2)
                    .put("createdAt", System.currentTimeMillis())
                    .put("lectures", JSONArray().apply { lectures.forEach { put(it.toJson()) } })
                    .put("classes", JSONArray().apply { classes.forEach { put(it.toJson()) } })
                    .put("courses", JSONArray().apply { courses.forEach { put(it.toJson()) } })
                    .put("folders", JSONArray().apply { folders.forEach { put(it.toJson()) } })
                    .put("counts", JSONObject()
                        .put("lectures", lectures.size)
                        .put("classes", classes.size)
                        .put("courses", courses.size)
                        .put("folders", folders.size))
            }
        }
    }

    fun import(context: Context, uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        } ?: error("فایل پشتیبان خوانده نشد.")
        val root = JSONObject(text)
        require(root.optString("format") == "darsyar-backup") { "این فایل، پشتیبان معتبر درس‌یار نیست." }
        val version = root.optInt("version")
        require(version in 1..2) { "نسخه فایل پشتیبان پشتیبانی نمی‌شود." }
        // Never treat a missing collection as an intentionally empty collection:
        // doing so could clear valid local data during restore.
        listOf("lectures", "classes", "courses", "folders").forEach { key ->
            require(root.has(key) && root.optJSONArray(key) != null) {
                "فایل پشتیبان ناقص است: بخش «$key» وجود ندارد یا معتبر نیست؛ اطلاعات فعلی دست‌نخورده باقی ماند."
            }
        }

        val lectures = root.optJSONArray("lectures").toEntitiesTyped {
            // Version 1 did not store publication state. Default legacy imports to
            // unpublished so a restore cannot accidentally publish old material.
            it.toLecture(legacyDefaultPublished = version >= 2)
        }
        val classes = root.optJSONArray("classes").toEntitiesTyped { it.toClassEntity() }
        val courses = root.optJSONArray("courses").toEntitiesTyped { it.toCourse() }
        val folders = root.optJSONArray("folders").toEntitiesTyped { it.toFolder() }
        requireUniqueIds(lectures.map { it.id }, "جلسه")
        requireUniqueIds(classes.map { it.id }, "کلاس")
        requireUniqueIds(courses.map { it.id }, "درس")
        requireUniqueIds(folders.map { it.id }, "پوشه")
        if (version >= 2) {
            val counts = root.optJSONObject("counts")
                ?: error("فایل پشتیبان ناقص است: شمارنده‌های کنترل سلامت وجود ندارد.")
            val actual = mapOf(
                "lectures" to lectures.size,
                "classes" to classes.size,
                "courses" to courses.size,
                "folders" to folders.size
            )
            actual.forEach { (key, count) ->
                require(counts.has(key) && counts.optInt(key, -1) == count) {
                    "فایل پشتیبان ناقص یا دستکاری‌شده است: تعداد «$key» با محتوای فایل سازگار نیست؛ اطلاعات فعلی دست‌نخورده باقی ماند."
                }
            }
        }
        // Preserve the exact current database before replacing anything.
        // If snapshot creation fails, abort before opening the destructive transaction.
        val safetySnapshot = createSafetySnapshot(context)
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
        return "بازیابی انجام شد: ${lectures.size} جلسه، ${classes.size} کلاس و ${courses.size} درس. نسخهٔ ایمنی اطلاعات قبلی نیز ذخیره شد: ${safetySnapshot.name}"
    }

    private fun requireUniqueIds(ids: List<Long>, label: String) {
        require(ids.distinct().size == ids.size) {
            "فایل پشتیبان دارای شناسهٔ تکراری برای $label است؛ اطلاعات فعلی دست‌نخورده باقی ماند."
        }
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

    private fun JSONObject.toLecture(legacyDefaultPublished: Boolean) = LectureEntity(
        id = optLong("id"), title = getString("title"), courseName = optString("courseName"),
        professorName = optString("professorName"), classId = optNullableLong("classId"),
        courseId = optNullableLong("courseId"), dateMillis = optLong("dateMillis"),
        audioFilePath = null, audioUrl = optNullableString("audioUrl"),
        audioDurationMs = optLong("audioDurationMs"), transcript = optString("transcript"),
        originalTranscript = optString("originalTranscript", optString("transcript")),
        aiSummary = optNullableString("aiSummary"), aiKeyPoints = optNullableString("aiKeyPoints"),
        aiQuizJson = optNullableString("aiQuizJson"), tags = optString("tags"),
        isFavorite = optBoolean("isFavorite"), isPublished = optBoolean("isPublished", legacyDefaultPublished),
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
