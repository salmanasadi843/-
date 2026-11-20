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
            publishSplitDocuments(context, uid, db, firestore)
        } else {
            val indexes = Tasks.await(firestore.collection("publication_index").get()).documents
                .filter { it.id != uid }
            val indexedOwners = indexes.map { it.getString("ownerUid") ?: it.id }.toSet()
            val legacyDocuments = Tasks.await(firestore.collection("shared_materials").get()).documents
                .filter { it.id != uid && (it.getString("ownerUid") ?: it.id) !in indexedOwners }

            val validPayloads = mutableListOf<Pair<String, JSONObject>>()
            indexes.forEach { index ->
                val owner = index.getString("ownerUid") ?: index.id
                val classIds = (index.get("classIds") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                val lessonIds = (index.get("lessonIds") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                val refs = classIds.map { firestore.collection("published_classes").document("${owner}__${it}") } +
                    lessonIds.map { firestore.collection("published_lessons").document("${owner}__${it}") }
                val snapshots = refs.chunked(100).flatMap { chunk ->
                    if (chunk.isEmpty()) emptyList() else Tasks.await(firestore.getAll(*chunk.toTypedArray()))
                }
                val classesArray = JSONArray()
                val coursesById = linkedMapOf<String, JSONObject>()
                val lecturesArray = JSONArray()
                snapshots.forEach { doc ->
                    if (!doc.exists() || doc.getString("ownerUid") != owner || doc.getBoolean("isPublished") != true) return@forEach
                    if (doc.reference.parent.id == "published_classes") {
                        val id = doc.getString("classId") ?: return@forEach
                        classesArray.put(JSONObject()
                            .put("id", id.toLongOrNull() ?: return@forEach)
                            .put("name", doc.getString("name") ?: "")
                            .put("teacherName", doc.getString("teacherName") ?: "")
                            .put("description", doc.getString("description") ?: "")
                            .put("term", doc.getString("term") ?: "")
                            .put("classDateMillis", doc.getLong("classDateMillis") ?: 0L)
                            .put("isPublished", true)
                            .put("createdAtMillis", doc.getLong("createdAtMillis") ?: 0L)
                            .put("ownerUid", owner))
                    } else {
                        val classId = doc.getString("classId") ?: return@forEach
                        val lessonId = doc.getString("lessonId") ?: return@forEach
                        val courseId = doc.getString("courseId")
                        val courseName = doc.getString("courseName") ?: ""
                        val teacherName = doc.getString("professorName") ?: ""
                        if (!courseId.isNullOrBlank()) {
                            coursesById.putIfAbsent(courseId, JSONObject()
                                .put("id", courseId.toLongOrNull() ?: 0L)
                                .put("classId", classId.toLongOrNull() ?: 0L)
                                .put("name", courseName)
                                .put("teacherName", teacherName)
                                .put("description", "")
                                .put("createdAtMillis", 0L))
                        }
                        lecturesArray.put(JSONObject()
                            .put("id", lessonId.toLongOrNull() ?: 0L)
                            .put("title", doc.getString("title") ?: "")
                            .put("courseName", courseName)
                            .put("professorName", teacherName)
                            .put("classId", classId.toLongOrNull() ?: JSONObject.NULL)
                            .put("courseId", courseId?.toLongOrNull() ?: JSONObject.NULL)
                            .put("dateMillis", doc.getLong("dateMillis") ?: 0L)
                            .put("audioUrl", doc.getString("audioUrl") ?: JSONObject.NULL)
                            .put("audioDurationMs", doc.getLong("audioDurationMs") ?: 0L)
                            .put("transcript", doc.getString("transcript") ?: "")
                            .put("originalTranscript", doc.getString("originalTranscript") ?: "")
                            .put("aiSummary", doc.getString("aiSummary") ?: JSONObject.NULL)
                            .put("aiKeyPoints", doc.getString("aiKeyPoints") ?: JSONObject.NULL)
                            .put("tags", doc.getString("tags") ?: "")
                            .put("isPublished", true)
                            .put("lastEditedMillis", doc.getLong("lastEditedMillis") ?: 0L)
                            .put("sortOrder", (doc.getLong("sortOrder") ?: 0L).toInt()))
                    }
                }
                val payload = JSONObject()
                    .put("format", "darsyar-shared-materials")
                    .put("version", 2)
                    .put("teacherName", index.getString("teacherName") ?: "استاد")
                    .put("classes", classesArray)
                    .put("courses", JSONArray().apply { coursesById.values.forEach { put(it) } })
                    .put("lectures", lecturesArray)
                validPayloads += owner to payload
            }

            legacyDocuments.forEach { document ->
                val owner = document.getString("ownerUid") ?: document.id
                val payloadText = document.getString("payload") ?: return@forEach
                val payload = runCatching { JSONObject(payloadText) }.getOrNull() ?: return@forEach
                if (payload.optString("format") == "darsyar-shared-materials") validPayloads += owner to payload
            }
            val documentsCount = indexes.size + legacyDocuments.size
            val allClasses = mutableListOf<ClassEntity>()
            val allCourses = mutableListOf<CourseEntity>()
            val allLectures = mutableListOf<LectureEntity>()

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

            fun classFingerprint(owner: String, item: ClassEntity): String {
                val courses = validPayloads.filter { it.first == owner }.flatMap { (_, payload) ->
                    val array = payload.optJSONArray("courses") ?: return@flatMap emptyList<CourseEntity>()
                    (0 until array.length()).mapNotNull { index -> runCatching { array.getJSONObject(index).toCourse() }.getOrNull() }
                }.filter { it.classId == item.id }.map { normalized(it.name) }.sorted()
                val lectures = validPayloads.filter { it.first == owner }.flatMap { (_, payload) ->
                    val array = payload.optJSONArray("lectures") ?: return@flatMap emptyList<LectureEntity>()
                    (0 until array.length()).mapNotNull { index -> runCatching { array.getJSONObject(index).toLecture() }.getOrNull() }
                }.filter { it.classId == item.id }.map { lecture ->
                    listOf(normalized(lecture.title), normalized(lecture.courseName), lecture.dateMillis.toString()).joinToString("~")
                }.sorted()
                val metadata = listOf(item.name, item.teacherName, item.term).joinToString("|") { normalized(it) }
                return "$metadata##${(courses + lectures).joinToString("|")}"
            }

            val canonicalSourceClasses = sourceClasses.groupBy { (owner, item, _) -> "$owner:${item.id}" }
                .values.mapNotNull { entries -> entries.maxByOrNull { it.second.createdAtMillis } }
            val classIdMap = mutableMapOf<String, Long>()
            val fingerprintToClassId = mutableMapOf<String, Long>()
            canonicalSourceClasses.forEach { (owner, item, _) ->
                val fingerprint = classFingerprint(owner, item)
                val importedClassId = fingerprintToClassId.getOrPut(fingerprint) {
                    stableId("shared-feed", "class-content:$fingerprint")
                }
                classIdMap["$owner:${item.id}"] = importedClassId
                if (allClasses.none { it.id == importedClassId }) {
                    allClasses += item.copy(id = importedClassId, ownerUid = owner, folderId = null, isPublished = true)
                }
            }

            val courseIdMap = mutableMapOf<String, Long>()
            val courseFingerprintToId = mutableMapOf<String, Long>()
            validPayloads.forEach { (owner, payload) ->
                payload.optJSONArray("courses")?.let { array ->
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i).toCourse()
                        val importedClassId = classIdMap["$owner:${item.classId}"] ?: continue
                        val fingerprint = listOf(importedClassId.toString(), normalized(item.name),
                            normalized(item.teacherName), normalized(item.description)).joinToString("|")
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
                        allClasses += ClassEntity(id = legacyClassId, name = "مطالب استاد $teacherName",
                            teacherName = teacherName, description = "جلسات قدیمی که به کلاس مشخصی متصل نبودند",
                            term = "", classDateMillis = System.currentTimeMillis(), createdAtMillis = System.currentTimeMillis())
                    }
                    teacherLectures.forEach { item ->
                        val mappedClassId = item.classId?.let { classIdMap["$owner:$it"] }
                            ?: if (item.classId == null) legacyClassId else null
                        if (mappedClassId == null) return@forEach
                        allLectures += item.copy(id = stableId(owner, "lecture:${item.id}"), classId = mappedClassId,
                            courseId = item.courseId?.let { courseIdMap["$owner:$it"] }, audioFilePath = null, isFavorite = false)
                    }
                }
            }

            val uniqueLectures = allLectures.groupBy { lecture ->
                listOf(lecture.classId?.toString().orEmpty(), normalized(lecture.courseName),
                    normalized(lecture.title), lecture.dateMillis.toString()).joinToString("|")
            }.values.map { copies ->
                copies.maxByOrNull { lecture ->
                    lecture.transcript.length + (lecture.aiSummary?.length ?: 0) +
                        (lecture.aiKeyPoints?.length ?: 0) + lecture.tags.length +
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
            "مطالب آنلاین به‌روزرسانی شد: ${uniqueLectures.size} جلسه یکتا از $documentsCount حساب استاد."
        }


    private fun publishSplitDocuments(
        context: Context,
        uid: String,
        db: AppDatabase,
        firestore: FirebaseFirestore
    ): String {
        val allClasses = runBlocking { db.classDao().getAllForBackup() }
        val allCourses = runBlocking { db.courseDao().getAllForBackup() }
        val allLectures = runBlocking { db.lectureDao().getAllForBackup() }
        val classes = allClasses.filter { it.id > 0L && it.ownerUid == uid && it.isPublished }
        val classIds = classes.map { it.id }.toSet()
        val courses = allCourses.filter { it.id > 0L && it.classId in classIds }
        val lessons = allLectures.filter { it.id > 0L && it.isPublished && it.classId in classIds }

        val classDocs = classes.map { item ->
            val id = item.id.toString()
            firestore.collection("published_classes").document("${uid}__${id}") to mapOf<String, Any?>(
                "ownerUid" to uid, "classId" to id, "name" to item.name,
                "teacherName" to item.teacherName, "description" to item.description,
                "term" to item.term, "classDateMillis" to item.classDateMillis,
                "createdAtMillis" to item.createdAtMillis, "isPublished" to true
            )
        }
        val courseById = courses.associateBy { it.id }
        val lessonDocs = lessons.map { item ->
            val id = item.id.toString()
            val course = item.courseId?.let { courseById[it] }
            val classId = item.classId!!.toString()
            firestore.collection("published_lessons").document("${uid}__${id}") to mapOf<String, Any?>(
                "ownerUid" to uid, "lessonId" to id, "classId" to classId,
                "courseId" to item.courseId?.toString(), "title" to item.title,
                "courseName" to (course?.name ?: item.courseName),
                "professorName" to item.professorName, "dateMillis" to item.dateMillis,
                "audioUrl" to item.audioUrl, "audioDurationMs" to item.audioDurationMs,
                "transcript" to item.transcript, "originalTranscript" to item.originalTranscript,
                "aiSummary" to item.aiSummary, "aiKeyPoints" to item.aiKeyPoints,
                "tags" to item.tags, "isPublished" to true,
                "lastEditedMillis" to item.lastEditedMillis, "sortOrder" to item.sortOrder.toLong()
            )
        }
        val indexRef = firestore.collection("publication_index").document(uid)
        val oldIndex = Tasks.await(indexRef.get())
        val oldClassIds = (oldIndex.get("classIds") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val oldLessonIds = (oldIndex.get("lessonIds") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val newClassIds = classes.map { it.id.toString() }
        val newLessonIds = lessons.map { it.id.toString() }

        fun ensureSmall(label: String, data: Map<String, Any?>) {
            val bytes = JSONObject(data).toString().toByteArray(Charsets.UTF_8).size
            check(bytes < 850_000) { "$label حجیم‌تر از حد امن Firestore است؛ هیچ سندی ارسال نشد." }
        }
        (classDocs + lessonDocs).forEachIndexed { i, pair -> ensureSmall("یکی از مطالب منتشرشده", pair.second) }
        val indexData = mapOf<String, Any?>(
            "ownerUid" to uid, "teacherName" to AuthPreferences.currentName(context),
            "classIds" to newClassIds, "lessonIds" to newLessonIds,
            "schemaVersion" to 2L, "updatedAtMillis" to System.currentTimeMillis()
        )
        ensureSmall("فهرست انتشار", indexData)

        fun writeBatches(items: List<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any?>>>) {
            items.chunked(400).forEach { group ->
                val batch = firestore.batch()
                group.forEach { (ref, data) -> batch.set(ref, data) }
                Tasks.await(batch.commit())
            }
        }
        writeBatches(classDocs)
        writeBatches(lessonDocs)
        Tasks.await(indexRef.set(indexData))

        val staleClassRefs = oldClassIds.filter { it !in newClassIds }
            .map { firestore.collection("published_classes").document("${uid}__${it}") }
        val staleLessonRefs = oldLessonIds.filter { it !in newLessonIds }
            .map { firestore.collection("published_lessons").document("${uid}__${it}") }
        (staleClassRefs + staleLessonRefs).chunked(400).forEach { group ->
            val batch = firestore.batch()
            group.forEach { ref -> batch.set(ref, mapOf("isPublished" to false), com.google.firebase.firestore.SetOptions.merge()) }
            Tasks.await(batch.commit())
        }
        "انتشار مطالب موفق بود: ${classes.size} کلاس و ${lessons.size} جلسه. داده‌ها در اسناد جداگانه ذخیره شدند."
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
        originalTranscript = optString("originalTranscript", optString("transcript")),
        aiSummary = nullableString("aiSummary"), aiKeyPoints = nullableString("aiKeyPoints"),
        tags = optString("tags"), isPublished = optBoolean("isPublished", true), lastEditedMillis = optLong("lastEditedMillis"),
        sortOrder = optInt("sortOrder", 0)
    )

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)
}
