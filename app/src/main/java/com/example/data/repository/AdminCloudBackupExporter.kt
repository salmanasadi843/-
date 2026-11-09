package com.example.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Read-only aggregate export; preserves each teacher UID and original document IDs. */
class AdminCloudBackupExporter(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    suspend fun exportAllTeachers(): JSONObject {
        val roots = firestore.collection("teacher_data").get().awaitResult().documents
        val teachers = JSONArray()
        var totalRecords = 0

        for (root in roots.sortedBy { it.id }) {
            val ownerUid = root.id
            val rootData = root.data ?: emptyMap()
            val generation = root.getString("activeGeneration")
            val schema = root.getLong("schemaVersion")?.toInt() ?: 1
            require(schema in 1..3) { "نسخهٔ پشتیبان استاد $ownerUid پشتیبانی نمی‌شود؛ خروجی ناقص متوقف شد." }
            require(rootData["ownerUid"] == null || rootData["ownerUid"] == ownerUid) {
                "مالک سند اصلی پشتیبان با شناسهٔ استاد $ownerUid سازگار نیست."
            }
            val records = JSONArray()
            val recordSnapshots = when {
                schema >= 3 -> {
                    require(!generation.isNullOrBlank()) {
                        "نسخه فعال پشتیبان استاد $ownerUid مشخص نیست؛ خروجی ناقص متوقف شد."
                    }
                    firestore.collection("teacher_data").document(ownerUid)
                        .collection("generations").document(generation!!)
                        .collection("records").get().awaitResult().documents
                }
                schema == 2 -> firestore.collection("teacher_data").document(ownerUid)
                    .collection("records").get().awaitResult().documents
                else -> emptyList()
            }

            recordSnapshots.sortedBy { it.id }.forEach { record ->
                val data = record.data ?: error("رکورد ${record.id} از استاد $ownerUid خالی است.")
                require(data["ownerUid"] == null || data["ownerUid"] == ownerUid) {
                    "مالک رکورد ${record.id} با استاد $ownerUid سازگار نیست؛ خروجی متوقف شد."
                }
                records.put(JSONObject().put("documentId", record.id).put("data", toJsonObject(data)))
            }
            if (schema >= 2) {
                val expectedCount = (rootData["recordCount"] as? Number)?.toInt()
                    ?: error("شمارندهٔ رکوردهای پشتیبان استاد $ownerUid موجود نیست.")
                require(expectedCount == records.length()) {
                    "پشتیبان استاد $ownerUid ناقص است: شمارنده $expectedCount و رکوردهای دریافت‌شده ${records.length()}؛ خروجی متوقف شد."
                }
            }
            val teacherEntry = JSONObject()
                .put("ownerUid", ownerUid)
                .put("schemaVersion", schema)
                .put("root", toJsonObject(rootData))
                .put("records", records)
            if (schema == 1) teacherEntry.put("legacyPayload", toJsonObject(rootData))
            teachers.put(teacherEntry)
            totalRecords += records.length()
        }
        return JSONObject()
            .put("format", "darsyar-admin-cloud-backup")
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("teacherCount", teachers.length())
            .put("recordCount", totalRecords)
            .put("teachers", teachers)
    }

    private fun toJsonObject(map: Map<String, Any?>): JSONObject =
        JSONObject().apply { map.forEach { (key, value) -> put(key, toJsonValue(value)) } }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, nested) -> if (key is String) put(key, toJsonValue(nested)) }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is Number, is Boolean, is String -> value
        else -> value.toString()
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                .addOnCanceledListener { continuation.cancel() }
        }
}
