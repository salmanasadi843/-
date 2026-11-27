package com.example.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DatabaseBackupIntegrityTest {
    private val validClass = ClassEntity(id = 10, name = "کلاس", ownerUid = "teacher-1")
    private val validCourse = CourseEntity(id = 20, classId = 10, name = "درس")
    private val validLecture = LectureEntity(
        id = 30,
        title = "جلسه",
        courseName = "درس",
        professorName = "استاد",
        classId = 10,
        courseId = 20,
        transcript = "متن ویرایش‌شده",
        originalTranscript = "متن اصلی"
    )

    @Test
    fun acceptsConsistentSnapshotAndNegativeSharedIds() {
        validateBackupIntegrity(
            classes = listOf(validClass, validClass.copy(id = -11, name = "کلاس منتشرشده")),
            courses = listOf(validCourse, validCourse.copy(id = -21, classId = -11)),
            lectures = listOf(validLecture, validLecture.copy(id = -31, classId = -11, courseId = -21)),
            folders = emptyList()
        )
    }

    @Test
    fun rejectsCompletelyEmptyBackup() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupIntegrity(emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    @Test
    fun rejectsDuplicateIdsAndMissingParents() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupIntegrity(
                classes = listOf(validClass, validClass),
                courses = emptyList(),
                lectures = emptyList(),
                folders = emptyList()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupIntegrity(
                classes = listOf(validClass),
                courses = listOf(validCourse.copy(classId = 999)),
                lectures = emptyList(),
                folders = emptyList()
            )
        }
    }

    @Test
    fun rejectsLectureCourseClassMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupIntegrity(
                classes = listOf(validClass, validClass.copy(id = 11)),
                courses = listOf(validCourse),
                lectures = listOf(validLecture.copy(classId = 11)),
                folders = emptyList()
            )
        }
    }

    @Test
    fun rejectsMissingFolderAndFolderCycles() {
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupIntegrity(
                classes = listOf(validClass.copy(folderId = 40)),
                courses = emptyList(),
                lectures = emptyList(),
                folders = emptyList()
            )
        }
        val first = TeacherFolderEntity(id = 40, ownerUid = "teacher-1", name = "اول", parentFolderId = 41)
        val second = TeacherFolderEntity(id = 41, ownerUid = "teacher-1", name = "دوم", parentFolderId = 40)
        assertThrows(IllegalArgumentException::class.java) {
            validateBackupIntegrity(
                classes = listOf(validClass.copy(folderId = 40)),
                courses = emptyList(),
                lectures = emptyList(),
                folders = listOf(first, second)
            )
        }
    }
    @Test
    fun preservesMatchingDeviceLocalAudioPathsWithoutInventingPaths() {
        val restored = listOf(
            validLecture.copy(audioFilePath = null),
            validLecture.copy(id = 31, audioFilePath = null),
            validLecture.copy(id = 32, audioFilePath = "/backup/already-present.mp3")
        )

        val result = preserveLocalAudioPaths(
            restored,
            mapOf(
                30L to "/device/audio/lecture-30.mp3",
                32L to "/device/audio/lecture-32.mp3"
            )
        )

        assertEquals("/device/audio/lecture-30.mp3", result[0].audioFilePath)
        assertEquals(null, result[1].audioFilePath)
        assertEquals("/backup/already-present.mp3", result[2].audioFilePath)
    }

}
