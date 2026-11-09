package com.example.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdminCloudBackupValidatorTest {
    private fun validBackup() = """{"format":"darsyar-admin-cloud-backup","version":1,"teacherCount":1,"recordCount":1,"teachers":[{"ownerUid":"teacher-A","schemaVersion":3,"root":{"ownerUid":"teacher-A","activeGeneration":"gen-1","recordCount":1},"records":[{"documentId":"class_12","data":{"ownerUid":"teacher-A","recordType":"class","id":12}}]}]}"""

    @Test fun acceptsCompleteSchemaThreeBackup() {
        val result = AdminCloudBackupValidator.validate(validBackup())
        assertEquals(1, result.teachers.size)
        assertEquals("teacher-A", result.teachers.single().ownerUid)
        assertEquals("gen-1", result.teachers.single().generationId)
        assertEquals("class_12", result.teachers.single().records.single().documentId)
        assertEquals(1, result.recordCount)
    }

    @Test fun rejectsForeignRecordOwner() {
        val bad = validBackup().replace(
            """"data":{"ownerUid":"teacher-A"""",
            """"data":{"ownerUid":"teacher-B""""
        )
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(bad)
        }
    }

    @Test fun rejectsCountMismatch() {
        val bad = validBackup().replace(
            """"recordCount":1,"teachers":""",
            """"recordCount":2,"teachers":"""
        )
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(bad)
        }
    }

    @Test fun rejectsLegacySchemaForAutomaticRestore() {
        val bad = validBackup().replace(
            """"schemaVersion":3""",
            """"schemaVersion":2"""
        )
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(bad)
        }
    }
}
