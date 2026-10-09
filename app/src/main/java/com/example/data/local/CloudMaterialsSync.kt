package com.example.data.local

import android.content.Context
import com.example.ui.screens.AuthPreferences
import com.example.ui.screens.UserRole
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Publishes and retrieves lesson text metadata through Firestore.
 * Audio binaries are never uploaded; only an optional audio URL is shared.
 */
object CloudMaterialsSync {
    fun sync(context: Context): String {
        val uid = AuthPreferences.currentUid(context)
            ?: error("ابتدا وارد حساب کاربری شوید.")
        val db = AppDatabase.getDatabase(context)
        val firestore = FirebaseFirestore.getInstance()

        return if (AuthPreferences.currentRole(context) == UserRole.TEACHER) {
            val classes = runBlocking { db.classDao().getAllForBackup() }
            val courses = runBlocking { db.courseDao().getAllForBackup() }
            val lectures = runBlocking { db.lectureDao().getAllForBackup() }
            val payload = JSONObject()
                .put("format", "darsyar-shared-materials")
                .put("version", 1)
                .put("teacherName", AuthPreferences.currentName(context))
                .put("classes", JSONArray().apply { classes.forEach { put(it.toJson()) } })
                .put("courses", JSONArray().apply { courses.forEach { put(it.toJson()) } })
                .put("lectures", JSONArray().apply { lectures.map { it.toJson() }.forEach { put(it) } })
            firestore.collection("shared_materials").document(uid)
                .set(mapOf("ownerUid" to uid, "payload" to payload.toString(), "updatedAt" to System.currentTimeMillis()))
                .get()
            "مطالب متنی و لینک‌های صوت با موفقیت در فضای آنلاین به‌روزرسانی شد."
        } else {
            val documents = firestore.collection("shared_materials").get().get().documents
                .filter { it.id != uid }
            val allClasses = mutableListOf<ClassEntity>()
            val allCourses = mutableListOf<CourseEntity>()
            val allLectures = mutableListOf<LectureEntity>()

            documents.forEach { document ->
                val owner = document.getString("ownerUid") ?: document.id
                val payloadText = document.getString("payload") ?: return@forEach
                val payload = JSONObject(payloadText)
                if (payload.optString("format") != "darsyar-shared-materials") return@forEach
                payload.optJSONArray("classes")?.let { array ->
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i).toClassEntity()
                        allClasses += item.copy(id = stableId(owner, "class:${item.id}"))
                    }
                }
                payload.optJSONArray("courses")?.let { array ->
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i).toCourse()
                        allCourses += item.copy(
                            id = stableId(owner, "course:${item.id}"),
                            classId = stableId(owner, "class:${item.classId}")
                        )
                    }
                }
                payload.optJSONArray("lectures")?.let { array ->
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i).toLecture()
                        allLectures += item.copy(
                            id = stableId(owner, "lecture:${item.id}"),
                            classId = item.classId?.let { stableId(owner, "class:$it") },
                            courseId = item.courseId?.let { stableId(owner, "course:$it") },
                            audioFilePath = null,
                            isFavorite = false
                        )
                    }
                }
            }

            runBlocking {
                db.withTransaction {
                    db.lectureDao().clearCloudSharedData()
                    db.courseDao().clearCloudSharedData()
                    db.classDao().clearCloudSharedData()
                    db.classDao().insertAllForRestore(allClasses.distinctBy { it.id })
                    db.courseDao().insertAllForRestore(allCourses.distinctBy { it.id })
                    db.lectureDao().insertAllForRestore(allLectures.distinctBy { it.id })
                }
            }
            "مطالب آنلاین به‌روزرسانی شد: ${allLectures.size} جلسه از ${documents.size} حساب استاد."
        }
    }

    private fun stableId(owner: String, key: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$owner:$key".toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0..7) value = (value shl 8) or (bytes[i].toLong() and 0xff)
        return -((value and Long.MAX_VALUE).coerceAtLeast(1L))
    }

    private fun ClassEntity.toJson() = JSONObject()
        .put("id", id).put("name", name).put("teacherName", teacherName)
        .put("description", description).put("term", term)
        .put("classDateMillis", classDateMillis).put("createdAtMillis", createdAtMillis)

    private fun JSONObject.toClassEntity() = ClassEntity(
        id = optLong("id"), name = optString("name"), teacherName = optString("teacherName"),
        description = optString("description"), term = optString("term"),
        classDateMillis = optLong("classDateMillis"), createdAtMillis = optLong("createdAtMillis")
    )

    private fun CourseEntity.toJson() = JSONObject()
        .put("id", id).put("classId", classId).put("name", name)
        .put("teacherName", teacherName).put("description", description)
        .put("createdAtMillis", createdAtMillis)

    private fun JSONObject.toCourse() = CourseEntity(
        id = optLong("id"), classId = optLong("classId"), name = optString("name"),
        teacherName = optString("teacherName"), description = optString("description"),
        createdAtMillis = optLong("createdAtMillis")
    )

    private fun LectureEntity.toJson() = JSONObject()
        .put("id", id).put("title", title).put("courseName", courseName)
        .put("professorName", professorName).put("classId", classId ?: JSONObject.NULL)
        .put("courseId", courseId ?: JSONObject.NULL).put("dateMillis", dateMillis)
        .put("audioUrl", audioUrl ?: JSONObject.NULL).put("audioDurationMs", audioDurationMs)
        .put("transcript", transcript).put("aiSummary", aiSummary ?: JSONObject.NULL)
        .put("aiKeyPoints", aiKeyPoints ?: JSONObject.NULL).put("tags", tags)
        .put("lastEditedMillis", lastEditedMillis)

    private fun JSONObject.toLecture() = LectureEntity(
        id = optLong("id"), title = optString("title"), courseName = optString("courseName"),
        professorName = optString("professorName"), classId = nullableLong("classId"),
        courseId = nullableLong("courseId"), dateMillis = optLong("dateMillis"),
        audioFilePath = null, audioUrl = nullableString("audioUrl"),
        audioDurationMs = optLong("audioDurationMs"), transcript = optString("transcript"),
        aiSummary = nullableString("aiSummary"), aiKeyPoints = nullableString("aiKeyPoints"),
        tags = optString("tags"), lastEditedMillis = optLong("lastEditedMillis")
    )

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)
}
