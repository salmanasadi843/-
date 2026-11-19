package com.example.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Protocol
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Read-only Firestore REST preview. It never writes to Firebase or Room. */
internal object FirestoreRestBackupPreview {
    private const val BASE = "https://firestore.googleapis.com/v1/projects"
    private const val PAGE_SIZE = 500

    suspend fun inspect(projectId: String, uid: String, idToken: String): String =
        withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
            val encodedUid = encode(uid)
            val root = getDocument(client, projectId, "teacher_data/$encodedUid", idToken)
            val fields = root.optJSONObject("fields")
                ?: return@withContext "REST پیش‌نمایش: سند ریشه فاقد فیلدهای معتبر است."

            val schema = fields.longValue("schemaVersion")
            if (schema != 2L) {
                return@withContext "REST پیش‌نمایش: نسخه پشتیبان $schema است؛ بررسی نسخه ۲ انجام نشد."
            }
            val expected = fields.longValue("recordCount")
            val owner = fields.stringValue("ownerUid")
            if (owner.isNotBlank() && owner != uid) {
                return@withContext "REST پیش‌نمایش: مالک سند با حساب واردشده یکسان نیست؛ بررسی متوقف شد."
            }

            val all = mutableListOf<JSONObject>()
            var pageToken: String? = null
            do {
                val suffix = buildString {
                    append("teacher_data/$encodedUid/records?pageSize=$PAGE_SIZE")
                    if (!pageToken.isNullOrBlank()) append("&pageToken=").append(encode(pageToken!!))
                }
                val page = getCollection(client, projectId, suffix, idToken)
                val docs = page.optJSONArray("documents")
                if (docs != null) for (i in 0 until docs.length()) all += docs.getJSONObject(i)
                pageToken = page.optString("nextPageToken").takeIf { it.isNotBlank() }
                if (all.size > 100_000) error("تعداد رکوردها از حد ایمن بررسی بیشتر است.")
            } while (pageToken != null)

            val classes = mutableMapOf<Long, JSONObject>()
            val courses = mutableMapOf<Long, JSONObject>()
            val lectures = mutableMapOf<Long, JSONObject>()
            val errors = mutableListOf<String>()
            val rawTypeCounts = mutableMapOf<String, Int>()
            val documentNamesByTypeAndId = mutableMapOf<String, String>()

            for (doc in all) {
                val f = doc.optJSONObject("fields")
                if (f == null) {
                    errors += "سند بدون fields"
                    continue
                }
                val id = f.longValue("id")
                if (id <= 0L) {
                    errors += "شناسه نامعتبر در " + doc.optString("name").substringAfterLast("/")
                    continue
                }
                val recordType = f.stringValue("recordType")
                rawTypeCounts[recordType] = (rawTypeCounts[recordType] ?: 0) + 1
                val target = when (recordType) {
                    "class" -> classes
                    "course" -> courses
                    "lecture" -> lectures
                    else -> {
                        errors += "نوع رکورد ناشناخته در سند " + doc.optString("name").substringAfterLast("/")
                        continue
                    }
                }
                val identityKey = "$recordType:$id"
                val documentName = doc.optString("name").substringAfterLast("/")
                val previousDocument = documentNamesByTypeAndId.putIfAbsent(identityKey, documentName)
                if (previousDocument != null) {
                    errors += "شناسه تکراری $id برای $recordType در اسناد $previousDocument و $documentName"
                }
                target.put(id, f)
                if (recordType == "class" &&
                    f.stringValue("ownerUid").isNotBlank() &&
                    f.stringValue("ownerUid") != uid
                ) errors += "مالکیت کلاس $id با حساب فعلی تطبیق ندارد"
            }

            for ((id, f) in courses) {
                val parent = f.longValue("classId")
                if (parent !in classes) errors += "درس $id به کلاس ناموجود $parent متصل است"
            }
            for ((id, f) in lectures) {
                val classId = f.nullableLongValue("classId")
                val courseId = f.nullableLongValue("courseId")
                if (classId != null && classId !in classes) errors += "جلسه $id به کلاس ناموجود $classId متصل است"
                if (courseId != null && courseId !in courses) errors += "جلسه $id به درس ناموجود $courseId متصل است"
            }

