package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ClassEntity
import com.example.data.local.CourseEntity
import com.example.data.local.LectureEntity
import com.example.data.local.TeacherFolderEntity
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
                firestore = FirebaseFirestore.getInstance(app),
                database = database
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
    @Test
    fun failedRestoreRollsBackAllLocalTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId("demo-darsyar-restore-rollback")
                .setApplicationId("1:123456789:android:restore-rollback")
                .setApiKey("fake-emulator-api-key")
                .build(),
            "restore-rollback-${UUID.randomUUID()}"
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
                LectureEntity(
                    id = 902, title = "جلسهٔ محلی سالم", courseName = "درس محلی سالم",
                    professorName = "استاد", classId = 900, courseId = 901,
                    transcript = "متن محلی که نباید حذف شود"
                )
            )

            val repository = FirestoreSyncRepository(
                lectureDao = lectureDao,
                classDao = classDao,
                courseDao = courseDao,
                teacherFolderDao = folderDao,
                firestore = FirebaseFirestore.getInstance(app),
                database = database
            )

            // Fail after the transaction has begun clearing tables, then verify rollback.
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_restore_class_insert BEFORE INSERT ON classes " +
                    "WHEN NEW.id = 101 BEGIN SELECT RAISE(ABORT, 'forced restore failure'); END"
            )

            var failed = false
            try {
                repository.validateAndReplaceLocalData(
                    classes = listOf(ClassEntity(id = 101, name = "کلاس آزمایشی", ownerUid = uid, folderId = 11)),
                    courses = listOf(CourseEntity(id = 301, classId = 101, name = "درس آزمایشی")),
                    lectures = listOf(
                        LectureEntity(
                            id = 501, title = "جلسهٔ آزمایشی", courseName = "درس آزمایشی",
                            professorName = "استاد", classId = 101, courseId = 301
                        )
                    ),
                    folders = listOf(TeacherFolderEntity(id = 11, ownerUid = uid, name = "پوشهٔ جدید"))
                )
            } catch (_: Exception) {
                failed = true
            }

            assertEquals("Forced insert failure must abort restore", true, failed)
            assertEquals("کلاس محلی سالم", classDao.getById(900)?.name)
            assertEquals("درس محلی سالم", courseDao.getById(901)?.name)
            assertEquals("متن محلی که نباید حذف شود", lectureDao.getLectureById(902)?.transcript)
            assertEquals("پوشهٔ قبلی", folderDao.getOwnedFolder(900, uid)?.name)
            assertEquals(null, classDao.getById(101))
            assertEquals(null, folderDao.getOwnedFolder(11, uid))
        } finally {
            database.close()
            app.delete()
        }
    }

}
