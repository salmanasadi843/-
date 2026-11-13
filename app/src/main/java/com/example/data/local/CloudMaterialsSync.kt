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
            val payloadRecords = buildList {
                classes.forEach { add("class" to it.copy(ownerUid = uid).toJson()) }
                courses.forEach { add("course" to it.toJson()) }
                lectures.forEach { add("lecture" to it.toJson()) }
            }
            // One document per record avoids the 1 MiB limit of a single JSON feed.
            // A fresh generation is staged first; the root pointer changes only after
            // every chunk succeeds, so readers keep seeing the previous valid version.
            val feedRef = firestore.collection("shared_materials").document(uid)
            val oldRoot = Tasks.await(feedRef.get())
            val oldOwner = oldRoot.getString("ownerUid")
            require(!oldRoot.exists() || oldOwner == uid) {
                "مالکیت مطالب آنلاین با حساب فعلی تطبیق ندارد؛ انتشار متوقف شد."
            }
            val generation = java.util.UUID.randomUUID().toString()
            val recordRefs = payloadRecords.mapIndexed { index, pair ->
                val json = pair.second.toString()
                require(json.toByteArray(Charsets.UTF_8).size < 900_000) {
                    "یکی از جلسات به‌تنهایی بیش از حد مجاز بزرگ است؛ انتشار متوقف شد و نسخه قبلی حفظ شد."
                }
                val recordId = "${pair.first}_${when (pair.first) {
                    "class" -> pair.second.optLong("id")
                    "course" -> pair.second.optLong("id")
                    else -> pair.second.optLong("id")
                }}"
                firestore.collection("shared_materials").document(uid)
                    .collection("records").document("${generation}__${recordId}") to mapOf(
                        "ownerUid" to uid,
                        "generation" to generation,
                        "recordType" to pair.first,
                        "dataJson" to json
                    )
            }
            recordRefs.chunked(400).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { (ref, data) -> batch.set(ref, data) }
                Tasks.await(batch.commit())
            }
            // Publish the new generation atomically after all records are staged.
            Tasks.await(feedRef.set(mapOf(
                "ownerUid" to uid,
                "schemaVersion" to 2,
                "activeGeneration" to generation,
                "teacherName" to AuthPreferences.currentName(context),
                "classCount" to classes.size,
                "courseCount" to courses.size,
                "lectureCount" to lectures.size,
                "recordCount" to recordRefs.size,
                "payload" to com.google.firebase.firestore.FieldValue.delete(),
                "updatedAt" to System.currentTimeMillis()
            ), com.google.firebase.firestore.SetOptions.merge()))
            "انتشار مرحله‌ای انجام شد: ${classes.size} کلاس، ${courses.size} درس و ${lectures.size} جلسه. نسخه قبلی تا تکمیل انتشار جدید حفظ شد."
        } else {
            val documents = Tasks.await(firestore.collection("shared_materials").get()).documents
                .filter { it.id != uid }
            // An empty or partially readable feed must never erase the previously
            // imported shared cache on this device.
            require(documents.isNotEmpty()) {
                "هیچ فید انتشار معتبری دریافت نشد؛ اطلاعات اشتراکی قبلی گوشی حفظ شد."
            }
            val allClasses = mutableListOf<ClassEntity>()
            val allCourses = mutableListOf<CourseEntity>()
            val allLectures = mutableListOf<LectureEntity>()
            val validPayloads = documents.map { document ->
                val owner = document.getString("ownerUid") ?: document.id
                require(owner == document.id) { "مالک فید انتشار معتبر نیست؛ همگام‌سازی متوقف شد." }
                val schemaVersion = document.getLong("schemaVersion")
                if (schemaVersion == 2L) {
                    val generation = requireNotNull(document.getString("activeGeneration")) {
                        "نسل فعال انتشار استاد $owner مشخص نیست؛ اطلاعات قبلی حفظ شد."
                    }
                    val rows = Tasks.await(
                        firestore.collection("shared_materials").document(document.id)
                            .collection("records").whereEqualTo("generation", generation).get()
                    ).documents
                    val expectedCount = document.getLong("recordCount")?.toInt()
                        ?: error("تعداد رکوردهای انتشار استاد $owner موجود نیست؛ اطلاعات قبلی حفظ شد.")
                    require(expectedCount >= 0 && rows.size == expectedCount) {
                        "انتشار استاد $owner ناقص دریافت شد؛ اطلاعات اشتراکی قبلی حفظ شد."
                    }
                    val payload = JSONObject()
                        .put("format", "darsyar-shared-materials")
                        .put("version", 2)
                        .put("teacherName", document.getString("teacherName") ?: "استاد")
                    val grouped = mapOf(
                        "class" to JSONArray(),
                        "course" to JSONArray(),
                        "lecture" to JSONArray()
                    )
                    rows.forEach { row ->
                        require(row.getString("ownerUid") == owner && row.getString("generation") == generation) {
                            "مالکیت یا نسل یکی از رکوردهای انتشار ناسازگار است؛ اطلاعات قبلی حفظ شد."
                        }
                        val type = requireNotNull(row.getString("recordType")) {
                            "نوع یکی از رکوردهای انتشار مشخص نیست؛ اطلاعات قبلی حفظ شد."
                        }
                        val json = requireNotNull(row.getString("dataJson")) {
                            "محتوای یکی از رکوردهای انتشار ناقص است؛ اطلاعات قبلی حفظ شد."
                        }
                        val array = requireNotNull(grouped[type]) {
                            "نوع رکورد انتشار ناشناخته است؛ اطلاعات قبلی حفظ شد."
                        }
                        array.put(JSONObject(json))
                    }
                    require(grouped.getValue("class").length() == document.getLong("classCount")?.toInt() &&
                        grouped.getValue("course").length() == document.getLong("courseCount")?.toInt() &&
                        grouped.getValue("lecture").length() == document.getLong("lectureCount")?.toInt()) {
                        "تعداد کلاس‌ها یا جلسات انتشار با فهرست رکوردها سازگار نیست؛ اطلاعات قبلی حفظ شد."
                    }
                    payload.put("classes", grouped.getValue("class"))
                        .put("courses", grouped.getValue("course"))
                        .put("lectures", grouped.getValue("lecture"))
                    owner to payload
                } else {
                    // Backward compatibility: read existing version-1 JSON feeds.
                    val payloadText = requireNotNull(document.getString("payload")) {
                        "فید قدیمی استاد $owner قابل خواندن نیست؛ اطلاعات قبلی حفظ شد."
                    }
                    val payload = JSONObject(payloadText)
                    require(payload.optString("format") == "darsyar-shared-materials") {
                        "قالب فید قدیمی استاد $owner معتبر نیست؛ اطلاعات قبلی حفظ شد."
                    }
                    owner to payload
                }
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
            // Class identity is its normalized metadata within the source teacher.
            // Session/course differences are content updates, not a new class:
            // all source class IDs with this identity map to one imported ID,
            // and their courses/lectures are collected below (without deleting data).
            // Including content in this key caused a second class card whenever
            // one published copy had an extra session.
            fun classFingerprint(owner: String, item: ClassEntity): String {
                val metadata = listOf(item.name, item.teacherName, item.term)
                    .joinToString("|") { normalized(it) }
                return "$owner|$metadata"
            }

            val canonicalSourceClasses = sourceClasses
                .groupBy { (owner, item, _) -> "$owner:${item.id}" }
                .values
                .mapNotNull { entries -> entries.maxByOrNull { it.second.createdAtMillis } }

            // Reuse an existing positive-ID class (owned by this account or a known
            // foreign teacher) when the same published class arrives in the shared
            // feed. Otherwise the positive local/legacy card and negative shared
            // card can both appear in the read-only section.
            // Matching foreign rows is safe here: they remain untouched; only the
            // incoming shared course/session foreign keys target their existing ID.
            val localClassIdByFingerprint = runBlocking { db.classDao().getAllForBackup() }
                .filter { it.id > 0L && it.ownerUid.isNotBlank() }
                .groupBy { item ->
                    val metadata = listOf(item.name, item.teacherName, item.term)
                        .joinToString("|") { normalized(it) }
                    "${item.ownerUid}|$metadata"
                }
                .mapValues { (_, copies) -> copies.minByOrNull { it.createdAtMillis }!!.id }

            val classIdMap = mutableMapOf<String, Long>()
            val fingerprintToClassId = mutableMapOf<String, Long>()
            canonicalSourceClasses.forEach { (owner, item, _) ->
                val fingerprint = classFingerprint(owner, item)
                val importedClassId = fingerprintToClassId.getOrPut(fingerprint) {
                    localClassIdByFingerprint[fingerprint]
                        ?: stableId("shared-feed", "class-content:$fingerprint")
                }
                classIdMap["$owner:${item.id}"] = importedClassId
                // Existing positive local classes are not reinserted or overwritten.
                // Incoming shared courses/sessions map to their ID and remain read-only.
                if (importedClassId < 0L && allClasses.none { it.id == importedClassId }) {
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
