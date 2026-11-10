package com.example.data.repository

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.DocumentSnapshot
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

        try {
        for (teacher in backup.teachers) {
            val rootRef = firestore.collection("teacher_data").document(teacher.ownerUid)
            val oldRoot = rootRef.get().awaitResult()
            val expectedRootVersion = oldRoot.toBackupRootVersion()
            val oldData = oldRoot.data
            if (oldRoot.exists()) {
                val existingOwner = oldData?.get("ownerUid") as? String
                if (!existingOwner.isNullOrBlank() && existingOwner != teacher.ownerUid) {
                    throw IllegalStateException("مالک سند ابری استاد ${teacher.ownerUid} ناسازگار است؛ بازیابی این استاد متوقف شد.")
                }
                if (existingOwner.isNullOrBlank() && teacher.schemaVersion == 3) {
                    throw IllegalStateException("سند ابری استاد ${teacher.ownerUid} مالک مشخصی ندارد؛ بازیابی خودکار متوقف شد.")
                }
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

            // Only restore safe teacher metadata. The generation pointer is generated
            // locally and all root mutations are guarded against concurrent sync/admin writes.
            val backupRoot = jsonToMap(teacher.root)
            val rootPatch = mutableMapOf<String, Any?>(
                "ownerUid" to teacher.ownerUid,
                "schemaVersion" to 3L,
                "activeGeneration" to newGeneration,
                "recordCount" to teacher.records.size,
                "updatedAt" to FieldValue.serverTimestamp(),
                // Remove embedded legacy arrays to keep the root below Firestore's 1 MiB limit.
                "classes" to FieldValue.delete(),
                "courses" to FieldValue.delete(),
                "lectures" to FieldValue.delete(),
                "payload" to FieldValue.delete(),
                "legacyPayload" to FieldValue.delete()
            )
            listOf("folders", "teacherName").forEach { key ->
                if (backupRoot.containsKey(key)) rootPatch[key] = backupRoot[key]
            }

            // This transaction is the commit point. If a teacher sync or another admin
            // operation changed the root while records were staged, keep the old
            // generation active and abandon this staged generation.
            firestore.runTransaction { transaction ->
                val currentRoot = transaction.get(rootRef)
                BackupGenerationCommitGuard.requireCanActivate(
                    teacher.ownerUid,
                    expectedRootVersion,
                    currentRoot.toBackupRootVersion()
                )
                transaction.set(rootRef, rootPatch, SetOptions.merge())
                true
            }.awaitResult()

            // Re-read the commit point. If it did not take effect, report failure.
            val committed = rootRef.get().awaitResult()
            require(committed.getString("activeGeneration") == newGeneration &&
                committed.getLong("recordCount") == teacher.records.size.toLong()) {
                "ثبت نسخهٔ فعال استاد ${teacher.ownerUid} تأیید نشد."
            }
            restoredTeachers++
            restoredRecords += teacher.records.size
        }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (restoredTeachers == 0) throw failure
            throw IllegalStateException(
                "بازیابی چنداستادی کامل نشد. تا پیش از خطا، بازیابی و فعال‌سازی $restoredTeachers استاد و $restoredRecords رکورد با موفقیت تأیید شد. " +
                    "وضعیت استادِ در حال پردازش را بررسی کنید؛ نسخه‌های قبلی حذف نشده‌اند. علت: ${failure.localizedMessage ?: "خطای نامشخص"}",
                failure
            )
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
        is Iterable<*> -> org.json.JSONArray().apply { value.forEach { put(it) } }
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

private fun DocumentSnapshot.toBackupRootVersion(): BackupRootVersion =
    BackupRootVersion(
        exists = exists(),
        ownerUid = getString("ownerUid"),
        schemaVersion = getLong("schemaVersion"),
        activeGeneration = getString("activeGeneration"),
        adminPending = getBoolean("adminPending") == true,
        adminPendingVersion = getLong("adminPendingVersion"),
        syncLockUntilMillis = getLong("syncLockUntilMillis")
    )
