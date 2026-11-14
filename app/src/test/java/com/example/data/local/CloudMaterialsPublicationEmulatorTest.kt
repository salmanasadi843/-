package com.example.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudMaterialsPublicationEmulatorTest {
    @Test(timeout = 180_000)
    fun publishesAggregatePayloadOverOneMiBAsSeparateRecordsAndPreservesOldGenerationOnOversize() = runBlocking(Dispatchers.IO) {
        println("PUBTEST checkpoint: start")
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Production code in AuthPreferences and CloudMaterialsSync uses the DEFAULT
        // Firebase app. Never select the first app: other tests create named apps and
        // their iteration order is not a valid way to obtain the default instance.
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == "[DEFAULT]" }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId("demo-darsyar-generation-tests")
                    .setApplicationId("1:123456789:android:publication-tests")
                    .setApiKey("fake-emulator-api-key")
                    .build()
            ) ?: error("Default Firebase test app could not initialize")

        val emulatorHost = requireNotNull(System.getenv("FIRESTORE_EMULATOR_HOST")) {
            "Firestore Emulator is required for this integration test"
        }
        val parts = emulatorHost.split(":")
        require(parts.size == 2) { "FIRESTORE_EMULATOR_HOST must be host:port" }

        val firestore = FirebaseFirestore.getInstance(app)
        firestore.firestoreSettings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(false)
            .build()
        firestore.useEmulator(parts[0], parts[1].toInt())

        val uid = "publication-test-${UUID.randomUUID()}"
        println("PUBTEST checkpoint: emulator configured; using isolated test UID")
        Tasks.await(firestore.collection("users").document(uid).set(mapOf(
            "uid" to uid, "name" to "استاد آزمایشی", "role" to "TEACHER"
        )), 30, TimeUnit.SECONDS)
        Tasks.await(firestore.collection("approved_teachers").document(uid).set(mapOf(
            "ownerUid" to uid, "enabled" to true, "approved" to true
        )), 30, TimeUnit.SECONDS)

        println("PUBTEST checkpoint: preparing Room")

        val db = AppDatabase.getDatabase(context)
        db.lectureDao().clearForRestore()
        db.courseDao().clearForRestore()
        db.classDao().clearForRestore()

        db.classDao().insert(ClassEntity(
            id = 101L,
            name = "کلاس انتشار حجیم",
            teacherName = "استاد آزمایشی",
            isPublished = true,
            ownerUid = uid
        ))
        db.courseDao().insert(CourseEntity(
            id = 201L,
            classId = 101L,
            name = "متن و صوت",
            teacherName = "استاد آزمایشی"
        ))
        val largeText = "a".repeat(600_000)
        db.lectureDao().insertLecture(LectureEntity(
            id = 301L,
            title = "جلسه اول",
            courseName = "متن و صوت",
            professorName = "استاد آزمایشی",
            classId = 101L,
            courseId = 201L,
            transcript = largeText,
            originalTranscript = largeText,
            isPublished = true
        ))
        db.lectureDao().insertLecture(LectureEntity(
            id = 302L,
            title = "جلسه دوم",
            courseName = "متن و صوت",
            professorName = "استاد آزمایشی",
            classId = 101L,
            courseId = 201L,
            transcript = largeText,
            originalTranscript = largeText,
            isPublished = true
        ))

        println("PUBTEST checkpoint: Room seeded; first CloudMaterialsSync.sync")
        val message = CloudMaterialsSync.syncAsTeacherForEmulatorTest(context, uid)
        assertTrue(message.contains("2 جلسه"))
        val root = firestore.collection("shared_materials").document(uid)
        val publishedRoot = Tasks.await(root.get(), 30, TimeUnit.SECONDS)
        val activeGeneration = requireNotNull(publishedRoot.getString("activeGeneration"))
        assertEquals(4L, publishedRoot.getLong("recordCount"))
        val activeRecords = Tasks.await(
            root.collection("records").whereEqualTo("generation", activeGeneration).get(), 30, TimeUnit.SECONDS
        )
        assertEquals(4, activeRecords.size())
        val payloadBytes = activeRecords.documents.sumOf {
            requireNotNull(it.getString("dataJson")).toByteArray(Charsets.UTF_8).size
        }
        assertTrue("Aggregate publication should exceed the former one-document limit", payloadBytes > 1_048_576)
        assertTrue(activeRecords.documents.all {
            requireNotNull(it.getString("dataJson")).toByteArray(Charsets.UTF_8).size < 900_000
        })

        println("PUBTEST checkpoint: first generation validated; inserting oversized record")
        db.lectureDao().insertLecture(LectureEntity(
            id = 303L,
            title = "جلسه بیش از حد بزرگ",
            courseName = "متن و صوت",
            professorName = "استاد آزمایشی",
            classId = 101L,
            courseId = 201L,
            transcript = "b".repeat(910_000),
            originalTranscript = "b".repeat(910_000),
            isPublished = true
        ))

        var oversizedRejected = false
        try {
            CloudMaterialsSync.syncAsTeacherForEmulatorTest(context, uid)
        } catch (_: IllegalArgumentException) {
            oversizedRejected = true
        }
        assertTrue("An individually oversized record must stop publication", oversizedRejected)
        val afterFailure = Tasks.await(root.get())
        assertEquals(activeGeneration, afterFailure.getString("activeGeneration"))
        assertEquals(4L, afterFailure.getLong("recordCount"))
        assertNotEquals("The active generation must not be replaced after a rejected upload",
            "invalid-generation", afterFailure.getString("activeGeneration"))

        println("PUBTEST checkpoint: oversized generation preserved; test finishing")
    }
}
