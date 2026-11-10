package com.example.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
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
        val auth = FirebaseAuth.getInstance(app)
        auth.useEmulator("127.0.0.1", 9099)
        auth.createUserWithEmailAndPassword("salmanasadi843@gmail.com", "test-password-123").awaitTask()
        // Rules grant aggregate backup access to the primary admin email.
        val firestore = FirebaseFirestore.getInstance(app)
        firestore.useEmulator("127.0.0.1", 8080)

        val uidA = "integration-A-${UUID.randomUUID()}"
        val uidB = "integration-B-${UUID.randomUUID()}"
        val backup = aggregateBackup(uidA, uidB)

        val report = AdminCloudBackupRestorer(firestore).restoreAll(backup.toString())
        assertEquals(2, report.teachersRestored)
        assertEquals(4, report.recordsRestored)

        val exported = AdminCloudBackupExporter(firestore).exportAllTeachers()
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
