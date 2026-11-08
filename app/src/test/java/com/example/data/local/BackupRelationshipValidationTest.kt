package com.example.data.local

import org.junit.Assert.fail
import org.junit.Test

class BackupRelationshipValidationTest {

    @Test
    fun validBackupRelationshipsAreAccepted() {
        val folders = listOf(
            TeacherFolderEntity(id = 10, ownerUid = "teacher-a", name = "اصول"),
            TeacherFolderEntity(id = 11, ownerUid = "teacher-a", name = "جلسات", parentFolderId = 10)
        )
        val classes = listOf(
            ClassEntity(id = 20, name = "خارج اصول", ownerUid = "teacher-a", folderId = 11)
        )
        val courses = listOf(
            CourseEntity(id = 30, classId = 20, name = "مباحث الفاظ")
        )
        val lectures = listOf(
            LectureEntity(id = 40, title = "جلسه اول", courseName = "مباحث الفاظ",
                professorName = "استاد", classId = 20, courseId = 30)
        )

        validateRestoreRelations(lectures, classes, courses, folders)
    }

    @Test
    fun duplicatePrimaryIdsAreRejected() {
        val classes = listOf(
            ClassEntity(id = 20, name = "کلاس اول"),
            ClassEntity(id = 20, name = "کلاس دوم")
        )

        expectInvalid {
            validateRestoreRelations(emptyList(), classes, emptyList(), emptyList())
        }
    }

    @Test
    fun coursePointingToMissingClassIsRejected() {
        val courses = listOf(CourseEntity(id = 30, classId = 999, name = "درس"))

        expectInvalid {
            validateRestoreRelations(emptyList(), emptyList(), courses, emptyList())
        }
    }

    @Test
    fun lectureCourseAndClassMismatchIsRejected() {
        val classes = listOf(
            ClassEntity(id = 20, name = "کلاس اول"),
            ClassEntity(id = 21, name = "کلاس دوم")
        )
        val courses = listOf(CourseEntity(id = 30, classId = 20, name = "درس"))
        val lectures = listOf(
            LectureEntity(id = 40, title = "جلسه", courseName = "درس",
                professorName = "استاد", classId = 21, courseId = 30)
        )

        expectInvalid {
            validateRestoreRelations(lectures, classes, courses, emptyList())
        }
    }

    @Test
    fun folderCycleIsRejected() {
        val folders = listOf(
            TeacherFolderEntity(id = 10, ownerUid = "teacher-a", name = "الف", parentFolderId = 11),
            TeacherFolderEntity(id = 11, ownerUid = "teacher-a", name = "ب", parentFolderId = 10)
        )

        expectInvalid {
            validateRestoreRelations(emptyList(), emptyList(), emptyList(), folders)
        }
    }

    @Test
    fun classCannotPointToAnotherTeachersFolder() {
        val folders = listOf(
            TeacherFolderEntity(id = 10, ownerUid = "teacher-a", name = "پوشه")
        )
        val classes = listOf(
            ClassEntity(id = 20, name = "کلاس", ownerUid = "teacher-b", folderId = 10)
        )

        expectInvalid {
            validateRestoreRelations(emptyList(), classes, emptyList(), folders)
        }
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid backup relationships to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: malformed relationships must be rejected before database mutation.
        }
    }
}
