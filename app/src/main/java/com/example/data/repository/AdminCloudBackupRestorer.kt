package com.example.data.repository

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.UUID

/**
 * Stages each teacher's validated backup in a new generation, verifies it,
 * and only then changes that teacher's active-generation pointer.
 * Existing generations are intentionally never deleted.
 */
class AdminCloudBackupRestorer(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    data class RestoreReport(val teachersRestored: Int, val recordsRestored: Int)

    suspend fun restoreAll(jsonText: String): RestoreReport {
        // Complete validation of the whole file happens before the first write.
        val backup = AdminCloudBackupValidator.validate(jsonText)
        var restoredTeachers = 0
        var restoredRecords = 0

        for (teacher in backup.teachers) {
            val rootRef = firestore.collection("teacher_data").document(teacher.ownerUid)
            val oldRoot = rootRef.get().awaitResult()
            val oldData = oldRoot.data
            if (oldRoot.exists() && oldData?.get("ownerUid") != teacher.ownerUid) {
                throw IllegalStateException("مالک سند ابری استاد ${teacher.ownerUid} ناسازگار است؛ بازیابی این استاد متوقف شد.")
            }

            val newGeneration = "admin_restore_${System.currentTimeMillis()}_${UUID.randomUUID().toString().replace("-", "")}"
            val recordsRef = rootRef.collection("generations").document(newGeneration).collection("records")
            var batch = firestore.batch()
            var batchSize = 0
            var batchBytes = 0
            for (record in teacher.records) {
                val recordBytes = record.data.toString().toByteArray(Charsets.UTF_8).size
                if (batchSize > 0 &&
                    (batchSize >= 400 || batchBytes + recordBytes > 8 * 1024 * 1024)
                ) {
                    batch.commit().awaitResult()
                    batch = firestore.batch()
                    batchSize = 0
                    batchBytes = 0
                }
                batch.set(recordsRef.document(record.documentId), jsonToMap(record.data))
                batchSize++
                batchBytes += recordBytes
            }
            if (batchSize > 0) batch.commit().awaitResult()

            // Verify all newly staged documents before switching the pointer.
            val staged = recordsRef.get().awaitResult().documents
            val stagedById = staged.associateBy { it.id }
            require(staged.size == teacher.records.size &&
                teacher.records.all { expected ->
                    val actual = stagedById[expected.documentId]?.data
                    actual != null && canonicalJson(JSONObject(actual)) == canonicalJson(expected.data)
                }) {
                "رکوردهای استاد ${teacher.ownerUid} پس از نوشتن کامل تأیید نشد؛ نسخهٔ قبلی فعال باقی ماند."
            }

            val rootMap = mutableMapOf<String, Any?>()
            oldData?.forEach { (key, value) -> rootMap[key] = value }
            // Restore the teacher-level metadata captured in the same backup,
            // especially the folder hierarchy. Pointer fields are always generated
            // locally and never trusted from the imported JSON.
            val backupRoot = jsonToMap(teacher.root)
            listOf("folders", "teacherName").forEach { key ->
                if (backupRoot.containsKey(key)) rootMap[key] = backupRoot[key]
            }
            rootMap["ownerUid"] = teacher.ownerUid
            rootMap["schemaVersion"] = 3L
            rootMap["activeGeneration"] = newGeneration
            rootMap["recordCount"] = teacher.records.size
            rootMap["updatedAt"] = FieldValue.serverTimestamp()
            rootRef.set(rootMap).awaitResult()

            // Re-read the commit point. If it did not take effect, report failure.
            val committed = rootRef.get().awaitResult()
            require(committed.getString("activeGeneration") == newGeneration &&
                committed.getLong("recordCount") == teacher.records.size.toLong()) {
                "ثبت نسخهٔ فعال استاد ${teacher.ownerUid} تأیید نشد."
            }
            restoredTeachers++
            restoredRecords += teacher.records.size
        }
        return RestoreReport(restoredTeachers, restoredRecords)
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        is JSONObject -> {
            val keys = value.keys().asSequence().toList().sorted()
            JSONObject().apply { keys.forEach { key -> put(key, canonicalValue(value.get(key))) } }.toString()
        }
        else -> JSONObject().put("value", canonicalValue(value)).toString()
    }

    private fun canonicalValue(value: Any?): Any? = when (value) {
        JSONObject.NULL -> JSONObject.NULL
        is JSONObject -> JSONObject().apply {
            value.keys().asSequence().toList().sorted().forEach { put(it, canonicalValue(value.get(it))) }
        }
        is org.json.JSONArray -> org.json.JSONArray().apply {
            for (i in 0 until value.length()) put(canonicalValue(value.get(i)))
        }
        is Map<*, *> -> JSONObject().apply {
            value.keys.filterIsInstance<String>().sorted().forEach { key -> put(key, canonicalValue(value[key])) }
        }
        is Iterable<*> -> org.json.JSONArray().apply { value.forEach { put(canonicalValue(it)) } }
        else -> value
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = jsonValue(json.get(key))
        }
        return result
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> jsonToMap(value)
        is org.json.JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                .addOnCanceledListener { continuation.cancel() }
        }
}
