package com.example

import com.example.data.local.DatabaseBackup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun backupWithAllCollectionsIncludingEmptyArraysIsAccepted() {
        val root = JSONObject()
            .put("lectures", org.json.JSONArray())
            .put("classes", org.json.JSONArray())
            .put("courses", org.json.JSONArray())
            .put("folders", org.json.JSONArray())

        DatabaseBackup.validateBackupCollections(root)
    }

    @Test
    fun backupMissingAnyCollectionIsRejected() {
        val required = listOf("lectures", "classes", "courses", "folders")
        required.forEach { missing ->
            val root = JSONObject()
            required.filterNot { it == missing }.forEach { root.put(it, org.json.JSONArray()) }

            val error = runCatching { DatabaseBackup.validateBackupCollections(root) }.exceptionOrNull()
            assertNotNull("Missing $missing must reject the backup", error)
            assertTrue(error is IllegalArgumentException)
            assertTrue(error!!.message!!.contains(missing))
        }
    }

    @Test
    fun backupCollectionWithWrongJsonTypeIsRejected() {
        val root = JSONObject()
            .put("lectures", org.json.JSONArray())
            .put("classes", org.json.JSONArray())
            .put("courses", org.json.JSONArray())
            .put("folders", JSONObject())

        assertTrue(runCatching { DatabaseBackup.validateBackupCollections(root) }.isFailure)
    }
}
