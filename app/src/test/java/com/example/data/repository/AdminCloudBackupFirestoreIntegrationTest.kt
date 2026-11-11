package com.example.data.repository

import android.content.Context
import androidx.room.Room
import com.example.data.local.AppDatabase
import com.example.data.local.ClassEntity
import com.example.data.local.CourseEntity
import com.example.data.local.LectureEntity
import com.example.data.local.TeacherFolderEntity
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executor

/**
 * End-to-end admin backup restore test against the Firestore Emulator only.
 * CI starts the emulator; this test never targets a production Firebase project.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdminCloudBackupFirestoreIntegrationTest {
    @Test
    fun restoreThenExport_preservesTwoTeachersClassesLecturesAndOwners() = runBlocking {
        try {
        println("RESTORE_TEST: start")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = "restore-emulator-${UUID.randomUUID()}"
        val app = FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId("demo-darsyar-rules")
                .setApplicationId("1:123456789:android:restore-test")
                .setApiKey("fake-emulator-api-key")
                .build(),
            appName
        ) ?: error("Firebase Emulator test app could not initialize")
        println("RESTORE_TEST: FirebaseApp initialized")
        // This integration test validates backup serialization and generation commits.
        // It uses a dedicated permissive emulator-only rules file; production rules are
        // covered independently by firestore.rules.test.mjs.
        println("RESTORE_TEST: FirebaseApp initialized; configuring Firestore emulator")
        val firestore = FirebaseFirestore.getInstance(app)
        firestore.useEmulator("127.0.0.1", 8080)

        println("RESTORE_TEST: Firestore emulator configured")
        val uidA = "integration-A-${UUID.randomUUID()}"
        val uidB = "integration-B-${UUID.randomUUID()}"
        val backup = aggregateBackup(uidA, uidB)

        println("RESTORE_TEST: starting valid restore")
        val report = AdminCloudBackupRestorer(firestore).restoreAll(backup.toString())
        println("RESTORE_TEST: valid restore finished")
        assertEquals(2, report.teachersRestored)
        assertEquals(30, report.recordsRestored)

        println("RESTORE_TEST: starting export after restore")
        val exported = AdminCloudBackupExporter(firestore).exportAllTeachers()
        println("RESTORE_TEST: export after restore finished")
        assertEquals(2, exported.getInt("teacherCount"))
        assertEquals(30, exported.getInt("recordCount"))
        val teachers = exported.getJSONArray("teachers")
        val byOwner = (0 until teachers.length()).associate { i ->
            val teacher = teachers.getJSONObject(i)
            teacher.getString("ownerUid") to teacher
        }
        assertTrue(uidA in byOwner)
        assertTrue(uidB in byOwner)
        for (uid in listOf(uidA, uidB)) {
            val records = byOwner.getValue(uid).getJSONArray("records")
            assertEquals(15, records.length())
            val rows = (0 until records.length()).map { records.getJSONObject(it) }
            assertTrue(rows.all { it.getJSONObject("data").getString("ownerUid") == uid })
            assertTrue(rows.any { it.getString("documentId") == "class_${if (uid == uidA) 101 else 202}" })
            val classId = if (uid == uidA) 101 else 202
            val courseId = if (uid == uidA) 301 else 402
            val lectureId = if (uid == uidA) 501 else 602
            assertTrue(rows.any { it.getString("documentId") == "class_$classId" })
            assertTrue(rows.any { it.getString("documentId") == "course_$courseId" })
            assertTrue(rows.any { it.getString("documentId") == "lecture_$lectureId" })
            val textFields = listOf("transcript", "originalTranscript", "aiSummary", "aiKeyPoints", "aiQuizJson", "tags")
            val lectureRecord = rows.single { it.getString("documentId") == "lecture_$lectureId" }
                .getJSONObject("data")
            for (field in textFields) {
                val chunks = rows
                    .filter { it.getString("documentId").startsWith("lecture_text_${lectureId}_${field}_") }
                    .sortedBy { it.getJSONObject("data").getInt("index") }
                assertEquals(listOf(0, 1), chunks.map { it.getJSONObject("data").getInt("index") })
                assertEquals(
                    listOf("متن $field جلسه $lectureId؛ بخش نخست ", "و بخش دوم."),
                    chunks.map { it.getJSONObject("data").getString("value") }
                )
                assertEquals(
                    "متن $field جلسه $lectureId؛ بخش نخست و بخش دوم.",
                    chunks.joinToString("") { it.getJSONObject("data").getString("value") }
                )
                assertEquals(2, lectureRecord.getJSONObject("textChunkCounts").getInt(field))
            }
            assertEquals(1, byOwner.getValue(uid).getJSONObject("root").getJSONArray("folders").length())
        }

        // A malformed later teacher must be rejected before any earlier teacher is staged.
        val protectedUid = "protected-${UUID.randomUUID()}"
        val malformedUid = "malformed-${UUID.randomUUID()}"
        val protectedRoot = firestore.collection("teacher_data").document(protectedUid)
        protectedRoot.set(
            mapOf(
                "ownerUid" to protectedUid,
                "schemaVersion" to 3L,
                "activeGeneration" to "original-generation",
                "recordCount" to 1L,
                "folders" to emptyList<String>()
            )
        ).awaitTask()
        protectedRoot.collection("generations").document("original-generation")
            .set(mapOf("generationId" to "original-generation")).awaitTask()
        protectedRoot.collection("generations").document("original-generation")
            .collection("records").document("class_existing")
            .set(mapOf("ownerUid" to protectedUid, "recordType" to "class", "id" to 303L, "name" to "نسخهٔ سالم قبلی"))
            .awaitTask()

        val malformedBackup = aggregateBackup(protectedUid, malformedUid)
        malformedBackup.getJSONArray("teachers").getJSONObject(1)
            .getJSONArray("records").getJSONObject(0).getJSONObject("data")
            .put("ownerUid", "forged-owner")
        var rejected = false
        println("RESTORE_TEST: starting malformed restore protection check")
        try {
            AdminCloudBackupRestorer(firestore).restoreAll(malformedBackup.toString())
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Malformed aggregate backup should be rejected", rejected)
        println("RESTORE_TEST: malformed backup rejected")

        val protectedAfter = protectedRoot.get().awaitTask()
        assertEquals("original-generation", protectedAfter.getString("activeGeneration"))
        assertEquals(1L, protectedAfter.getLong("recordCount"))
        val oldRecord = protectedRoot.collection("generations").document("original-generation")
            .collection("records").document("class_existing").get().awaitTask()
        assertTrue(oldRecord.exists())
        assertEquals("نسخهٔ سالم قبلی", oldRecord.getString("name"))
        assertEquals(1, protectedRoot.collection("generations").get().awaitTask().size())

        app.delete()
        } catch (failure: Throwable) {
            failure.printStackTrace()
            throw failure
        }
    }

    @Test
    fun failedRoomRestore_rollsBackDeletesAndPreservesExistingLocalData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = "room-rollback-${UUID.randomUUID()}"
        val app = FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId("demo-darsyar-rules")
                .setApplicationId("1:123456789:android:room-rollback") 
                .setApiKey("fake-emulator-api-key")
                .build(),
            appName
        ) ?: error("Firebase test app could not initialize")
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val uid = "room-rollback-${UUID.randomUUID()}"
            val classDao = database.classDao()
            val courseDao = database.courseDao()
            val lectureDao = database.lectureDao()
            val folderDao = database.teacherFolderDao()

            folderDao.insert(TeacherFolderEntity(id = 900, ownerUid = uid, name = "پوشهٔ قبلی"))
            classDao.insert(ClassEntity(id = 900, name = "کلاس محلی سالم", ownerUid = uid, folderId = 900))
            courseDao.insert(CourseEntity(id = 901, classId = 900, name = "درس محلی سالم"))
            lectureDao.insertLecture(
                LectureEntity(id = 902, title = "جلسهٔ محلی سالم", courseName = "درس محلی سالم",
                    professorName = "استاد", classId = 900, courseId = 901,
                    transcript = "متن محلی که نباید حذف شود")
            )

            val repository = FirestoreSyncRepository(
                lectureDao, classDao, courseDao, folderDao,
                FirebaseFirestore.getInstance(app), database
            )
            var failed = false
            try {
                repository.validateAndReplaceLocalData(
                    classes = listOf(ClassEntity(id = 101, name = "کلاس آزمایشی", ownerUid = uid, folderId = 11)),
                    courses = listOf(CourseEntity(id = 301, classId = 101, name = "درس آزمایشی")),
                    lectures = listOf(LectureEntity(id = 501, title = "جلسهٔ آزمایشی",
                        courseName = "درس آزمایشی", professorName = "استاد", classId = 101, courseId = 301)),
                    folders = listOf(
                        TeacherFolderEntity(id = 11, ownerUid = uid, name = "تکراری", parentFolderId = null),
                        TeacherFolderEntity(id = 12, ownerUid = uid, name = "تکراری", parentFolderId = null)
                    ),
                    ownerUid = uid
                )
            } catch (_: Exception) {
                failed = true
            }
            assertTrue("Duplicate folder names should make the Room transaction fail", failed)

            assertEquals("کلاس محلی سالم", classDao.getById(900)?.name)
            assertEquals("درس محلی سالم", courseDao.getById(901)?.name)
            assertEquals("متن محلی که نباید حذف شود", lectureDao.getLectureById(902)?.transcript)
            assertEquals("پوشهٔ قبلی", folderDao.getOwnedFolder(900, uid)?.name)
            assertEquals(null, classDao.getById(101))
        } finally {
            database.close()
            app.delete()
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        val directExecutor = Executor { command -> command.run() }
        addOnSuccessListener(directExecutor) { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener(directExecutor) { if (continuation.isActive) continuation.resumeWithException(it) }
            .addOnCanceledListener(directExecutor) { if (continuation.isActive) continuation.cancel() }
    }

    private fun aggregateBackup(uidA: String, uidB: String): JSONObject {
        fun teacher(
            uid: String,
            classId: Long,
            courseId: Long,
            lectureId: Long,
            folderId: Long,
            classTitle: String,
            lectureTitle: String
        ): JSONObject {
            val textFields = listOf("transcript", "originalTranscript", "aiSummary", "aiKeyPoints", "aiQuizJson", "tags")
            val manifest = JSONObject().apply { textFields.forEach { put(it, 2) } }
            val lecture = JSONObject()
                .put("recordType", "lecture").put("ownerUid", uid)
                .put("id", lectureId).put("classId", classId).put("courseId", courseId)
                .put("title", lectureTitle)
                .put("transcript", "").put("originalTranscript", "")
                .put("aiSummary", JSONObject.NULL).put("aiKeyPoints", JSONObject.NULL)
                .put("aiQuizJson", JSONObject.NULL).put("tags", "")
                .put("textChunkCounts", manifest)
            val records = JSONArray()
                .put(JSONObject().put("documentId", "class_$classId")
                    .put("data", JSONObject().put("recordType", "class").put("ownerUid", uid)
                        .put("id", classId).put("name", classTitle).put("folderId", folderId)))
                .put(JSONObject().put("documentId", "course_$courseId")
                    .put("data", JSONObject().put("recordType", "course").put("ownerUid", uid)
                        .put("id", courseId).put("classId", classId).put("title", "درس $classTitle")))
                .put(JSONObject().put("documentId", "lecture_$lectureId").put("data", lecture))
            textFields.forEach { field ->
                listOf("متن $field جلسه $lectureId؛ بخش نخست ", "و بخش دوم.").forEachIndexed { index, value ->
                    records.put(JSONObject().put("documentId", "lecture_text_${lectureId}_${field}_$index")
                        .put("data", JSONObject().put("recordType", "lecture_text_chunk")
                            .put("ownerUid", uid).put("lectureId", lectureId)
                            .put("field", field).put("index", index).put("value", value)))
                }
            }
            return JSONObject()
                .put("ownerUid", uid)
                .put("schemaVersion", 3)
                .put(
                    "root",
                    JSONObject()
                        .put("ownerUid", uid)
                        .put("activeGeneration", "source-generation")
                        .put("recordCount", 15)
                        .put(
                            "folders",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", folderId)
                                    .put("ownerUid", uid)
                                    .put("name", "پوشهٔ $classTitle")
                                    .put("parentFolderId", JSONObject.NULL)
                            )
                        )
                )
                .put("records", records)
        }
        return JSONObject()
            .put("format", "darsyar-admin-cloud-backup")
            .put("version", 1)
            .put("teacherCount", 2)
            .put("recordCount", 30)
            .put(
                "teachers",
                JSONArray()
                    .put(teacher(uidA, 101, 301, 501, 11, "کلاس استاد الف", "جلسه استاد الف"))
                    .put(teacher(uidB, 202, 402, 602, 22, "کلاس استاد ب", "جلسه استاد ب"))
            )
    }
}
