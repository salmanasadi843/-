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
    fun sync(context: Context): String = syncInternal(context, receiveOnly = false)

    /**
     * Allows a secondary teacher to read other teachers' published materials without
     * publishing their own snapshot. Imported rows retain negative IDs and are read-only.
     */
    fun syncPublishedForTeacher(context: Context): String {
        require(AuthPreferences.currentRole(context) == UserRole.TEACHER) {
            "این همگام‌سازی فقط برای حساب استاد است."
        }
        return syncInternal(context, receiveOnly = true)
    }

    private fun syncInternal(context: Context, receiveOnly: Boolean): String {
        val uid = AuthPreferences.currentUid(context)
            ?: error("ابتدا وارد حساب کاربری شوید.")
        val db = AppDatabase.getDatabase(context)
        val firestore = FirebaseFirestore.getInstance()

        return if (AuthPreferences.currentRole(context) == UserRole.TEACHER && !receiveOnly) {
            val allClasses = runBlocking { db.classDao().getAllForBackup() }
            val allCourses = runBlocking { db.courseDao().getAllForBackup() }
            val allLectures = runBlocking { db.lectureDao().getAllForBackup() }
            // Imported/shared records use stable negative IDs. They belong to their
            // original teacher and must never be republished or managed by this account.
            val classes = allClasses.filter { it.id > 0L && it.ownerUid == uid && it.isPublished }
            val visibleClassIds = classes.map { it.id }.toSet()
            val courses = allCourses.filter { it.id > 0L && it.classId > 0L && it.classId in visibleClassIds }
            val lectures = allLectures.filter { lecture ->
                lecture.id > 0L && lecture.isPublished &&
                    lecture.classId?.let { it > 0L && it in visibleClassIds } == true
            }
            val payload = JSONObject()
                .put("format", "darsyar-shared-materials")
                .put("version", 1)
                .put("teacherName", AuthPreferences.currentName(context))
                .put("classes", JSONArray().apply { classes.forEach { put(it.copy(ownerUid = uid).toJson()) } })
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
                        sourceClasses += Triple(owner, item.copy(ownerUid = owner, folderId = null), signature)
                    }
                }
            }

            // Normalize class identity using both metadata and its published contents.
            // This collapses duplicate copies with different source IDs while preserving
            // same-named classes that contain different courses or sessions.
            fun classFingerprint(owner: String, item: ClassEntity): String {
                val courses = validPayloads
                    .filter { it.first == owner }
                    .flatMap { (_, payload) ->
                        val array = payload.optJSONArray("courses") ?: return@flatMap emptyList<CourseEntity>()
                        (0 until array.length()).mapNotNull { index ->
                            runCatching { array.getJSONObject(index).toCourse() }.getOrNull()
                        }
                    }
                    .filter { it.classId == item.id }
                    .map { normalized(it.name) }
                    .sorted()

                val lectures = validPayloads
                    .filter { it.first == owner }
                    .flatMap { (_, payload) ->
                        val array = payload.optJSONArray("lectures") ?: return@flatMap emptyList<LectureEntity>()
                        (0 until array.length()).mapNotNull { index ->
                            runCatching { array.getJSONObject(index).toLecture() }.getOrNull()
                        }
                    }
                    .filter { it.classId == item.id }
                    .map { lecture ->
                        listOf(
                            normalized(lecture.title),
                            normalized(lecture.courseName),
                            lecture.dateMillis.toString(),
                        ).joinToString("~")
                    }
                    .sorted()

                val metadata = listOf(item.name, item.teacherName, item.term).joinToString("|") { normalized(it) }
                val content = (courses + lectures).joinToString("|")
                return "$metadata##$content"
            }

            val canonicalSourceClasses = sourceClasses
                .groupBy { (owner, item, _) -> "$owner:${item.id}" }
                .values
                .mapNotNull { entries -> entries.maxByOrNull { it.second.createdAtMillis } }

            val classIdMap = mutableMapOf<String, Long>()
            val fingerprintToClassId = mutableMapOf<String, Long>()
            canonicalSourceClasses.forEach { (owner, item, _) ->
                // The same published class can appear in feeds from more than one
                // teacher account. Use its normalized metadata + content as the identity,
                // not the feed owner, so identical copies share one imported class.
                val fingerprint = classFingerprint(owner, item)
                val importedClassId = fingerprintToClassId.getOrPut(fingerprint) {
                    stableId("shared-feed", "class-content:$fingerprint")
                }
                classIdMap["$owner:${item.id}"] = importedClassId
                if (allClasses.none { it.id == importedClassId }) {
                    allClasses += item.copy(id = importedClassId, ownerUid = owner, folderId = null, isPublished = true)
                }
            }

            // Deduplicate courses inside the canonical class and remember the canonical
            // course ID for every source course ID so sessions keep the correct relation.
            val courseIdMap = mutableMapOf<String, Long>()
            val courseFingerprintToId = mutableMapOf<String, Long>()
            validPayloads.forEach { (owner, payload) ->
                payload.optJSONArray("courses")?.let { array ->
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i).toCourse()
                        val importedClassId = classIdMap["$owner:${item.classId}"] ?: continue
                        val fingerprint = listOf(
                            importedClassId.toString(),
                            normalized(item.name),
                            normalized(item.teacherName),
                            normalized(item.description)
                        ).joinToString("|")
                        val importedCourseId = courseFingerprintToId.getOrPut(fingerprint) {
                            stableId("shared-feed", "course-content:$fingerprint")
                        }
                        courseIdMap["$owner:${item.id}"] = importedCourseId
                        if (allCourses.none { it.id == importedCourseId }) {
                            allCourses += item.copy(id = importedCourseId, classId = importedClassId)
                        }
                    }
                }

                payload.optJSONArray("lectures")?.let { array ->
                    val teacherLectures = (0 until array.length()).map { array.getJSONObject(it).toLecture() }
                    val teacherName = payload.optString("teacherName", "استاد")
                    val legacyClassId = stableId(owner, "class:legacy")
                    if (teacherLectures.any { it.classId == null } && allClasses.none { it.id == legacyClassId }) {
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
                            courseId = item.courseId?.let { courseIdMap["$owner:$it"] },
                            audioFilePath = null,
                            isFavorite = false
                        )
                    }
                }
            }

            // Remove duplicate sessions by canonical class, course name, title and date;
            // keep the richest copy so metadata/audio links are not lost.
            val uniqueLectures = allLectures
                .groupBy { lecture ->
                    listOf(
                        lecture.classId?.toString().orEmpty(),
                        normalized(lecture.courseName),
                        normalized(lecture.title),
                        lecture.dateMillis.toString()
                    ).joinToString("|")
                }
                .values
                .map { copies ->
                    copies.maxByOrNull { lecture ->
                        lecture.transcript.length +
                            (lecture.aiSummary?.length ?: 0) +
                            (lecture.aiKeyPoints?.length ?: 0) +
                            lecture.tags.length +
                            if (!lecture.audioUrl.isNullOrBlank()) 1000 else 0
                    }!!
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
        .put("classDateMillis", classDateMillis).put("isPublished", isPublished).put("createdAtMillis", createdAtMillis).put("ownerUid", ownerUid)

    private fun JSONObject.toClassEntity() = ClassEntity(
        id = optLong("id"), name = optString("name"), teacherName = optString("teacherName"),
        description = optString("description"), term = optString("term"),
        classDateMillis = optLong("classDateMillis"), isPublished = optBoolean("isPublished", true), createdAtMillis = optLong("createdAtMillis"),
        ownerUid = optString("ownerUid")
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
        .put("sortOrder", sortOrder)

    private fun JSONObject.toLecture() = LectureEntity(
        id = optLong("id"), title = optString("title"), courseName = optString("courseName"),
        professorName = optString("professorName"), classId = nullableLong("classId"),
        courseId = nullableLong("courseId"), dateMillis = optLong("dateMillis"),
        audioFilePath = null, audioUrl = nullableString("audioUrl"),
        audioDurationMs = optLong("audioDurationMs"), transcript = optString("transcript"),
        aiSummary = nullableString("aiSummary"), aiKeyPoints = nullableString("aiKeyPoints"),
        tags = optString("tags"), isPublished = optBoolean("isPublished", true), lastEditedMillis = optLong("lastEditedMillis"),
        sortOrder = optInt("sortOrder", 0)
    )

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)
}
