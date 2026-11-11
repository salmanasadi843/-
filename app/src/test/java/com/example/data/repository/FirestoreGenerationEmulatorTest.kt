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
import kotlinx.coroutines.runBlocking
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
        firestore.useEmulator(parts[0], parts[1].toInt())
        firestore.firestoreSettings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(false)
            .build()

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
    fun successfulUploadActivatesAllRecordsAfterMultipleBatches() = runBlocking {
        insertClasses(401)
        val repository = repository()

        repository.uploadSnapshot(uid)

        val root = Tasks.await(firestore.collection("teacher_data").document(uid).get())
        val active = requireNotNull(root.getString("activeGeneration"))
        assertEquals(401L, root.getLong("recordCount"))
        val records = Tasks.await(
            firestore.collection("teacher_data").document(uid).collection("records")
                .whereEqualTo("generation", active).get()
        )
        assertEquals(401, records.size())
        assertTrue(records.documents.all { it.getString("generation") == active })
    }

    @Test
    fun failedSecondBatchLeavesPreviousGenerationActive() = runBlocking {
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
            repository().uploadSnapshot(uid)
        } catch (_: Exception) {
            failed = true
        }

        assertTrue("The deliberately rejected second batch must fail", failed)
        val after = Tasks.await(root.get())
        assertEquals(previousGeneration, after.getString("activeGeneration"))
        val previous = Tasks.await(records.whereEqualTo("generation", previousGeneration).get())
        assertEquals(1, previous.size())
        val staged = Tasks.await(records.get())
        assertTrue("A failed generation must not become active", staged.documents
            .filter { it.getString("generation") != previousGeneration }
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
