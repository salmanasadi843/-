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
}
