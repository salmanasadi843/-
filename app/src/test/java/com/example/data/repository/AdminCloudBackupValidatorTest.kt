package com.example.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Android 16/API 36 Robolectric requires Java 21; CI uses Java 17.
class AdminCloudBackupValidatorTest {
    private fun validBackup() = """{"format":"darsyar-admin-cloud-backup","version":1,"teacherCount":1,"recordCount":1,"teachers":[{"ownerUid":"teacher-A","schemaVersion":3,"root":{"ownerUid":"teacher-A","activeGeneration":"gen-1","recordCount":1,"folders":[]},"records":[{"documentId":"class_12","data":{"ownerUid":"teacher-A","recordType":"class","id":12}}]}]}"""

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
            """data":{"ownerUid":"teacher-A""",
            """data":{"ownerUid":"teacher-B"""
        )
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(bad)
        }
    }

    @Test fun rejectsCountMismatch() {
        val bad = validBackup().replace(
            """recordCount":1,"teachers"""",
            """recordCount":2,"teachers""""
        )
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(bad)
        }
    }

    @Test fun acceptsLegacyRecordSchemaTwoForMigration() {
        val legacy = validBackup()
            .replace("""schemaVersion":3""", """schemaVersion":2""")
            .replace("\"activeGeneration\":\"gen-1\",", "")
        val result = AdminCloudBackupValidator.validate(legacy)
        assertEquals("legacy-records", result.teachers.single().generationId)
    }

    @Test fun acceptsConvertedLegacyArraySchemaOneBackup() {
        val legacy = validBackup().replace(
            """schemaVersion":3""",
            """schemaVersion":1"""
        ).replace(
            "\"activeGeneration\":\"gen-1\",",
            ""
        )
        val result = AdminCloudBackupValidator.validate(legacy)
        assertEquals(1, result.recordCount)
        assertEquals(1, result.teachers.single().schemaVersion)
        assertEquals("legacy-records", result.teachers.single().generationId)
    }

    @Test fun rejectsFolderParentCycles() {
        val bad = validBackup().replace(
            "\"folders\":[]",
            "\"folders\":[{\"id\":1,\"ownerUid\":\"teacher-A\",\"name\":\"الف\",\"parentFolderId\":2},{\"id\":2,\"ownerUid\":\"teacher-A\",\"name\":\"ب\",\"parentFolderId\":1}]"
        )
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(bad)
        }
    }

    @Test fun rejectsCourseThatReferencesMissingClass() {
        val bad = validBackup()
            .replace("\"recordCount\":1", "\"recordCount\":2")
            .replace(
                "\"records\":[{\"documentId\":\"class_12\",\"data\":{\"ownerUid\":\"teacher-A\",\"recordType\":\"class\",\"id\":12}}]",
                "\"records\":[{\"documentId\":\"class_12\",\"data\":{\"ownerUid\":\"teacher-A\",\"recordType\":\"class\",\"id\":12}},{\"documentId\":\"course_7\",\"data\":{\"ownerUid\":\"teacher-A\",\"recordType\":\"course\",\"id\":7,\"classId\":99}}]"
            )
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(bad)
        }
    }

    @Test fun rejectsLectureThatReferencesMissingCourse() {
        val json = """{"format":"darsyar-admin-cloud-backup","version":1,"teacherCount":1,"recordCount":3,"teachers":[{"ownerUid":"teacher-A","schemaVersion":3,"root":{"ownerUid":"teacher-A","activeGeneration":"gen-1","recordCount":3,"folders":[]},"records":[{"documentId":"class_12","data":{"ownerUid":"teacher-A","recordType":"class","id":12}},{"documentId":"course_7","data":{"ownerUid":"teacher-A","recordType":"course","id":7,"classId":12}},{"documentId":"lecture_9","data":{"ownerUid":"teacher-A","recordType":"lecture","id":9,"classId":12,"courseId":99}}]}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(json)
        }
    }

    @Test fun rejectsIncompleteTranscriptChunks() {
        val json = """{"format":"darsyar-admin-cloud-backup","version":1,"teacherCount":1,"recordCount":2,"teachers":[{"ownerUid":"teacher-A","schemaVersion":3,"root":{"ownerUid":"teacher-A","activeGeneration":"gen-1","recordCount":2,"folders":[]},"records":[{"documentId":"class_12","data":{"ownerUid":"teacher-A","recordType":"class","id":12}},{"documentId":"lecture_9","data":{"ownerUid":"teacher-A","recordType":"lecture","id":9,"classId":12,"textChunkCounts":{"transcript":2}}}]}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(json)
        }
    }

    @Test fun rejectsDuplicateDocumentIds() {
        val bad = validBackup().replace(
            "\"records\":[{\"documentId\":\"class_12\",\"data\":{\"ownerUid\":\"teacher-A\",\"recordType\":\"class\",\"id\":12}}]",
            "\"records\":[{\"documentId\":\"class_12\",\"data\":{\"ownerUid\":\"teacher-A\",\"recordType\":\"class\",\"id\":12}},{\"documentId\":\"class_12\",\"data\":{\"ownerUid\":\"teacher-A\",\"recordType\":\"class\",\"id\":12}}]"
        ).replace("\"recordCount\":1", "\"recordCount\":2")
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(bad)
        }
    }

    @Test fun preservesOwnershipAcrossMultipleTeachers() {
        val multi = """{"format":"darsyar-admin-cloud-backup","version":1,"teacherCount":2,"recordCount":2,"teachers":[{"ownerUid":"teacher-A","schemaVersion":3,"root":{"ownerUid":"teacher-A","activeGeneration":"gen-A","recordCount":1,"folders":[]},"records":[{"documentId":"class_12","data":{"ownerUid":"teacher-A","recordType":"class","id":12}}]},{"ownerUid":"teacher-B","schemaVersion":3,"root":{"ownerUid":"teacher-B","activeGeneration":"gen-B","recordCount":1,"folders":[]},"records":[{"documentId":"class_22","data":{"ownerUid":"teacher-B","recordType":"class","id":22}}]}]}"""
        val result = AdminCloudBackupValidator.validate(multi)
        assertEquals(2, result.teachers.size)
        assertEquals(setOf("teacher-A", "teacher-B"), result.teachers.map { it.ownerUid }.toSet())
        assertEquals(setOf("teacher-A", "teacher-B"), result.teachers.map { it.records.single().data.optString("ownerUid") }.toSet())
    }

    @Test fun rejectsDuplicateTeacherOwnerInAggregateBackup() {
        val duplicated = """{"format":"darsyar-admin-cloud-backup","version":1,"teacherCount":2,"recordCount":2,"teachers":[{"ownerUid":"teacher-A","schemaVersion":3,"root":{"ownerUid":"teacher-A","activeGeneration":"gen-A","recordCount":1,"folders":[]},"records":[{"documentId":"class_12","data":{"ownerUid":"teacher-A","recordType":"class","id":12}}]},{"ownerUid":"teacher-A","schemaVersion":3,"root":{"ownerUid":"teacher-A","activeGeneration":"gen-B","recordCount":1,"folders":[]},"records":[{"documentId":"class_22","data":{"ownerUid":"teacher-A","recordType":"class","id":22}}]}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            AdminCloudBackupValidator.validate(duplicated)
        }
    }

}