            val actual = all.size.toLong()
            val countStatus = if (actual == expected) "مطابق" else "مغایر"
            val legacySummary = inspectLegacySharedMaterials(client, projectId, encodedUid, idToken)
            val valid = errors.isEmpty() && actual == expected && lectures.isNotEmpty()
            buildString {
                appendLine("REST پیش‌نمایش فقط‌خواندنی: " + if (valid) "اعتبارسنجی اولیه موفق" else "نیازمند بررسی؛ هیچ داده‌ای بازیابی نشد")
                appendLine("نسخه: 2؛ تعداد اعلام‌شده: $expected؛ تعداد خوانده‌شده: $actual ($countStatus)")
                appendLine("تعداد خام اسناد بر اساس نوع: کلاس ${rawTypeCounts["class"] ?: 0}؛ درس ${rawTypeCounts["course"] ?: 0}؛ جلسه ${rawTypeCounts["lecture"] ?: 0}")
                appendLine("شناسه‌های یکتای معتبر: کلاس ${classes.size}؛ درس ${courses.size}؛ جلسه ${lectures.size}")
                appendLine(legacySummary)
                append("موارد نیازمند بررسی: ")
                append(if (errors.isEmpty()) "ندارد" else errors.distinct().take(12).joinToString("؛ "))
                appendLine()
                append("هیچ اطلاعاتی در Firebase یا حافظه گوشی تغییر نکرد.")
            }
        }

    private fun inspectLegacySharedMaterials(
        client: OkHttpClient,
        projectId: String,
        encodedUid: String,
        token: String
    ): String = try {
        val document = getDocument(client, projectId, "shared_materials/$encodedUid", token)
        val fields = document.optJSONObject("fields")
            ?: return "پشتیبان قدیمی shared_materials: سند فاقد فیلدهای قابل‌خواندن است."
        val payloadText = fields.optJSONObject("payload")?.optString("stringValue").orEmpty()
        val payload = if (payloadText.isNotBlank()) JSONObject(payloadText) else null
        if (payload != null && payload.optString("format") == "darsyar-shared-materials") {
            val classes = payload.optJSONArray("classes")?.length() ?: 0
            val courses = payload.optJSONArray("courses")?.length() ?: 0
            val lectures = payload.optJSONArray("lectures")?.length() ?: 0
            "پشتیبان قدیمی: قالب شناخته‌شده؛ کلاس $classes؛ درس $courses؛ جلسه $lectures"
        } else {
            val classCount = fields.optJSONObject("classes")?.optJSONObject("arrayValue")?.optJSONArray("values")?.length() ?: 0
            val courseCount = fields.optJSONObject("courses")?.optJSONObject("arrayValue")?.optJSONArray("values")?.length() ?: 0
            val lectureCount = fields.optJSONObject("lectures")?.optJSONObject("arrayValue")?.optJSONArray("values")?.length() ?: 0
            "پشتیبان قدیمی: سند موجود؛ آرایه مستقیم کلاس $classCount؛ درس $courseCount؛ جلسه $lectureCount؛ قالب payload تشخیص داده نشد."
        }
    } catch (error: Exception) {
        "پشتیبان قدیمی: بررسی REST ناموفق؛ ${error.javaClass.simpleName}: ${error.message?.take(100) ?: "بدون جزئیات"}"
    }

    private fun getDocument(client: OkHttpClient, projectId: String, path: String, token: String): JSONObject =
        execute(client, "$BASE/$projectId/databases/(default)/documents/$path", token)

    private fun getCollection(client: OkHttpClient, projectId: String, pathAndQuery: String, token: String): JSONObject =
        execute(client, "$BASE/$projectId/databases/(default)/documents/$pathAndQuery", token)

    private fun execute(client: OkHttpClient, url: String, token: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Firestore REST پاسخ HTTP ${response.code} داد.")
            return JSONObject(body)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JSONObject.longValue(key: String): Long {
        val value = optJSONObject(key) ?: return 0L
        return value.optString("integerValue").toLongOrNull()
            ?: value.optDouble("doubleValue", 0.0).toLong()
    }

    private fun JSONObject.nullableLongValue(key: String): Long? {
        val value = optJSONObject(key) ?: return null
        if (value.has("nullValue")) return null
        return value.optString("integerValue").toLongOrNull()
            ?: value.optDouble("doubleValue", Double.NaN).takeIf { it.isFinite() }?.toLong()
    }

    private fun JSONObject.stringValue(key: String): String =
        optJSONObject(key)?.optString("stringValue").orEmpty()
}
