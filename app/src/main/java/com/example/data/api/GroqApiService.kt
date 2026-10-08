package com.example.data.api

import android.content.Context
import android.util.Log
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaCodec
import com.example.data.local.ApiKeyStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
                val durationMs = readAudioDurationMs(file)
                if (durationMs > CHUNK_DURATION_MS || file.length() > MAX_SINGLE_FILE_BYTES)
                    return@withContext transcribeInChunks(context, key, file, onProgress)
                transcribeSingleFile(key, file, onProgress)
            } catch (e: Exception) {
                Log.e(TAG, "Groq transcription failed", e)
                Result.failure(e)
            }
        }

    private const val CHUNK_DURATION_MS = 10 * 60 * 1000L
    private const val MAX_SINGLE_FILE_BYTES = 20L * 1024L * 1024L

    private fun readAudioDurationMs(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var duration = 0L
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true)
                    duration = maxOf(duration, f.getLong(android.media.MediaFormat.KEY_DURATION) / 1000L)
            }
            duration
        } finally { extractor.release() }
    }

    private fun splitAudioIntoChunks(file: File, dir: File): List<File> {
        dir.mkdirs()
        val extractor = MediaExtractor()
        val result = mutableListOf<File>()
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var startUs = -1L
        var index = 0
        try {
            extractor.setDataSource(file.absolutePath)
            var sourceTrack = -1
            var format: android.media.MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    sourceTrack = i
                    format = f
                    break
                }
            }
            if (sourceTrack < 0 || format == null) throw Exception("مسیر صوتی قابل تقسیم نیست.")
            val audioFormat = format
            extractor.selectTrack(sourceTrack)
            val buffer = java.nio.ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            fun openChunk() {
                val out = File(dir, "part_\${index + 1}.m4a")
                muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                trackIndex = muxer!!.addTrack(audioFormat)
                muxer!!.start()
                startUs = extractor.sampleTime
                result += out
                index++
            }

            while (extractor.sampleTime >= 0L) {
                if (muxer == null) openChunk()
                val t = extractor.sampleTime
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = t - startUs
                info.flags = extractor.sampleFlags
                muxer!!.writeSampleData(trackIndex, buffer, info)
                extractor.advance()
                if (t - startUs >= CHUNK_DURATION_MS * 1000L) {
                    muxer!!.stop(); muxer!!.release(); muxer = null; trackIndex = -1; startUs = -1L
                }
            }
            try { muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
            return result.filter { it.exists() && it.length() > 0L }.toList()
        } catch (e: Exception) {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            result.forEach { it.delete() }
            throw e
        } finally { extractor.release() }
    }

    private suspend fun transcribeInChunks(
        context: Context,
        key: String,
        file: File,
        onProgress: (TranscriptionProgress) -> Unit
    ): Result<String> {
        val dir = File(context.cacheDir, "groq_chunks_\${System.currentTimeMillis()}")
        return try {
            onProgress(TranscriptionProgress("تقسیم فایل صوتی", 5, totalBytes = file.length(), detail = "فایل طولانی است؛ صوت به بخش‌های ۱۰ دقیقه‌ای تقسیم می‌شود."))
            val chunks = splitAudioIntoChunks(file, dir)
            if (chunks.isEmpty()) return Result.failure(Exception("تقسیم فایل صوتی ناموفق بود."))
            val texts = mutableListOf<String>()
            chunks.forEachIndexed { i, chunk ->
                onProgress(TranscriptionProgress("تبدیل بخش \${i + 1} از \${chunks.size}", 10 + i * 80 / chunks.size, totalBytes = file.length(), detail = "در حال ارسال بخش \${i + 1} از \${chunks.size}"))
                val r = transcribeSingleFile(key, chunk) { p ->
                    val mapped = 10 + i * 80 / chunks.size + p.percent.coerceIn(0, 100) * 80 / chunks.size / 100
                    onProgress(p.copy(stage = "تبدیل بخش \${i + 1} از \${chunks.size}", percent = mapped.coerceIn(10, 95)))
                }
                if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                texts += r.getOrThrow()
            }
            onProgress(TranscriptionProgress("تبدیل صوت به متن کامل شد", 100, file.length(), file.length(), detail = "\${chunks.size} بخش با موفقیت به هم متصل شد."))
            Result.success(texts.joinToString("\n\n"))
        } finally { dir.deleteRecursively() }
    }

    private fun transcribeSingleFile(
        key: String,
        file: File,
        onProgress: (TranscriptionProgress) -> Unit
    ): Result<String> {
        val mime = when (file.extension.lowercase()) {
            "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav";
            "ogg" -> "audio/ogg"; "webm" -> "audio/webm"; "flac" -> "audio/flac";
            else -> "application/octet-stream"
        }.toMediaType()
        onProgress(TranscriptionProgress("ارسال فایل به Groq", 15, totalBytes = file.length(), detail = "در حال ارسال فایل صوتی"))
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL).addFormDataPart("language", "fa")
            .addFormDataPart("response_format", "json").addFormDataPart("temperature", "0")
            .addFormDataPart("prompt", "این فایل صدای کلاس دانشگاهی به زبان فارسی است. اصطلاحات علمی، آیات، احادیث و عبارات عربی را دقیق ثبت کن.")
            .addFormDataPart("file", file.name, file.asRequestBody(mime)).build()
        val request = Request.Builder().url(URL).header("Authorization", "Bearer " + key).post(body).build()
        val response = client.newCall(request).execute()
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "Groq error " + response.code + ": " + raw)
            return Result.failure(Exception("خطای Groq در تبدیل صوت به متن: " + response.code))
        }
        val text = JSONObject(raw).optString("text").trim()
        if (text.isBlank()) return Result.failure(Exception("Groq متنی برای بخش صوتی برنگرداند."))
        onProgress(TranscriptionProgress("دریافت متن بخش", 100, file.length(), file.length(), detail = "بخش با موفقیت تبدیل شد."))
        return Result.success(text)
    }

    // ---------------------------------------------------------
    // تولید متن با Groq (اولویت اصلی تمام قابلیت‌های متنی)
    // ---------------------------------------------------------
    suspend fun generateContent(
        prompt: String,
        systemPrompt: String? = null
    ): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val context = currentContext
            ?: return@withContext Result.failure(Exception("تنظیمات Groq در دسترس نیست."))
        val key = ApiKeyStore.getGrokApiKey(context).orEmpty()
        if (key.isBlank()) {
            return@withContext Result.failure(Exception("کلید Groq تنظیم نشده است."))
        }

        try {
            val messages = org.json.JSONArray().apply {
                if (!systemPrompt.isNullOrBlank()) {
                    put(org.json.JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                put(org.json.JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            val json = org.json.JSONObject().apply {
                put("model", "openai/gpt-oss-120b")
                put("messages", messages)
                put("temperature", 0.2)
                put("max_tokens", 32768)
            }

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val raw = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Log.e(TAG, "Groq text error ${response.code}: $raw")
                return@withContext Result.failure(
                    Exception("خطای Groq در پردازش متن: ${response.code}")
                )
            }

            val text = org.json.JSONObject(raw)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                .orEmpty()

            if (text.isBlank()) {
                Result.failure(Exception("Groq پاسخ متنی معتبری برنگرداند."))
            } else {
                Result.success(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq text generation failed", e)
            Result.failure(e)
        }
    }

    suspend fun polishTranscript(rawTranscript: String): Result<String> {
        val systemPrompt = """
            تو ویراستار تخصصی متون دانشگاهی فارسی و جزوه‌های حوزوی هستی.
            متن خام حاصل از تبدیل صوت را بدون تغییر معنا و بدون حذف محتوای علمی اصلاح کن.
            تپق‌ها، تکرارهای بی‌فایده و کلمات پرکننده گفتاری را حذف کن.
            غلط‌های املایی و شنیداری را تا حد امکان از روی بافت اصلاح کن.
            علائم نگارشی، فاصله‌گذاری و نیم‌فاصله را درست کن.
            پاراگراف‌بندی و تیترهای کوتاه و روشن ایجاد کن.
            اصطلاحات عربی، آیات قرآن، احادیث، نام اشخاص و اصطلاحات اصولی/فقهی را حفظ کن و در صورت اطمینان اصلاح کن.
            هیچ توضیحی درباره فرایند ویرایش نده و فقط متن نهایی را برگردان.
        """.trimIndent()

        val prompt = """
            متن خام پیاده‌شده از سخنرانی استاد:

            $rawTranscript
        """.trimIndent()

        return generateContent(prompt, systemPrompt)
    }

    suspend fun summarizeLecture(
        transcript: String,
        lectureTitle: String
    ): Result<String> {
        return generateContent(
            """
            عنوان جلسه: $lectureTitle

            متن جلسه:
            $transcript

            خلاصه‌ای دقیق و ساختارمند از این جلسه تهیه کن؛ شامل موضوع اصلی، مباحث مطرح‌شده، نتایج و نکات مهم.
            متن فارسی دانشگاهی و خوانا باشد.
            """.trimIndent(),
            "تو دستیار علمی و آموزشی یک استاد دانشگاه هستی."
        )
    }

    suspend fun extractKeyPoints(transcript: String): Result<String> {
        return generateContent(
            """
            از متن زیر «مباحث اصلی»، «نکات کلیدی»، «تعریف‌ها» و اصطلاحات مهم را با تیترهای کوتاه استخراج کن.
            از ساختن مطلبی که در متن نیست خودداری کن.

            متن:
            $transcript
            """.trimIndent(),
            "تو دستیار آموزشی دقیق برای تهیه جزوه هستی."
        )
    }

    suspend fun generateQuiz(transcript: String): Result<String> {
        return generateContent(
            """
            بر اساس متن زیر چهار سؤال چهارگزینه‌ای استاندارد تولید کن.
            فقط آرایه JSON معتبر با ساختار question, options, correctIndex, explanation برگردان.

            متن:
            $transcript
            """.trimIndent(),
            "تو طراح سؤال دانشگاهی هستی."
        )
    }

    suspend fun askLectureQuestion(transcript: String, question: String): Result<String> {
        return generateContent(
            """
            متن جلسه:
            $transcript

            سؤال دانشجو:
            $question

            فقط بر اساس متن جلسه پاسخ بده. اگر پاسخ در متن نیست، صریحاً بگو در متن جلسه مطرح نشده است.
            """.trimIndent(),
            "تو دستیار علمی همین جلسه هستی."
        )
    }

}