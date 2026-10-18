package com.example.data.local

import android.content.Context
import androidx.room.withTransaction
import com.example.ui.screens.AuthPreferences
import com.example.ui.screens.UserRole
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.tasks.Tasks
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
            val allClasses = runBlocking { db.classDao().getAllForBackup() }
            val allCourses = runBlocking { db.courseDao().getAllForBackup() }
            val allLectures = runBlocking { db.lectureDao().getAllForBackup() }
            val classes = allClasses.filter { it.isPublished }
            val visibleClassIds = classes.map { it.id }.toSet()
            val courses = allCourses.filter { it.classId in visibleClassIds }
            val lectures = allLectures.filter { lecture -> lecture.isPublished && lecture.classId?.let { it in visibleClassIds } == true }
            val payload = JSONObject()
                .put("format", "darsyar-shared-materials")
                .put("version", 1)
                .put("teacherName", AuthPreferences.currentName(context))
                .put("classes", JSONArray().apply { classes.forEach { put(it.toJson()) } })
                .put("courses", JSONArray().apply { courses.forEach { put(it.toJson()) } })
                .put("lectures", JSONArray().apply { lectures.map { it.toJson() }.forEach { put(it) } })
            Tasks.await(
                firestore.collection("shared_materials").document(uid)
                    .set(mapOf("ownerUid" to uid, "payload" to payload.toString(), "updatedAt" to System.currentTimeMillis()))
            )
            "مطالب متنی و لینک‌های صوت با موفقیت در فضای آنلاین به‌روزرسانی شد."
        } else {
            val documents = Tasks.await(firestore.collection("shared_materials").get()).documents
                .filter { it.id != uid }
            val allClasses = mutableListOf<ClassEntity>()
            val allCourses = mutableListOf<CourseEntity>()
            val allLectures = mutableListOf<LectureEntity>()
            val validPayloads = documents.mapNotNull { document ->
                val owner = document.getString("ownerUid") ?: document.id
                val payloadText = document.getString("payload") ?: return@mapNotNull null
                val payload = runCatching { JSONObject(payloadText) }.getOrNull() ?: return@mapNotNull null
                if (payload.optString("format") != "darsyar-shared-materials") null else owner to payload
            }

            // Merge duplicate class cards by normalized name + teacher + term, but remap
            // every source class ID to the merged ID so no course or session is lost.
            fun normalized(value: String): String =
                value.trim()
                    .lowercase()
                    .replace('ي', 'ی')
                    .replace('ك', 'ک')
                    .replace('ى', 'ی')
                    .replace('ـ', ' ')
                    .replace(Regex("[\\u200c\\u200d]"), "")
                    .replace(Regex("\\s+"), " ")

            val sourceClasses = mutableListOf<Triple<String, ClassEntity, String>>()
            validPayloads.forEach { (owner, payload) ->
                payload.optJSONArray("classes")?.let { array ->
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i).toClassEntity()
                        if (!item.isPublished) continue
                        val signature = listOf(item.name, item.teacherName, item.term)
                            .joinToString("|") { normalized(it) }
                        sourceClasses += Triple(owner, item, signature)
                    }
                }
            }

            // A source class ID belongs to one teacher and must remain one class even if
            // stale duplicate records carry slightly different text after an edit.
            val canonicalSourceClasses = sourceClasses
                .groupBy { (owner, item, _) -> "$owner:${item.id}" }
                .values
                .mapNotNull { entries -> entries.maxByOrNull { it.second.createdAtMillis } }

            val classIdMap = mutableMapOf<String, Long>()
            canonicalSourceClasses.groupBy { it.third }.forEach { (signature, entries) ->
                val mergedId = stableId("merged-classes", signature)
                entries.forEach { (owner, item, _) ->
                    classIdMap["$owner:${item.id}"] = mergedId
                }
                val preferred = entries.maxByOrNull { it.second.createdAtMillis }!!.second
                allClasses += preferred.copy(
                    id = mergedId,
                    isPublished = true,
                    createdAtMillis = entries.maxOf { it.second.createdAtMillis },
                    classDateMillis = entries.maxOf { it.second.classDateMillis }
                )
            }

            validPayloads.forEach { (owner, payload) ->
                payload.optJSONArray("courses")?.let { array ->
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i).toCourse()
                        val mergedClassId = classIdMap["$owner:${item.classId}"] ?: continue
                        allCourses += item.copy(
                            id = stableId(owner, "course:${item.id}"),
                            classId = mergedClassId
                        )
                    }
                }

                payload.optJSONArray("lectures")?.let { array ->
                    val teacherLectures = (0 until array.length()).map { array.getJSONObject(it).toLecture() }
                    val teacherName = payload.optString("teacherName", "استاد")
                    val legacyClassId = stableId(owner, "class:legacy")
                    if (teacherLectures.any { it.classId == null }) {
                        allClasses += ClassEntity(
                            id = legacyClassId,
                            name = "مطالب استاد $teacherName",
                            teacherName = teacherName,
                            description = "جلسات قدیمی که به کلاس مشخصی متصل نبودند",
                            term = "",
                            classDateMillis = System.currentTimeMillis(),
                            createdAtMillis = System.currentTimeMillis()
                        )
                    }
                    teacherLectures.forEach { item ->
                        val mappedClassId = item.classId?.let { classIdMap["$owner:$it"] }
                            ?: if (item.classId == null) legacyClassId else null
                        // Do not attach material from a class that is not currently published.
                        if (mappedClassId == null) return@forEach
                        allLectures += item.copy(
                            id = stableId(owner, "lecture:${item.id}"),
                            classId = mappedClassId,
                            courseId = item.courseId?.let { stableId(owner, "course:$it") },
                            audioFilePath = null,
                            isFavorite = false
                        )
                    }
                }
            }

            // Remove exact duplicate sessions that came from source classes later merged together.
            // Different dates or different transcript/audio content are preserved.
            val uniqueLectures = allLectures.distinctBy { lecture ->
                listOf(
                    lecture.classId?.toString().orEmpty(),
                    normalized(lecture.title).replace('ي', 'ی').replace('ك', 'ک'),
                    normalized(lecture.courseName).replace('ي', 'ی').replace('ك', 'ک'),
                    lecture.dateMillis.toString(),
                    normalized(lecture.transcript).replace('ي', 'ی').replace('ك', 'ک'),
                    normalized(lecture.audioUrl.orEmpty())
                ).joinToString("|")
            }

            runBlocking {
                db.withTransaction {
                    db.lectureDao().clearCloudSharedData()
                    db.courseDao().clearCloudSharedData()
                    db.classDao().clearCloudSharedData()
                    db.classDao().insertAllForRestore(allClasses.distinctBy { it.id })
                    db.courseDao().insertAllForRestore(allCourses.distinctBy { it.id })
                    db.lectureDao().insertAllForRestore(uniqueLectures.distinctBy { it.id })
                }
            }
            "مطالب آنلاین به‌روزرسانی شد: ${uniqueLectures.size} جلسه یکتا از ${documents.size} حساب استاد."
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
        .put("classDateMillis", classDateMillis).put("isPublished", isPublished).put("createdAtMillis", createdAtMillis)

    private fun JSONObject.toClassEntity() = ClassEntity(
        id = optLong("id"), name = optString("name"), teacherName = optString("teacherName"),
        description = optString("description"), term = optString("term"),
        classDateMillis = optLong("classDateMillis"), isPublished = optBoolean("isPublished", true), createdAtMillis = optLong("createdAtMillis")
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
        .put("isPublished", isPublished).put("lastEditedMillis", lastEditedMillis)

    private fun JSONObject.toLecture() = LectureEntity(
        id = optLong("id"), title = optString("title"), courseName = optString("courseName"),
        professorName = optString("professorName"), classId = nullableLong("classId"),
        courseId = nullableLong("courseId"), dateMillis = optLong("dateMillis"),
        audioFilePath = null, audioUrl = nullableString("audioUrl"),
        audioDurationMs = optLong("audioDurationMs"), transcript = optString("transcript"),
        aiSummary = nullableString("aiSummary"), aiKeyPoints = nullableString("aiKeyPoints"),
        tags = optString("tags"), isPublished = optBoolean("isPublished", true), lastEditedMillis = optLong("lastEditedMillis")
    )

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)
}
