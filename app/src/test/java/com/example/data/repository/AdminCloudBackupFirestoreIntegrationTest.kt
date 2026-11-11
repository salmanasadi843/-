package com.example.data.repository

import android.content.Context
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
        assertEquals(4, report.recordsRestored)

        println("RESTORE_TEST: starting export after restore")
        val exported = AdminCloudBackupExporter(firestore).exportAllTeachers()
        println("RESTORE_TEST: export after restore finished")
        assertEquals(2, exported.getInt("teacherCount"))
        assertEquals(4, exported.getInt("recordCount"))
        val teachers = exported.getJSONArray("teachers")
        val byOwner = (0 until teachers.length()).associate { i ->
            val teacher = teachers.getJSONObject(i)
            teacher.getString("ownerUid") to teacher
        }
        assertTrue(uidA in byOwner)
        assertTrue(uidB in byOwner)
        for (uid in listOf(uidA, uidB)) {
            val records = byOwner.getValue(uid).getJSONArray("records")
            assertEquals(2, records.length())
            val rows = (0 until records.length()).map { records.getJSONObject(it) }
            assertTrue(rows.all { it.getJSONObject("data").getString("ownerUid") == uid })
            assertTrue(rows.any { it.getString("documentId") == "class_${if (uid == uidA) 101 else 202}" })
            assertTrue(rows.any { it.getString("documentId") == "lecture_${if (uid == uidA) 501 else 602}" })
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

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        val directExecutor = Executor { command -> command.run() }
        addOnSuccessListener(directExecutor) { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener(directExecutor) { if (continuation.isActive) continuation.resumeWithException(it) }
            .addOnCanceledListener(directExecutor) { if (continuation.isActive) continuation.cancel() }
    }

    private fun aggregateBackup(uidA: String, uidB: String): JSONObject {
        fun teacher(uid: String, classId: Long, lectureId: Long, classTitle: String, lectureTitle: String) =
            JSONObject()
                .put("ownerUid", uid)
                .put("schemaVersion", 3)
                .put("root", JSONObject().put("ownerUid", uid).put("activeGeneration", "source-generation")
                    .put("recordCount", 2).put("folders", JSONArray()))
                .put("records", JSONArray()
                    .put(JSONObject().put("documentId", "class_$classId")
                        .put("data", JSONObject().put("recordType", "class").put("ownerUid", uid)
                            .put("id", classId).put("name", classTitle)))
                    .put(JSONObject().put("documentId", "lecture_$lectureId")
                        .put("data", JSONObject().put("recordType", "lecture").put("ownerUid", uid)
                            .put("id", lectureId).put("classId", classId).put("title", lectureTitle)
                            .put("transcript", "متن آزمون $lectureTitle"))))
        return JSONObject().put("format", "darsyar-admin-cloud-backup").put("version", 1)
            .put("teacherCount", 2).put("recordCount", 4)
            .put("teachers", JSONArray()
                .put(teacher(uidA, 101, 501, "کلاس استاد الف", "جلسه استاد الف"))
                .put(teacher(uidB, 202, 602, "کلاس استاد ب", "جلسه استاد ب")))
    }
}
