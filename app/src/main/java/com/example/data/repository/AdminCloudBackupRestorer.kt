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
            for (record in teacher.records) {
                batch.set(recordsRef.document(record.documentId), jsonToMap(record.data))
                batchSize++
                if (batchSize == 400) {
                    batch.commit().awaitResult()
                    batch = firestore.batch()
                    batchSize = 0
                }
            }
            if (batchSize > 0) batch.commit().awaitResult()

            // Verify all newly staged documents before switching the pointer.
            val staged = recordsRef.get().awaitResult().documents
            val stagedById = staged.associateBy { it.id }
            require(staged.size == teacher.records.size &&
                teacher.records.all { expected ->
                    val actual = stagedById[expected.documentId]?.data
                    actual != null && JSONObject(actual).toString() == expected.data.toString()
                }) {
                "رکوردهای استاد ${teacher.ownerUid} پس از نوشتن کامل تأیید نشد؛ نسخهٔ قبلی فعال باقی ماند."
            }

            val rootMap = mutableMapOf<String, Any?>()
            oldData?.forEach { (key, value) -> rootMap[key] = value }
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
