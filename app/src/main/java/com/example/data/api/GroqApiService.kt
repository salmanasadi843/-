package com.example.data.api

import android.content.Context
import android.util.Log
import com.example.data.local.ApiKeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object GroqApiService {
    private const val TAG = "GroqApiService"
    private const val URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3-turbo"
    private var currentContext: Context? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    fun configure(context: Context) { currentContext = context.applicationContext }
    fun getSavedApiKey(context: Context): String = ApiKeyStore.getGrokApiKey(context) ?: ""
    fun saveApiKey(context: Context, apiKey: String) = ApiKeyStore.saveGrokApiKey(context, apiKey)
    fun deleteSavedApiKey(context: Context) = ApiKeyStore.removeGrokApiKey(context)
    fun hasApiKey(context: Context?): Boolean = context?.let { !ApiKeyStore.getGrokApiKey(it).isNullOrBlank() } == true

    suspend fun transcribeAudioFile(filePath: String, onProgress: (TranscriptionProgress) -> Unit = {}): Result<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val context = currentContext ?: return@withContext Result.failure(Exception("تنظیمات Groq در دسترس نیست."))
            val key = ApiKeyStore.getGrokApiKey(context).orEmpty()
            if (key.isBlank()) return@withContext Result.failure(Exception("کلید Groq تنظیم نشده است."))
            val file = File(filePath)
            if (!file.exists() || file.length() == 0L) return@withContext Result.failure(Exception("فایل صوتی معتبر نیست."))
            try {
                onProgress(TranscriptionProgress("ارسال فایل به Groq", 10, totalBytes = file.length(), detail = "در حال آماده‌سازی فایل صوتی برای Groq"))
                val mime = when (file.extension.lowercase()) {
                    "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav";
                    "ogg" -> "audio/ogg"; "webm" -> "audio/webm"; "flac" -> "audio/flac";
                    else -> "application/octet-stream"
                }.toMediaType()
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", MODEL)
                    .addFormDataPart("language", "fa")
                    .addFormDataPart("response_format", "json")
                    .addFormDataPart("temperature", "0")
                    .addFormDataPart("prompt", "این فایل صدای کلاس دانشگاهی به زبان فارسی است. اصطلاحات علمی، آیات، احادیث و عبارات عربی را دقیق ثبت کن.")
                    .addFormDataPart("file", file.name, file.asRequestBody(mime)).build()
                onProgress(TranscriptionProgress("در حال تبدیل صوت به متن با Groq", 35, totalBytes = file.length(), detail = "مدل Whisper Large V3 Turbo در حال پردازش است"))
                val request = Request.Builder().url(URL).header("Authorization", "Bearer " + key).post(body).build()
                val response = client.newCall(request).execute()
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Groq error " + response.code + ": " + raw)
                    return@withContext Result.failure(Exception("خطای Groq در تبدیل صوت به متن: " + response.code))
                }
                val text = JSONObject(raw).optString("text").trim()
                if (text.isBlank()) return@withContext Result.failure(Exception("Groq متنی برای فایل صوتی برنگرداند."))
                onProgress(TranscriptionProgress("تبدیل صوت به متن کامل شد", 100, file.length(), file.length(), detail = "متن با موفقیت توسط Groq دریافت شد"))
                Result.success(text)
            } catch (e: Exception) {
                Log.e(TAG, "Groq transcription failed", e)
                Result.failure(e)
            }
        }
}