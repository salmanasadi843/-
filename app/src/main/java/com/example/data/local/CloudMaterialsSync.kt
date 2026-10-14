package com.example.data.local

import android.content.Context
import android.util.Log
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
            val teacherName = AuthPreferences.currentName(context)

            // Canonical per-class/per-lesson documents are the student-facing source.
            publishCanonicalRecords(firestore, uid, teacherName, allClasses, allCourses, allLectures)

            val classes = allClasses.filter { it.isPublished }
            val visibleClassIds = classes.map { it.id }.toSet()
            val courses = allCourses.filter { it.classId in visibleClassIds }
            val lectures = allLectures.filter {
                it.isPublished && it.classId?.let { classId -> classId in visibleClassIds } == true
            }
            val payload = JSONObject()
                .put("format", "darsyar-shared-materials")
                .put("version", 1)
                .put("teacherName", teacherName)
                .put("classes", JSONArray().apply { classes.forEach { put(it.toJson()) } })
                .put("withdrawnClasses", JSONArray().apply {
                    allClasses.filterNot { it.isPublished }.forEach { put(it.toJson()) }
                })
                .put("courses", JSONArray().apply { courses.forEach { put(it.toJson()) } })
                .put("lectures", JSONArray().apply { lectures.forEach { put(it.toJson()) } })
            // Retained for owner-side compatibility only; student clients cannot read this JSON.
            Tasks.await(
                firestore.collection("shared_materials").document(uid)
                    .set(mapOf("ownerUid" to uid, "payload" to payload.toString(), "updatedAt" to System.currentTimeMillis()))
            )
            "انتشار آنلاین کلاس‌ها و جلسات با رعایت مجوزهای نمایش به‌روزرسانی شد."
        } else {
            // Read the lightweight index first, then query each teacher's published
            // classes separately. The owner filter lets Firestore rules verify that
            // each teacher still has an active approval; a global classes query would
            // be denied once revocation is enforced by the rules.
            val publicationIndexes = Tasks.await(firestore.collection("publication_index").get()).documents
            val classDocuments = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
            var skippedInactiveTeachers = 0
            var deniedTeacherQueries = 0

            publicationIndexes.forEach { index ->
                val owner = index.getString("ownerUid") ?: index.id

                // Do not query a teacher whose trusted approval has been revoked.
                // This avoids one revoked teacher's denied query aborting every
                // student's sync for otherwise-active teachers.
                val approval = try {
                    Tasks.await(firestore.collection("approved_teachers").document(owner).get())
                } catch (e: Exception) {
                    Log.w("CloudMaterialsSync", "Could not check publication approval for $owner", e)
                    deniedTeacherQueries++
                    return@forEach
                }
                val approved = approval.exists() &&
                    (approval.getBoolean("enabled") == true || approval.getBoolean("approved") == true)
                if (!approved) {
                    skippedInactiveTeachers++
                    return@forEach
                }

                try {
                    classDocuments += Tasks.await(
                        firestore.collection("published_classes")
                            .whereEqualTo("ownerUid", owner)
                            .whereEqualTo("isPublished", true)
                            .get()
                    ).documents
                } catch (e: Exception) {
                    // A teacher whose profile is no longer TEACHER may still have
                    // a legacy approval document. Skip that teacher rather than
                    // failing the whole sync; report a useful error if no records
                    // can be read at all.
                    Log.w("CloudMaterialsSync", "Could not read published classes for $owner", e)
                    deniedTeacherQueries++
                }
            }

            if (publicationIndexes.isNotEmpty() && classDocuments.isEmpty() && deniedTeacherQueries > 0) {
                error("Firebase دسترسی به کلاس‌های منتشرشده را رد کرد. قوانین Firestore و وضعیت تأیید استادها را بررسی کنید (PERMISSION_DENIED).")
            }

            // Query lessons within each published class. Rules also verify the
            // parent class and active teacher approval, protecting against revocation.
            var deniedLessonQueries = 0
            val lessonDocuments = classDocuments.flatMap { classDocument ->
                val owner = classDocument.getString("ownerUid") ?: return@flatMap emptyList()
                val classId = classDocument.getString("classId") ?: return@flatMap emptyList()
                try {
                    Tasks.await(
                        firestore.collection("published_lessons")
                            .whereEqualTo("ownerUid", owner)
                            .whereEqualTo("classId", classId)
                            .whereEqualTo("isPublished", true)
                            .get()
                    ).documents
                } catch (e: Exception) {
                    Log.w("CloudMaterialsSync", "Could not read published lessons for $owner/$classId", e)
                    deniedLessonQueries++
                    emptyList()
                }
            }
            if (classDocuments.isNotEmpty() && lessonDocuments.isEmpty() && deniedLessonQueries > 0) {
                error("Firebase دسترسی به جلسات منتشرشده را رد کرد. قوانین Firestore و مجوز انتشار را بررسی کنید (PERMISSION_DENIED).")
            }
            val payloads = mutableMapOf<String, JSONObject>()

            fun payloadFor(owner: String): JSONObject = payloads.getOrPut(owner) {
                JSONObject()
                    .put("format", "darsyar-shared-materials")
                    .put("version", 1)
                    .put("teacherName", "استاد")
                    .put("classes", JSONArray())
                    .put("courses", JSONArray())
                    .put("lectures", JSONArray())
            }

            classDocuments.forEach { document ->
                val owner = document.getString("ownerUid") ?: return@forEach
                val payload = payloadFor(owner)
                document.getString("teacherName")?.let { payload.put("teacherName", it) }
                document.getString("classJson")?.let { payload.getJSONArray("classes").put(JSONObject(it)) }
                document.getString("coursesJson")?.let { json ->
                    val array = JSONArray(json)
                    for (i in 0 until array.length()) payload.getJSONArray("courses").put(array.getJSONObject(i))
                }
            }
            lessonDocuments.forEach { document ->
                val owner = document.getString("ownerUid") ?: return@forEach
                val json = document.getString("lessonJson") ?: return@forEach
                payloadFor(owner).getJSONArray("lectures").put(JSONObject(json))
            }

            val documents = payloads.entries.toList()
            val allClasses = mutableListOf<ClassEntity>()
            val allCourses = mutableListOf<CourseEntity>()
            val allLectures = mutableListOf<LectureEntity>()

            documents.forEach { (owner, payload) ->
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
                        if (item.classId == null) continue
                        allLectures += item.copy(
                            id = stableId(owner, "lecture:${item.id}"),
                            classId = stableId(owner, "class:${item.classId}"),
                            courseId = item.courseId?.let { stableId(owner, "course:$it") },
                            audioFilePath = null,
                            isFavorite = false
                        )
                    }
                }
            }

            // Manual imports create positive local IDs while online sync uses stable negative IDs.
            // Match classes by semantic identity so the same class is not shown twice.
            val localClassesAll = runBlocking { db.classDao().getAllForBackup() }
            // Apply publication changes to matching cached classes too, including classes
            // imported by older versions with positive local IDs.
            val publishedKeys = allClasses.filter { it.isPublished }.map { classKey(it.name, it.teacherName, it.term) }.toSet()
            val withdrawnKeys = mutableSetOf<String>()
            publicationIndexes.forEach { index ->
                val values = index.get("withdrawnClassKeys")
                if (values is List<*>) values.filterIsInstance<String>().forEach { withdrawnKeys += it }
            }
            localClassesAll.filter { it.id > 0L }.forEach { local ->
                val key = classKey(local.name, local.teacherName, local.term)
                when {
                    hashClassKey(key) in withdrawnKeys && local.isPublished ->
                        runBlocking { db.classDao().update(local.copy(isPublished = false)) }
                    key in publishedKeys && !local.isPublished ->
                        runBlocking { db.classDao().update(local.copy(isPublished = true)) }
                }
            }

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
            buildString {
                append("مطالب منتشرشده آنلاین به‌روزرسانی شد: ${lecturesToInsert.size} جلسه جدید از ${documents.size} حساب استاد.")
                if (skippedInactiveTeachers > 0) append(" ${skippedInactiveTeachers} حساب استاد غیرفعال نادیده گرفته شد.")
                if (deniedTeacherQueries > 0) append(" برای ${deniedTeacherQueries} حساب دسترسی وجود نداشت؛ همگام‌سازی بقیه مطالب انجام شد.")
                if (deniedLessonQueries > 0) append(" ${deniedLessonQueries} درخواست دریافت جلسه با محدودیت دسترسی روبه‌رو شد.")
            }
        }
    }

    private fun publishCanonicalRecords(
        firestore: FirebaseFirestore,
        uid: String,
        teacherName: String,
        classes: List<ClassEntity>,
        courses: List<CourseEntity>,
        lectures: List<LectureEntity>
    ) {
        val now = System.currentTimeMillis()
        val publishedClassIds = classes.filter { it.isPublished }.map { it.id }.toSet()
        val classRecords = classes.map { item ->
            val classCourses = courses.filter { it.classId == item.id }
            firestore.collection("published_classes").document(publicationDocId(uid, item.id.toString())) to mapOf(
                "ownerUid" to uid,
                "classId" to item.id.toString(),
                "isPublished" to item.isPublished,
                "teacherName" to teacherName,
                "classJson" to item.toJson().toString(),
                "coursesJson" to JSONArray().apply { classCourses.forEach { put(it.toJson()) } }.toString(),
                "updatedAt" to now
            )
        }
        val lessonRecords = lectures.map { item ->
            val classId = item.classId
            val effectivePublished = item.isPublished && classId != null && classId in publishedClassIds
            firestore.collection("published_lessons").document(publicationDocId(uid, item.id.toString())) to mapOf(
                "ownerUid" to uid,
                "lessonId" to item.id.toString(),
                "classId" to (classId?.toString() ?: ""),
                "isPublished" to effectivePublished,
                "lessonJson" to item.toJson().toString(),
                "updatedAt" to now
            )
        }
        val indexData = mapOf(
            "ownerUid" to uid,
            "withdrawnClassKeys" to classes.filterNot { it.isPublished }
                .map { hashClassKey(classKey(it.name, it.teacherName, it.term)) }.distinct(),
            "updatedAt" to now
        )

        (classRecords + lessonRecords).chunked(400).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { (reference, data) -> batch.set(reference, data) }
            Tasks.await(batch.commit())
        }
        Tasks.await(firestore.collection("publication_index").document(uid).set(indexData))

        // Deleted entities are removed from the public feed; hidden entities remain
        // as false records and stay intact in the teacher's private local database.
        val currentClassIds = classes.map { publicationDocId(uid, it.id.toString()) }.toSet()
        val currentLessonIds = lectures.map { publicationDocId(uid, it.id.toString()) }.toSet()
        val staleClasses = Tasks.await(
            firestore.collection("published_classes").whereEqualTo("ownerUid", uid).get()
        ).documents.filterNot { it.id in currentClassIds }
        val staleLessons = Tasks.await(
            firestore.collection("published_lessons").whereEqualTo("ownerUid", uid).get()
        ).documents.filterNot { it.id in currentLessonIds }
        (staleClasses.map { it.reference } + staleLessons.map { it.reference }).chunked(400).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { batch.delete(it) }
            Tasks.await(batch.commit())
        }
    }

    private fun publicationDocId(owner: String, localId: String): String = "${owner}__${localId}"

    private fun hashClassKey(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
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
        .put("transcript", transcript).put("originalTranscript", originalTranscript).put("aiSummary", aiSummary ?: JSONObject.NULL)
        .put("aiKeyPoints", aiKeyPoints ?: JSONObject.NULL).put("tags", tags)
        .put("isPublished", isPublished).put("lastEditedMillis", lastEditedMillis)

    private fun JSONObject.toLecture() = LectureEntity(
        id = optLong("id"), title = optString("title"), courseName = optString("courseName"),
        professorName = optString("professorName"), classId = nullableLong("classId"),
        courseId = nullableLong("courseId"), dateMillis = optLong("dateMillis"),
        audioFilePath = null, audioUrl = nullableString("audioUrl"),
        audioDurationMs = optLong("audioDurationMs"), transcript = optString("transcript"),
        originalTranscript = optString("originalTranscript", optString("transcript")),
        aiSummary = nullableString("aiSummary"), aiKeyPoints = nullableString("aiKeyPoints"),
        tags = optString("tags"), isPublished = optBoolean("isPublished", true), lastEditedMillis = optLong("lastEditedMillis")
    )

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)
}
