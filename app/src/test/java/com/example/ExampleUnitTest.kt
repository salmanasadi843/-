package com.example

import com.example.data.local.DatabaseBackup
import com.example.data.repository.isPrivateBackupLecture
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun backupWithAllCollectionsIncludingEmptyArraysIsAccepted() {
        val required = setOf("lectures", "classes", "courses", "folders")
        DatabaseBackup.validateBackupCollections(
            presentKeys = required,
            arrayKeys = required
        )
    }

    @Test
    fun backupMissingAnyCollectionIsRejected() {
        val required = setOf("lectures", "classes", "courses", "folders")
        required.forEach { missing ->
            val error = runCatching {
                DatabaseBackup.validateBackupCollections(
                    presentKeys = required - missing,
                    arrayKeys = required - missing
                )
            }.exceptionOrNull()
            assertNotNull("Missing $missing must reject the backup", error)
            assertTrue(error is IllegalArgumentException)
            assertTrue(error!!.message!!.contains(missing))
        }
    }

    @Test
    fun backupCollectionWithWrongJsonTypeIsRejected() {
        val required = setOf("lectures", "classes", "courses", "folders")
        val error = runCatching {
            DatabaseBackup.validateBackupCollections(
                presentKeys = required,
                arrayKeys = required - "folders"
            )
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("folders"))
    }

    @Test
    fun privateCloudBackupIncludesStandalonePersonalSessions() {
        val ownedClassIds = setOf(10L, 20L)
        assertTrue(isPrivateBackupLecture(1L, null, ownedClassIds))
        assertTrue(isPrivateBackupLecture(2L, 10L, ownedClassIds))
        assertFalse(isPrivateBackupLecture(3L, 99L, ownedClassIds))
        assertFalse(isPrivateBackupLecture(-4L, null, ownedClassIds))
    }

}
