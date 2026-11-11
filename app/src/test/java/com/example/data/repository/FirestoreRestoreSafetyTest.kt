package com.example.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreRestoreSafetyTest {
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
