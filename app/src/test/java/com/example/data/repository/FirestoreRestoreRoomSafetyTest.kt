package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ClassEntity
import com.example.data.local.CourseEntity
import com.example.data.local.LectureEntity
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirestoreRestoreRoomSafetyTest {
    @Test
    fun emptyCloudBackupIsRejectedWithoutDeletingExistingRoomData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId("demo-darsyar-restore-safety")
                .setApplicationId("1:123456789:android:restore-safety")
                .setApiKey("fake-emulator-api-key")
                .build(),
            "restore-safety-${UUID.randomUUID()}"
        ) ?: error("Firebase test app could not initialize")

        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val uid = "room-safety-${UUID.randomUUID()}"
            val classDao = database.classDao()
            val courseDao = database.courseDao()
            val lectureDao = database.lectureDao()

            classDao.insert(
                ClassEntity(id = 900, name = "کلاس محلی سالم", ownerUid = uid)
            )
            courseDao.insert(
                CourseEntity(id = 901, classId = 900, name = "درس محلی سالم")
            )
            lectureDao.insertLecture(
                LectureEntity(
                    id = 902,
                    title = "جلسه محلی سالم",
                    courseName = "درس محلی سالم",
                    professorName = "استاد",
                    classId = 900,
                    courseId = 901,
                    transcript = "این متن نباید حذف شود"
                )
            )

            val repository = FirestoreSyncRepository(
                lectureDao = lectureDao,
                classDao = classDao,
                courseDao = courseDao,
                firestore = FirebaseFirestore.getInstance(app)
            )

            var rejected = false
            try {
                repository.validateAndReplaceLocalData(
                    classes = emptyList(),
                    courses = emptyList(),
                    lectures = emptyList()
                )
            } catch (_: IllegalStateException) {
                rejected = true
            }

            assertEquals("Empty cloud backup must be rejected", true, rejected)
            assertEquals("کلاس محلی سالم", classDao.getById(900)?.name)
            assertEquals("درس محلی سالم", courseDao.getById(901)?.name)
            assertEquals("این متن نباید حذف شود", lectureDao.getLectureById(902)?.transcript)
            assertNotNull(classDao.getById(900))
        } finally {
            database.close()
            app.delete()
        }
    }
}
