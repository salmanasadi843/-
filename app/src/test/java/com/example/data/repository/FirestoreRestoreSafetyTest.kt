package com.example.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreRestoreSafetyTest {
    @Test
    fun populatedLocalDataBlocksAnExistingCloudRestoreButNotAnInitialUpload() {
        assertTrue(shouldBlockLegacyRestore(localRecordCount = 1, legacyCloudBackupExists = true))
        assertTrue(shouldBlockLegacyRestore(localRecordCount = 12, cloudBackupExists = true))
        assertFalse(shouldBlockLegacyRestore(localRecordCount = 0, cloudBackupExists = true))
        assertFalse(shouldBlockLegacyRestore(localRecordCount = 12, legacyCloudBackupExists = false))
        assertFalse(shouldBlockLegacyRestore(localRecordCount = 0, cloudBackupExists = false))
    }

    @Test
    fun identicalTeacherSnapshotsAreSafeToRestoreAndDivergentSnapshotsAreBlocked() {
        val localClass = com.example.data.local.ClassEntity(id = 1, name = "کلاس", ownerUid = "teacher")
        val localCourse = com.example.data.local.CourseEntity(id = 2, classId = 1, name = "درس")
        val localLecture = com.example.data.local.LectureEntity(
            id = 3, title = "جلسه", courseName = "درس", professorName = "استاد",
            classId = 1, courseId = 2, transcript = "متن", audioFilePath = "/local/audio.mp3"
        )
        val localFolder = com.example.data.local.TeacherFolderEntity(
            id = 4, ownerUid = "teacher", name = "پوشه"
        )
        assertTrue(
            areTeacherSnapshotsEquivalent(
                listOf(localClass), listOf(localCourse), listOf(localLecture), listOf(localFolder),
                listOf(localClass), listOf(localCourse),
                listOf(localLecture.copy(audioFilePath = null)), listOf(localFolder)
            )
        )
        assertFalse(
            areTeacherSnapshotsEquivalent(
                listOf(localClass), listOf(localCourse), listOf(localLecture), listOf(localFolder),
                listOf(localClass.copy(name = "کلاس متفاوت")), listOf(localCourse),
                listOf(localLecture), listOf(localFolder)
            )
        )
    }

    @Test
    fun emptyCloudBackupIsRejectedOnlyWhenAllRecordTypesAreEmpty() {
        assertTrue(isEmptyCloudBackup(0, 0, 0))
        assertFalse(isEmptyCloudBackup(1, 0, 0))
        assertFalse(isEmptyCloudBackup(0, 1, 0))
        assertFalse(isEmptyCloudBackup(0, 0, 1))
    }

    @Test
    fun mismatchedRecordCountIsRejectedButLegacyMissingCountIsAllowed() {
        assertTrue(isRecordCountMismatch(3L, 2))
        assertTrue(isRecordCountMismatch(0L, 1))
        assertFalse(isRecordCountMismatch(3L, 3))
        assertFalse(isRecordCountMismatch(null, 0))
    }

    @Test
    fun restoreSelectsOnlyTheActiveBackupGeneration() {
        // Before generation-based uploads, schema-2 records had no generation field.
        assertTrue(isRecordInGeneration(null, null))
        assertTrue(isRecordInGeneration("", null))
        assertFalse(isRecordInGeneration("new-generation", null))

        assertTrue(isRecordInGeneration("active-generation", "active-generation"))
        assertFalse(isRecordInGeneration("old-generation", "active-generation"))
        assertFalse(isRecordInGeneration(null, "active-generation"))
    }

    @Test
    fun missingOrMalformedFolderBackupIsRejected() {
        assertFalse(isValidFolderBackup(null))
        assertFalse(isValidFolderBackup("not-a-list"))
        assertFalse(isValidFolderBackup(listOf("bad-row")))
        assertFalse(isValidFolderBackup(listOf(mapOf("id" to 1L, "ownerUid" to "", "name" to "Folder"))))
        assertTrue(
            isValidFolderBackup(
                listOf(mapOf("id" to 1L, "ownerUid" to "teacher-1", "name" to "Folder"))
            )
        )
        assertTrue(isValidFolderBackup(emptyList<Any>()))
    }
}
