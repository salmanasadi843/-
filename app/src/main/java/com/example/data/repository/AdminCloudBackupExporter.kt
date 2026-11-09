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
    private companion object {
        const val MAX_FILE_BYTES = 25 * 1024 * 1024
        const val MAX_TEACHERS = 500
        const val MAX_RECORDS = 100_000
    }

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
            if (schema == 1) {
                legacyRecords(ownerUid, rootData).forEach { (id, data) ->
                    require(JSONObject(data).toString().toByteArray(Charsets.UTF_8).size <= 700 * 1024) {
                        "رکورد قدیمی $id بیش از اندازهٔ مجاز است؛ خروجی کامل متوقف شد."
                    }
                    records.put(JSONObject().put("documentId", id).put("data", toJsonObject(data)))
                }
            }
            if (schema >= 2) {
                val expectedCount = (rootData["recordCount"] as? Number)?.toInt()
                    ?: error("شمارندهٔ رکوردهای پشتیبان استاد $ownerUid موجود نیست.")
                require(expectedCount == records.length()) {
                    "پشتیبان استاد $ownerUid ناقص است: شمارنده $expectedCount و رکوردهای دریافت‌شده ${records.length()}؛ خروجی متوقف شد."
                }
            }
            val backupRoot = toJsonObject(rootData).apply {
                put("ownerUid", ownerUid)
                if (schema == 1) put("recordCount", records.length())
            }
            val teacherEntry = JSONObject()
                .put("ownerUid", ownerUid)
                .put("schemaVersion", schema)
                .put("root", backupRoot)
                .put("records", records)
            if (schema == 1) teacherEntry.put("legacyPayload", toJsonObject(rootData))
            teachers.put(teacherEntry)
            totalRecords += records.length()
            require(totalRecords <= MAX_RECORDS) {
                "تعداد رکوردهای پشتیبان از سقف مجاز بیشتر است؛ خروجی کامل و قابل‌بازیابی ساخته نشد."
            }
        }
        require(teachers.length() in 1..MAX_TEACHERS) {
            "تعداد استادان برای ساخت پشتیبان تجمیعی معتبر نیست."
        }
        val output = JSONObject()
            .put("format", "darsyar-admin-cloud-backup")
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("teacherCount", teachers.length())
            .put("recordCount", totalRecords)
            .put("teachers", teachers)
        require(output.toString().toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "حجم پشتیبان تجمیعی بیش از ۲۵ مگابایت است؛ فایل ناقص یا غیرقابل‌بازیابی تولید نشد."
        }
        return output
    }

    private fun legacyRecords(
        ownerUid: String,
        root: Map<String, Any?>
    ): List<Pair<String, Map<String, Any?>>> {
        fun rows(name: String): List<Map<String, Any?>> {
            val source = root[name] as? List<*>
                ?: error("آرایهٔ $name در پشتیبان قدیمی استاد $ownerUid موجود نیست.")
            return source.mapIndexed { index, value ->
                val map = value as? Map<*, *>
                    ?: error("رکورد شمارهٔ ${index + 1} از $name معتبر نیست.")
                require(map.keys.all { it is String }) { "کلید نامعتبر در $name استاد $ownerUid." }
                map.entries.associate { (key, item) -> key as String to item }.toMutableMap()
            }
        }

        val result = mutableListOf<Pair<String, Map<String, Any?>>>()
        val seenIds = mutableSetOf<String>()
        fun add(id: String, data: Map<String, Any?>) {
            require(id.isNotBlank() && seenIds.add(id)) { "شناسهٔ تکراری یا خالی در پشتیبان قدیمی استاد $ownerUid." }
            result += id to data
        }

        rows("classes").forEach { source ->
            val data = source.toMutableMap()
            val id = (data["id"] as? Number)?.toLong()
                ?: error("شناسهٔ یکی از کلاس‌های قدیمی استاد $ownerUid معتبر نیست.")
            require(id > 0) { "شناسهٔ کلاس قدیمی باید مثبت باشد." }
            val owner = (data["ownerUid"] as? String).orEmpty()
            require(owner.isBlank() || owner == ownerUid) { "مالک کلاس قدیمی با استاد $ownerUid سازگار نیست." }
            data["ownerUid"] = ownerUid
            data["recordType"] = "class"
            add("class_$id", data)
        }

        rows("courses").forEach { source ->
            val data = source.toMutableMap()
            val id = (data["id"] as? Number)?.toLong()
                ?: error("شناسهٔ یکی از درس‌های قدیمی استاد $ownerUid معتبر نیست.")
            require(id > 0) { "شناسهٔ درس قدیمی باید مثبت باشد." }
            val existingOwner = (data["ownerUid"] as? String).orEmpty()
            require(existingOwner.isBlank() || existingOwner == ownerUid) {
                "مالک درس قدیمی با استاد $ownerUid سازگار نیست."
            }
            data["ownerUid"] = ownerUid
            data["recordType"] = "course"
            add("course_$id", data)
        }

        rows("lectures").forEach { source ->
            val data = source.toMutableMap()
            val id = (data["id"] as? Number)?.toLong()
                ?: error("شناسهٔ یکی از جلسات قدیمی استاد $ownerUid معتبر نیست.")
            require(id > 0) { "شناسهٔ جلسهٔ قدیمی باید مثبت باشد." }
            val existingOwner = (data["ownerUid"] as? String).orEmpty()
            require(existingOwner.isBlank() || existingOwner == ownerUid) {
                "مالک جلسهٔ قدیمی با استاد $ownerUid سازگار نیست."
            }
            val textFields = listOf("transcript", "originalTranscript", "aiSummary", "aiKeyPoints", "aiQuizJson", "tags")
            val manifest = linkedMapOf<String, Int>()
            textFields.forEach { field ->
                val raw = data[field]
                if (raw != null) {
                    val value = raw as? String ?: raw.toString()
                    val chunks = BackupTextChunker.splitUtf8(value, 256 * 1024)
                    manifest[field] = chunks.size
                    chunks.forEachIndexed { index, chunk ->
                        add(
                            "lecture_text_${id}_${field}_$index",
                            mapOf(
                                "recordType" to "lecture_text_chunk",
                                "ownerUid" to ownerUid,
                                "lectureId" to id,
                                "field" to field,
                                "index" to index,
                                "value" to chunk
                            )
                        )
                    }
                    data[field] = ""
                }
            }
            data["aiSummary"] = null
            data["aiKeyPoints"] = null
            data["aiQuizJson"] = null
            data["textChunkCounts"] = manifest
            data["ownerUid"] = ownerUid
            data["recordType"] = "lecture"
            add("lecture_$id", data)
        }
        return result
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
