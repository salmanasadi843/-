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
import kotlinx.coroutines.delay
import kotlin.random.Random

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
                // Groq accepts long recordings when the file is within the upload-size limit.
                // Do not remux merely because duration exceeds 10 minutes: MediaMuxer can reject
                // valid audio codecs/containers with "Failed to add the track to the muxer".
                if (file.length() > MAX_SINGLE_FILE_BYTES)
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
                val out = File(dir, "part_${index + 1}.m4a")
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
        val dir = File(context.cacheDir, "groq_chunks_${System.currentTimeMillis()}")
        return try {
            onProgress(TranscriptionProgress("تقسیم فایل صوتی", 5, totalBytes = file.length(), detail = "فایل طولانی است؛ صوت به بخش‌های ۱۰ دقیقه‌ای تقسیم می‌شود."))
            val chunks = splitAudioIntoChunks(file, dir)
            if (chunks.isEmpty()) return Result.failure(Exception("تقسیم فایل صوتی ناموفق بود."))
            val texts = mutableListOf<String>()
            chunks.forEachIndexed { i, chunk ->
                onProgress(TranscriptionProgress("تبدیل بخش ${i + 1} از ${chunks.size}", 10 + i * 80 / chunks.size, totalBytes = file.length(), detail = "در حال ارسال بخش ${i + 1} از ${chunks.size}"))
                val r = transcribeSingleFile(key, chunk) { p ->
                    val mapped = 10 + i * 80 / chunks.size + p.percent.coerceIn(0, 100) * 80 / chunks.size / 100
                    onProgress(p.copy(stage = "تبدیل بخش ${i + 1} از ${chunks.size}", percent = mapped.coerceIn(10, 95)))
                }
                if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                texts += r.getOrThrow()
            }
            onProgress(TranscriptionProgress("تبدیل صوت به متن کامل شد", 100, file.length(), file.length(), detail = "${chunks.size} بخش با موفقیت به هم متصل شد."))
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
            .addFormDataPart("prompt", "این فایل صدای یک درس دانشگاهی/حوزوی به زبان فارسی است و ممکن است درباره اصول فقه، حجیت خبر و ظن باشد. واژه‌ها و اصطلاحات تخصصی را با دقت بشنو و با املای معیار ثبت کن. واژه‌نامه کمکی برای تشخیص در بافت مناسب: ادله حجیت، ادله ناهیه از عمل به ظن، خبر واحد، خبر ظنی، حجیت خبر، کشف از واقع، تنافی ادله، جمع عرفی، تخصیص، حکومت، اطلاق، تقیید، تواتر لفظی، تواتر معنوی، تواتر اجمالی، علم اجمالی، منجّزیت و معذّریت. این فهرست فقط راهنمای شنیدن است؛ اصطلاحی را که با صوت سازگار نیست جایگزین نکن. آیات قرآن و عبارات عربی را دقیق ثبت کن؛ اگر بخشی را نمی‌توانی تشخیص دهی، حدس نزن و همان صورت شنیده‌شده را نگه دار.")
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

            executeTextRequestWithRetry(request)
        } catch (e: Exception) {
            Log.e(TAG, "Groq text generation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Retry only transient Groq API/network failures. The request body is replayable JSON,
     * so retrying this text-generation request cannot duplicate a file upload.
     */
    private suspend fun executeTextRequestWithRetry(request: Request): Result<String> {
        val maxRetries = 3
        var retryIndex = 0

        while (true) {
            val response = try {
                client.newCall(request).execute()
            } catch (e: java.io.IOException) {
                if (retryIndex >= maxRetries) {
                    Log.e(TAG, "Groq text request failed after retries", e)
                    return Result.failure(Exception("ارتباط با Groq پس از چند تلاش ناموفق بود: ${e.localizedMessage ?: "خطای شبکه"}", e))
                }
                val waitMs = (1_000L shl retryIndex) + Random.nextLong(0, 501)
                Log.w(TAG, "Groq network error; retry ${retryIndex + 1}/$maxRetries after ${waitMs}ms")
                delay(waitMs)
                retryIndex++
                continue
            }

            val raw = response.body?.string().orEmpty()
            val status = response.code
            val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
            response.close()

            if (status in 200..299) {
                val text = try {
                    org.json.JSONObject(raw)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        ?.trim()
                        .orEmpty()
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid Groq text response", e)
                    return Result.failure(Exception("پاسخ Groq قابل خواندن نبود."))
                }

                return if (text.isBlank()) {
                    Result.failure(Exception("Groq پاسخ متنی معتبری برنگرداند."))
                } else {
                    Result.success(text)
                }
            }

            Log.e(TAG, "Groq text error $status: $raw")
            val transient = status == 408 || status == 429 || status == 500 ||
                status == 502 || status == 503 || status == 504
            if (transient && retryIndex < maxRetries) {
                val backoffMs = retryAfterSeconds?.coerceIn(1, 30)?.times(1_000L)
                    ?: ((1_000L shl retryIndex) + Random.nextLong(0, 501))
                Log.w(TAG, "Groq returned $status; retry ${retryIndex + 1}/$maxRetries after ${backoffMs}ms")
                delay(backoffMs)
                retryIndex++
                continue
            }

            val message = when (status) {
                401, 403 -> "کلید Groq معتبر نیست یا اجازه دسترسی ندارد (کد $status)."
                413 -> "متن ارسالی برای Groq بیش از حد بزرگ است (413)."
                429 -> "محدودیت درخواست Groq فعال است (429)؛ کمی بعد دوباره تلاش کنید."
                500, 502, 503, 504 -> "سرویس Groq موقتاً در دسترس نیست (کد $status). کمی بعد دوباره تلاش کنید."
                else -> "خطای Groq در پردازش متن: $status"
            }
            return Result.failure(Exception(message))
        }
    }

    suspend fun polishTranscript(
        rawTranscript: String
    ): Result<String> {
        val systemPrompt = """
            تو ویراستار علمی و امانت‌دارِ پیاده‌سازی گفتار استاد هستی؛ وظیفه تو تبدیل گفتار خام به متن نوشتاریِ رسمی و قابل مطالعه است، نه خلاصه‌نویسی یا شرح مستقل.
            نثر خروجی باید فارسی معیار، رسمی، علمی و غیرمحاوره‌ای باشد. محاوره را صرفاً غلط‌گیری نکن: ساختارهای محاوره‌ای را در سراسر متن به معادل نوشتاری طبیعی تبدیل کن؛ مانند «می‌خواد» به «می‌خواهد»، «می‌گن» به «می‌گویند»، «یه» به «یک» و «این‌جوری» به «به این صورت»، البته فقط وقتی با معنای جمله سازگار است.
            واژه‌های پُرکننده مانند «خب» و «یعنی» را فقط وقتی حذف کن که هیچ نقش معنایی، استدلالی یا تأکیدی ندارند؛ اگر نقش انتقالی دارند، آن‌ها را به عبارت رسمی مناسب تبدیل کن.
            امانت علمی الزامی است: همه گزاره‌ها، مقدمات، قیود، استدلال‌ها، مثال‌ها، پرسش‌ها، اشکال‌ها، پاسخ‌ها، احتمالات، تردیدها، نقل‌قول‌ها و ترتیب طرح بحث را حفظ کن. هیچ نکته‌ای را حذف، خلاصه، ادغام یا جابه‌جا نکن و موضع یا درجه قطعیت استاد را تغییر نده.
            عنوان‌گذاری نیز بخشی از تدوین متن است: هرگاه تغییر واقعی موضوع یا ورود به مرحله مستقل تازه‌ای از استدلال وجود دارد، عنوانی کوتاه و دقیق از خود محتوای گفتار استخراج کن. برای عنوان اصلی از نشانه «## » و برای زیرعنوان از «### » و در صورت ضرورت برای سطح سوم از «#### » استفاده کن. عنوان‌ها باید کوتاه، علمی و توصیف‌کننده محتوای بلافاصله پس از خود باشند؛ عنوان ساختگی، کلی یا تکراری نساز و برای هر پاراگراف عنوان جداگانه نگذار.
            اگر ورودی ادامه یک بحث از قطعه قبلی است، صرفاً به‌دلیل شروع قطعه، عنوان یا مقدمه تازه نساز. اگر در قطعه موضوع مستقلی شکل نگرفته، عنوان اضافه نکن.
            اصطلاحات علمی و حوزوی را فقط با قرائن خود متن اصلاح کن. در مباحث اصول فقه، به اصطلاحاتی مانند ادله حجیت، ادله ناهیه از عمل به ظن، خبر واحد، خبر ظنی، حجیت خبر، کشف از واقع، وصول به واقع، تنافی ادله، جمع عرفی، تخصیص، حکومت، اطلاق، تقیید، تواتر لفظی، تواتر معنوی، تواتر اجمالی، علم اجمالی، منجّزیت و معذّریت توجه کن؛ اما هیچ اصطلاحی را برخلاف بافت تحمیل نکن.
            آیات، روایات و عبارات عربی را از حافظه بازسازی نکن. فقط رسم‌الخط را در موارد روشن اصلاح کن. اگر بخشی واقعاً نامفهوم است، فقط همان بخش را با [عبارت نامفهوم در متن خام] مشخص کن و عبارت‌های روشن اطراف را حفظ کن.
            هیچ منبع، آیه، روایت، مثال، تعریف، استدلال یا نتیجه‌ای از دانش عمومی اضافه نکن. از گزارش بیرونی مانند «استاد می‌گوید» و «در این جلسه بررسی شد» استفاده نکن.
            خروجی باید متن کاملِ ویرایش‌شده همین ورودی باشد، نه چکیده یا جزوه خلاصه. طول و جزئیات متن را تا حد ممکن حفظ کن.
            فقط متن ویرایش‌شده را برگردان؛ هیچ توضیحی درباره فرایند ویرایش ننویس.
        """.trimIndent()

        val prompt = """
            متن زیر پیاده‌سازی خام گفتار استاد است. آن را طبق همه قواعد زیر به متن علمیِ رسمی و ساختاریافته تبدیل کن:
            ۱. محاوره را به نثر نوشتاری رسمی تبدیل کن، نه اینکه فقط نشانه‌گذاری را اصلاح کنی.
            ۲. عنوان‌های سلسله‌مراتبی را فقط در محل تغییر واقعی موضوع یا مرحله استدلال اضافه کن؛ عنوان‌ها را با ## و ### مشخص کن.
            ۳. تمام محتوای علمی، مثال‌ها، قیود، اشکال‌ها و پاسخ‌ها را با همان ترتیب حفظ کن.
            ۴. متن را خلاصه نکن، کوتاه نکن و چیزی از خودت اضافه نکن.
            ۵. اگر عبارتی مبهم است، حدس نزن؛ فقط همان قسمت را مشخص کن.
            ۶. خروجی فقط متن ویرایش‌شده باشد.

            متن خام:
            $rawTranscript
        """.trimIndent()

        return generateContent(prompt, systemPrompt)
    }

    suspend fun summarizeLecture(
        transcript: String,
        lectureTitle: String
    ): Result<String> {
        val systemPrompt = """
            تو ویراستار و تدوین‌گر امانت‌دار جزوه علمی از گفتار استاد دانشگاهی یا حوزوی هستی.
            متن باید از زبان و منظر خود استاد تنظیم شود؛ از روایت بیرونی مانند «استاد می‌گوید»، «استاد بیان می‌کند» و «در این جلسه استاد بررسی کرد» استفاده نکن.
            نثر فارسی معیار، رسمی، علمی و غیرمحاوره‌ای باشد؛ گفتار روزمره را به نثر نوشتاری تبدیل کن، اما معنا، موضع، میزان قطعیت و صدای علمی استاد را تغییر نده.
            اگر استاد در متن از اول‌شخص یا تعابیری مانند «به نظر من» استفاده کرده است، همان موضع را در صورت تناسب حفظ کن؛ ضمیر اول‌شخص یا نظر شخصی تازه‌ای به استاد نسبت نده.
            فقط بر اساس متن منبع بنویس. هیچ استدلال، منبع، مثال، تعریف، نقل‌قول یا نتیجه تازه‌ای اضافه نکن.
            دیدگاه صریح استاد، احتمال، دیدگاه نقل‌شده از دیگران، فرض، اشکال و پرسش حل‌نشده را از یکدیگر متمایز نگه دار. اگر متن به نتیجه قطعی نرسیده است، نتیجه قطعی نساز.
            تکرارهای لفظیِ غیرضروری را می‌توان فشرده کرد، اما هیچ نکته، قید، مثال لازم یا مرحله استدلالی را حذف نکن.
            عنوان‌ها باید دقیقاً از محتوای واقعی گفتار استخراج شوند؛ عنوان یا زیرعنوان ساختگی و تقسیم‌بندی بی‌دلیل نساز.
            از تولید نکات امتحانی، تله امتحانی، سؤال امتحانی، توصیه آموزشی یا توضیحات مستقل هوش مصنوعی خودداری کن.
            نام استاد، تاریخ، منبع و ارجاع را حدس نزن.
            خروجی باید یک جزوه منظم، دقیق، وفادار و قابل مطالعه باشد.
        """.trimIndent()

        val prompt = """
            از متن پیاده‌سازی‌شده گفتار استاد، دو بخش مستقل اما هماهنگ تهیه کن:

            ۱. چکیده علمی
            - موضوع، مسئله محوری، مسیر اصلی بحث و جمع‌بندی واقعیِ موجود در گفتار را در یک یا دو بند بیان کن.
            - از مقدمه‌پردازی کلی و گزارش بیرونی درباره سخنان استاد پرهیز کن.

            ۲. خلاصه تفصیلی درس
            - ساختار را با عنوان‌های شماره‌گذاری‌شده و سلسله‌مراتبی تنظیم کن.
            - دو بخش اصلی با شماره‌های «۱. چکیده علمی» و «۲. خلاصه تفصیلی درس» مشخص شوند. عنوان‌های داخل خلاصه تفصیلی از شماره «۲ـ۱. عنوان» آغاز شوند؛ سطح بعد «۲ـ۱ـ۱. عنوان» باشد و همین الگو ادامه یابد.
            - شماره‌ها در هر سطح پیوسته، مرتب و بدون تکرار یا پرش باشند و شماره‌گذاری زیرعنوان‌ها با شماره بخش «۲» هماهنگ بماند.
            - برای نکته‌های هم‌سطح در صورت نیاز از «الف)»، «ب)» و مانند آن استفاده کن.
            - هر عنوان باید محتوای بخش پس از خود را دقیقاً بازتاب دهد. اگر موضوعی زیرمجموعه بحث قبلی است، آن را در همان سلسله‌مراتب قرار بده.
            - عنوان‌ها کوتاه، علمی و روشن باشند؛ از عنوان‌های کلی و بی‌اطلاع مانند «نکته مهم» مگر در صورت ضرورت استفاده نکن.
            - تعاریف، مقدمات، دیدگاه‌ها، مراحل استدلال، اشکال‌ها، پاسخ‌ها، مثال‌های مهم، قیود، استثناها و نتایج را با حفظ پیوند منطقی بیاور.
            - متن را به نثر رسمی و نوشتاری تبدیل کن؛ از عبارت‌های محاوره‌ای مانند «خب»، «حالا ببینیم»، «این‌جوری» و ساختار گفت‌وگویی غیرضروری پرهیز کن.
            - جزوه را از زبان خود استاد بنویس، نه به صورت گزارش شخص ثالث. از تعبیرهایی مانند «استاد معتقد است» یا «استاد در ادامه می‌گوید» استفاده نکن؛ موضع استاد را مستقیم و بدون نسبت‌دادن نظر تازه بازنویسی کن.
            - فقط تکرار لفظی زائد را حذف کن؛ مسیر استدلال و مثال‌های لازم را کوتاه یا جابه‌جا نکن.
            - اگر عبارت یا اصطلاحی مبهم است، آن را حدس نزن و ابهام را با برچسب کوتاه مشخص کن.

            عنوان جلسه: $lectureTitle

            متن مبنا:
            $transcript
        """.trimIndent()

        return generateContent(prompt, systemPrompt)
    }

        suspend fun extractKeyPoints(transcript: String): Result<String> {
        val systemPrompt = """
            تو استخراج‌کننده دقیق کلیدواژه برای جزوه علمی هستی.
            دقیقاً ۱۰ کلیدواژه یا اصطلاح محوریِ متمایز از خود متن انتخاب کن؛ هر مورد باید واقعاً در متن یا به‌صورت روشن در آن مطرح شده باشد.
            فقط فهرست کوتاه ارائه کن و برای هیچ مورد تعریف یا توضیح ننویس.
            واژه‌های عمومی، مترادف‌های تکراری، نکات امتحانی، سؤال، تله امتحانی و اطلاعات بیرون از متن ممنوع است.
            اگر متن واقعاً کمتر از ۱۰ مفهوم متمایز دارد، مفهوم تازه نساز و فقط موارد مستند را بیاور.
        """.trimIndent()
        val prompt = """
            از متن زیر دقیقاً ۱۰ کلیدواژه یا اصطلاح تخصصیِ متمایز استخراج کن.
            خروجی با عنوان «کلیدواژه‌ها» و سپس فهرست شماره‌دار ارائه شود.
            هر مورد باید کوتاه، علمی و مستقیماً مستند به متن باشد؛ توضیح، تعریف، جمله کامل یا نکته امتحانی ننویس.
            اگر متن کمتر از ۱۰ مفهوم متمایز دارد، چیزی جعل نکن و فقط مفاهیم واقعی را فهرست کن.

            متن:
            $transcript
        """.trimIndent()
        return generateContent(prompt, systemPrompt)
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