package com.example.data.api

import android.content.Context
import android.util.Log
import com.example.data.local.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object SpeechmaticsApiService {
    private const val TAG = "SpeechmaticsApiService"
    private const val BASE_URL = "https://eu1.asr.api.speechmatics.com/v2"
    private const val MODEL = "enhanced"
    private var currentContext: Context? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    fun configure(context: Context) { currentContext = context.applicationContext }
    fun getSavedApiKey(context: Context): String = ApiKeyStore.getSpeechmaticsApiKey(context) ?: ""
    fun saveApiKey(context: Context, apiKey: String) = ApiKeyStore.saveSpeechmaticsApiKey(context, apiKey)
    fun deleteSavedApiKey(context: Context) = ApiKeyStore.removeSpeechmaticsApiKey(context)
    fun hasApiKey(context: Context?): Boolean = context?.let { !ApiKeyStore.getSpeechmaticsApiKey(it).isNullOrBlank() } == true

    suspend fun transcribeAudioFile(
        filePath: String,
        onProgress: (TranscriptionProgress) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val context = currentContext ?: return@withContext Result.failure(Exception("تنظیمات Speechmatics در دسترس نیست."))
        val key = ApiKeyStore.getSpeechmaticsApiKey(context).orEmpty()
        if (key.isBlank()) return@withContext Result.failure(Exception("کلید Speechmatics تنظیم نشده است."))
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) return@withContext Result.failure(Exception("فایل صوتی معتبر نیست."))

        try {
            onProgress(TranscriptionProgress("ارسال فایل به Speechmatics", 10, totalBytes = file.length(), detail = "در حال ارسال فایل برای Speechmatics"))

            val mime = when (file.extension.lowercase()) {
                "m4a" -> "audio/mp4"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "webm" -> "audio/webm"
                "flac" -> "audio/flac"
                else -> "application/octet-stream"
            }.toMediaType()

            val config = JSONObject().apply {
                put("type", "transcription")
                put("transcription_config", JSONObject().apply {
                    put("language", "fa")
                    put("model", MODEL)
                })
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("data_file", file.name, file.asRequestBody(mime))
                .addFormDataPart("config", config.toString())
                .build()

            val request = Request.Builder()
                .url("$BASE_URL/jobs?wait=60&format=txt")
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()

            onProgress(TranscriptionProgress("در حال پردازش با Speechmatics", 35, totalBytes = file.length(), detail = "Speechmatics فایل را به‌صورت Batch پردازش می‌کند."))

            val response = client.newCall(request).execute()
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "Speechmatics error ${response.code}: $raw")
                return@withContext Result.failure(Exception("خطای Speechmatics در تبدیل صوت به متن: ${response.code}"))
            }

            val json = JSONObject(raw)
            if (json.optString("status") == "done") {
                val transcript = json.optString("txt").trim()
                if (transcript.isNotBlank()) {
                    onProgress(TranscriptionProgress("تبدیل صوت به متن کامل شد", 100, file.length(), file.length(), detail = "متن با موفقیت توسط Speechmatics دریافت شد"))
                    return@withContext Result.success(transcript)
                }
            }

            val jobId = json.optString("id")
            if (jobId.isBlank()) return@withContext Result.failure(Exception("Speechmatics شناسه پردازش را برنگرداند."))

            for (attempt in 1..24) {
                delay(5000)
                val statusRequest = Request.Builder()
                    .url("$BASE_URL/jobs/$jobId")
                    .header("Authorization", "Bearer $key")
                    .get()
                    .build()
                val statusResponse = client.newCall(statusRequest).execute()
                val statusRaw = statusResponse.body?.string().orEmpty()
                if (!statusResponse.isSuccessful) {
                    return@withContext Result.failure(Exception("خطای Speechmatics هنگام بررسی وضعیت: ${statusResponse.code}"))
                }

                val statusJson = JSONObject(statusRaw)
                val jobStatus = statusJson.optString("status")
                onProgress(TranscriptionProgress("پردازش فایل در Speechmatics", (40 + attempt * 2).coerceAtMost(88), totalBytes = file.length(), detail = "وضعیت پردازش: $jobStatus"))

                when (jobStatus.lowercase()) {
                    "done" -> {
                        val transcriptRequest = Request.Builder()
                            .url("$BASE_URL/jobs/$jobId/transcript?format=txt")
                            .header("Authorization", "Bearer $key")
                            .get()
                            .build()
                        val transcriptResponse = client.newCall(transcriptRequest).execute()
                        val transcript = transcriptResponse.body?.string().orEmpty().trim()
                        if (!transcriptResponse.isSuccessful || transcript.isBlank()) {
                            return@withContext Result.failure(Exception("Speechmatics پردازش را کامل کرد اما متن قابل دریافت نیست."))
                        }
                        onProgress(TranscriptionProgress("تبدیل صوت به متن کامل شد", 100, file.length(), file.length(), detail = "متن با موفقیت توسط Speechmatics دریافت شد"))
                        return@withContext Result.success(transcript)
                    }
                    "rejected", "failed", "deleted", "expired" ->
                        return@withContext Result.failure(Exception("Speechmatics پردازش فایل را با وضعیت «$jobStatus» متوقف کرد."))
                }
            }
            Result.failure(Exception("زمان پردازش Speechmatics بیش از حد مجاز طول کشید."))
        } catch (e: Exception) {
            Log.e(TAG, "Speechmatics transcription failed", e)
            Result.failure(e)
        }
    }
}
