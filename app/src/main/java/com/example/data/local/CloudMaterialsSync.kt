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
            val classes = allClasses.filter { it.isPublished }
            val publishedClassIds = classes.map { it.id }.toSet()
            val courses = runBlocking { db.courseDao().getAllForBackup() }
                .filter { it.classId in publishedClassIds }
            val lectures = runBlocking { db.lectureDao().getAllForBackup() }
                .filter { it.classId == null || it.classId in publishedClassIds }
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
                    val teacherLectures = (0 until array.length()).map { array.getJSONObject(it).toLecture() }
                    val legacyClassId = stableId(owner, "class:legacy")
                    // Only create a legacy bucket when the teacher has no real classes.
                    if (teacherLectures.any { it.classId == null } &&
                        (payload.optJSONArray("classes")?.length() ?: 0) == 0) {
                        val teacherName = payload.optString("teacherName", "استاد")
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
                        allLectures += item.copy(
                            id = stableId(owner, "lecture:${item.id}"),
                            classId = item.classId?.let { stableId(owner, "class:$it") } ?: legacyClassId,
                            courseId = item.courseId?.let { stableId(owner, "course:$it") },
                            audioFilePath = null,
                            isFavorite = false
                        )
                    }
                }
            }

            // Manual imports create positive local IDs while online sync uses stable negative IDs.
            // Match classes by semantic identity so the same class is not shown twice.
            val localClasses = runBlocking { db.classDao().getAllForBackup() }.filter { it.id > 0L }
            val localClassByKey = localClasses.associateBy { classKey(it.name, it.teacherName, it.term) }
            val cloudClassByKey = allClasses.groupBy { classKey(it.name, it.teacherName, it.term) }
                .mapValues { (_, group) -> group.firstOrNull { it.isPublished } ?: group.first() }
            val classIdRemap = mutableMapOf<Long, Long>()
            val classesToInsert = mutableListOf<ClassEntity>()

            allClasses.forEach { incoming ->
                val key = classKey(incoming.name, incoming.teacherName, incoming.term)
                val existingLocal = localClassByKey[key]
                val canonicalCloud = cloudClassByKey[key]
                val canonicalId = existingLocal?.id ?: canonicalCloud?.id ?: incoming.id
                classIdRemap[incoming.id] = canonicalId
                if (existingLocal == null && canonicalCloud?.id == incoming.id && incoming.isPublished) {
                    classesToInsert += incoming
                }
            }

            val publishedClassIds = (classesToInsert.map { it.id } + localClasses
                .filter { local -> allClasses.any {
                    classKey(it.name, it.teacherName, it.term) == classKey(local.name, local.teacherName, local.term) && it.isPublished
                } }
                .map { it.id }).toSet()

            val localCourses = runBlocking { db.courseDao().getAllForBackup() }.filter { it.id > 0L }
            val courseIdRemap = mutableMapOf<Long, Long>()
            val coursesToInsert = mutableListOf<CourseEntity>()
            allCourses.filter { (classIdRemap[it.classId] ?: Long.MIN_VALUE) in publishedClassIds }.forEach { incoming ->
                val mappedClassId = classIdRemap[incoming.classId] ?: incoming.classId
                val existing = localCourses.firstOrNull {
                    it.classId == mappedClassId &&
                        normalized(it.name) == normalized(incoming.name) &&
                        normalized(it.teacherName) == normalized(incoming.teacherName)
                }
                if (existing != null) {
                    courseIdRemap[incoming.id] = existing.id
                } else {
                    coursesToInsert += incoming.copy(classId = mappedClassId)
                    courseIdRemap[incoming.id] = incoming.id
                }
            }

            val localLectures = runBlocking { db.lectureDao().getAllForBackup() }.filter { it.id > 0L }
            val lecturesToInsert = allLectures
                .filter { (classIdRemap[it.classId ?: 0L] ?: Long.MIN_VALUE) in publishedClassIds }
                .map { item ->
                    item.copy(
                        classId = item.classId?.let { classIdRemap[it] ?: it },
                        courseId = item.courseId?.let { courseIdRemap[it] ?: it }
                    )
                }
                .distinctBy { "${it.classId}:${normalized(it.title)}:${it.dateMillis}:${normalized(it.courseName)}" }
                .filterNot { incoming ->
                    localLectures.any { local ->
                        local.classId == incoming.classId &&
                            normalized(local.title) == normalized(incoming.title) &&
                            local.dateMillis == incoming.dateMillis &&
                            normalized(local.courseName) == normalized(incoming.courseName)
                    }
                }
            runBlocking {
                db.withTransaction {
                    db.lectureDao().clearCloudSharedData()
                    db.courseDao().clearCloudSharedData()
                    db.classDao().clearCloudSharedData()
                    db.classDao().insertAllForRestore(classesToInsert.distinctBy { it.id })
                    db.courseDao().insertAllForRestore(coursesToInsert.distinctBy { "${it.classId}:${normalized(it.name)}:${normalized(it.teacherName)}" })
                    db.lectureDao().insertAllForRestore(lecturesToInsert.distinctBy { it.id })
                }
            }
            "مطالب منتشرشده آنلاین به‌روزرسانی شد: ${lecturesToInsert.size} جلسه جدید از ${documents.size} حساب استاد."
        }
    }

    private fun normalized(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun classKey(name: String, teacher: String, term: String): String =
        "${normalized(name)}|${normalized(teacher)}|${normalized(term)}"

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
        .put("isPublished", isPublished)

    private fun JSONObject.toClassEntity() = ClassEntity(
        id = optLong("id"), name = optString("name"), teacherName = optString("teacherName"),
        description = optString("description"), term = optString("term"),
        classDateMillis = optLong("classDateMillis"), createdAtMillis = optLong("createdAtMillis"),
        isPublished = if (has("isPublished")) optBoolean("isPublished") else true
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
