package com.example.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdminCloudBackupValidatorTest {
    private fun validBackup(): String {
        val data = JSONObject().put("ownerUid", "teacher-A").put("recordType", "class").put("id", 12)
        val record = JSONObject().put("documentId", "class_12").put("data", data)
        val root = JSONObject().put("ownerUid", "teacher-A").put("activeGeneration", "gen-1").put("recordCount", 1)
        val teacher = JSONObject().put("ownerUid", "teacher-A").put("schemaVersion", 3)
            .put("root", root).put("records", JSONArray().put(record))
        return JSONObject().put("format", "darsyar-admin-cloud-backup").put("version", 1)
            .put("teacherCount", 1).put("recordCount", 1).put("teachers", JSONArray().put(teacher)).toString()
    }

    @Test fun acceptsCompleteSchemaThreeBackup() {
        val result = AdminCloudBackupValidator.validate(validBackup())
        assertEquals(1, result.teachers.size)
        assertEquals("teacher-A", result.teachers.single().ownerUid)
        assertEquals("gen-1", result.teachers.single().generationId)
        assertEquals("class_12", result.teachers.single().records.single().documentId)
        assertEquals(1, result.recordCount)
    }

    @Test fun rejectsForeignRecordOwner() {
        val input = JSONObject(validBackup())
        val teacher = input.getJSONArray("teachers").getJSONObject(0)
        teacher.getJSONArray("records").getJSONObject(0).getJSONObject("data").put("ownerUid", "teacher-B")
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(input.toString())
        }
    }

    @Test fun rejectsCountMismatch() {
        val input = JSONObject(validBackup()).put("recordCount", 2)
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(input.toString())
        }
    }

    @Test fun rejectsLegacySchemaForAutomaticRestore() {
        val input = JSONObject(validBackup())
        input.getJSONArray("teachers").getJSONObject(0).put("schemaVersion", 2)
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(input.toString())
        }
    }
}
