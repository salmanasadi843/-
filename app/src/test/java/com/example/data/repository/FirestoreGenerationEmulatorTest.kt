package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ClassEntity
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirestoreGenerationEmulatorTest {
    private lateinit var database: AppDatabase
    private lateinit var firestore: FirebaseFirestore
    private lateinit var app: FirebaseApp
    private lateinit var uid: String

    @Before
    fun setUp() {
        val emulatorHost = System.getenv("FIRESTORE_EMULATOR_HOST")
        assumeTrue("Firestore Emulator is required for this integration test", !emulatorHost.isNullOrBlank())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val parts = requireNotNull(emulatorHost).split(":")
        require(parts.size == 2) { "FIRESTORE_EMULATOR_HOST must be host:port" }

        app = FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId("demo-darsyar-generation-tests")
                .setApplicationId("1:123456789:android:generation-tests")
                .setApiKey("fake-emulator-api-key")
                .build(),
            "generation-test-${UUID.randomUUID()}"
        ) ?: error("Firebase test app could not initialize")

        firestore = FirebaseFirestore.getInstance(app)
        firestore.firestoreSettings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(false)
            .build()
        firestore.useEmulator(parts[0], parts[1].toInt())

        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        uid = "generation-${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::app.isInitialized) app.delete()
    }

    @Test
    fun successfulUploadActivatesAllRecordsAfterMultipleBatches() = runBlocking(Dispatchers.IO) {
        insertClasses(401)
        val rootRef = firestore.collection("teacher_data").document(uid)
        Tasks.await(rootRef.set(mapOf(
            "ownerUid" to uid,
            "externalMetadata" to "must-survive",
            "classes" to listOf(mapOf("legacy" to true)),
            "activeGeneration" to "older-generation"
        )))
        val recordsRef = rootRef.collection("records")
        Tasks.await(recordsRef.document("older-generation__class_9000").set(mapOf(
            "recordType" to "class",
            "id" to 9000L,
            "name" to "نسخه قدیمی",
            "ownerUid" to uid,
            "generation" to "older-generation"
        )))
        val repository = repository()

        withTimeout(90_000) { repository.uploadSnapshot(uid) }

        val root = Tasks.await(rootRef.get())
        val active = requireNotNull(root.getString("activeGeneration"))
        assertEquals(401L, root.getLong("recordCount"))
        assertEquals("must-survive", root.getString("externalMetadata"))
        assertEquals(null, root.get("classes"))
        val records = Tasks.await(
            firestore.collection("teacher_data").document(uid).collection("records")
                .whereEqualTo("generation", active).get()
        )
        assertEquals(401, records.size())
        assertTrue(records.documents.all { it.getString("generation") == active })
        assertEquals(1, Tasks.await(recordsRef.whereEqualTo("generation", "older-generation").get()).size())

        database.classDao().clearForRestore()
        withTimeout(90_000) { repository.restoreFromCloud(uid) }
        assertEquals(401, database.classDao().getAllForBackup().size)
        assertEquals(null, database.classDao().getById(9000L))
    }


    @Test
    fun divergentLocalDataIsKeptUntilExplicitCloudReplacementIsApproved() = runBlocking(Dispatchers.IO) {
        val local = ClassEntity(id = 700, name = "نسخه محلی", ownerUid = uid)
        database.classDao().insert(local)
        val generation = "conflict-generation"
        val root = firestore.collection("teacher_data").document(uid)
        Tasks.await(root.set(mapOf(
            "schemaVersion" to 2L,
            "ownerUid" to uid,
            "activeGeneration" to generation,
            "recordCount" to 1L,
            "folders" to emptyList<Map<String, Any>>()
        )))
        Tasks.await(root.collection("records").document("${generation}__class_700").set(mapOf(
            "recordType" to "class",
            "id" to 700L,
            "name" to "نسخه ابری متفاوت",
            "ownerUid" to uid,
            "generation" to generation
        )))

        var blocked = false
        try {
            repository().restoreFromCloud(uid)
        } catch (_: IllegalStateException) {
            blocked = true
        }
        assertTrue("Divergent local data must not be silently replaced", blocked)
        assertEquals("نسخه محلی", database.classDao().getById(700)?.name)

        repository().restoreFromCloud(uid, allowLocalReplace = true)
        assertEquals("نسخه ابری متفاوت", database.classDao().getById(700)?.name)
    }

    @Test
    fun identicalCloudRestorePreservesDeviceLocalAudioPath() = runBlocking(Dispatchers.IO) {
        val localClass = ClassEntity(
            id = 710, name = "کلاس یکسان", ownerUid = uid,
            classDateMillis = 10L, createdAtMillis = 11L
        )
        val localCourse = com.example.data.local.CourseEntity(
            id = 720, classId = 710, name = "درس یکسان", createdAtMillis = 12L
        )
        val localLecture = com.example.data.local.LectureEntity(
            id = 730, title = "جلسه یکسان", courseName = "درس یکسان", professorName = "استاد",
            classId = 710, courseId = 720, dateMillis = 13L,
            audioFilePath = "/device/audio/session.mp3", transcript = "متن",
            originalTranscript = "متن خام", lastEditedMillis = 14L
        )
        database.classDao().insert(localClass)
        database.courseDao().insert(localCourse)
        database.lectureDao().insertLecture(localLecture)

        val generation = "identical-generation"
        val root = firestore.collection("teacher_data").document(uid)
        Tasks.await(root.set(mapOf(
            "schemaVersion" to 2L,
            "ownerUid" to uid,
            "activeGeneration" to generation,
            "recordCount" to 3L,
            "folders" to emptyList<Map<String, Any>>()
        )))
        val records = root.collection("records")
        Tasks.await(records.document("${generation}__class_710").set(mapOf(
            "recordType" to "class", "id" to 710L, "name" to "کلاس یکسان",
            "ownerUid" to uid, "classDateMillis" to 10L, "createdAtMillis" to 11L,
            "generation" to generation
        )))
        Tasks.await(records.document("${generation}__course_720").set(mapOf(
            "recordType" to "course", "id" to 720L, "classId" to 710L,
            "name" to "درس یکسان", "createdAtMillis" to 12L,
            "generation" to generation
        )))
        Tasks.await(records.document("${generation}__lecture_730").set(mapOf(
            "recordType" to "lecture", "id" to 730L, "title" to "جلسه یکسان",
            "courseName" to "درس یکسان", "professorName" to "استاد",
            "classId" to 710L, "courseId" to 720L, "dateMillis" to 13L,
            "transcript" to "متن", "originalTranscript" to "متن خام",
            "lastEditedMillis" to 14L, "generation" to generation
        )))

        repository().restoreFromCloud(uid)
        assertEquals(
            "/device/audio/session.mp3",
            database.lectureDao().getLectureById(730)?.audioFilePath
        )
    }

    @Test
    fun failedSecondBatchLeavesPreviousGenerationActive() = runBlocking(Dispatchers.IO) {
        insertClasses(401, failLast = true)
        val root = firestore.collection("teacher_data").document(uid)
        val records = root.collection("records")
        val previousGeneration = "previous-good-generation"
        Tasks.await(root.set(mapOf(
            "schemaVersion" to 2L,
            "ownerUid" to uid,
            "activeGeneration" to previousGeneration,
            "recordCount" to 1L,
            "folders" to emptyList<Map<String, Any>>()
        )))
        Tasks.await(records.document("${previousGeneration}__class_9000").set(mapOf(
            "recordType" to "class",
            "id" to 9000L,
            "name" to "نسخه سالم قبلی",
            "ownerUid" to uid,
            "generation" to previousGeneration
        )))

        var failed = false
        try {
            withTimeout(90_000) { repository().uploadSnapshot(uid) }
        } catch (_: Exception) {
            failed = true
        }

        assertTrue("The deliberately rejected second batch must fail", failed)
        val after = Tasks.await(root.get())
        assertEquals(previousGeneration, after.getString("activeGeneration"))
        val previous = Tasks.await(records.whereEqualTo("generation", previousGeneration).get())
        assertEquals(1, previous.size())
        val staged = Tasks.await(records.get()).documents
            .filter { it.getString("generation") != previousGeneration }
        assertEquals("The first 400-record batch should remain staged but inactive", 400, staged.size)
        assertTrue(staged.mapNotNull { it.getString("generation") }.distinct().size == 1)
        assertTrue("A failed generation must not become active", staged
            .all { it.getString("generation") != after.getString("activeGeneration") })
    }

    private suspend fun insertClasses(count: Int, failLast: Boolean = false) {
        repeat(count) { index ->
            val name = if (failLast && index == count - 1) "FORCE_FAILURE" else "Class $index"
            database.classDao().insert(
                ClassEntity(
                    id = index + 1L,
                    name = name,
                    ownerUid = uid,
                    createdAtMillis = index.toLong()
                )
            )
        }
    }

    private fun repository() = FirestoreSyncRepository(
        lectureDao = database.lectureDao(),
        classDao = database.classDao(),
        courseDao = database.courseDao(),
        teacherFolderDao = database.teacherFolderDao(),
        firestore = firestore,
        database = database
    )
}
