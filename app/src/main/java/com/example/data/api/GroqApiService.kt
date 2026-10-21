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
    // تولید متن با Groq (جایگزین Gemini در صورت خطا یا نبودن کلید)
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

    suspend fun polishTranscript(rawTranscript: String): Result<String> {
        val systemPrompt = """
            تو ویراستار وفادارِ متن پیاده‌سازی‌شده از سخنرانی هستی، نه نویسنده یا شارح مستقل.
            این ورودی ممکن است فقط یک قطعه از متن بلند یک جلسه باشد؛ آن را متن مستقل تلقی نکن.
            فقط خطاهای روشنِ نگارشی، نشانه‌گذاری، فاصله‌گذاری و جمله‌بندی را اصلاح کن. تبدیل محاوره به فارسی رسمی فقط تا جایی مجاز است که معنا با اطمینان از متن فهمیده شود.
            هیچ مقدمه، نتیجه‌گیری، جمع‌بندی، پرسش‌وپاسخ، نکته آموزشی، مثال، تعریف، استدلال، تفسیر فلسفی یا عنوان تازه‌ای تولید نکن.
            اصطلاحات علمی و حوزوی را با توجه به موضوع و جمله اصلاح کن، اگر صورت درست آن از بافت به‌روشنی قابل تشخیص است. اگر بحث درباره اصول فقه و حجیت خبر یا ظن است، به صورت‌های معیار این اصطلاحات توجه کن: ادله حجیت، ادله ناهیه از عمل به ظن، خبر واحد، خبر ظنی، حجیت خبر، کشف از واقع، وصول به واقع، تنافی ادله، جمع عرفی، تخصیص، حکومت، اطلاق، تقیید، آیات ناهیه از عمل به ظن، تواتر لفظی، تواتر معنوی، تواتر اجمالی، علم اجمالی، منجّزیت و معذّریت. این واژه‌نامه فقط راهنمای تشخیص است؛ اصطلاحی را که با گفتار و بافت سازگار نیست جایگزین نکن.
            عبارات عربی را از نظر رسم‌الخط اصلاح کن، اما متن حدیث یا آیه را از حافظه بازسازی نکن و واژه‌ای به آن نیفزا. عبارت شناخته‌شده را فقط وقتی اصلاح کن که خود متن و بافت شواهد کافی بدهند. اگر واژه یا عبارت عربی واقعاً قابل تشخیص نیست، فقط همان قسمت را با [عبارت نامفهوم در متن خام] مشخص کن؛ بخش‌های قابل فهم اطراف را نامفهوم علامت نزن.
            هیچ ادعایی را از روی دانش عمومی تکمیل نکن و هیچ واژهٔ نامفهومی را با حدس آوایی به اصطلاح تخصصی تبدیل نکن.
            اگر عبارتی نامفهوم است، فقط همان قسمت را با [عبارت نامفهوم در متن خام] مشخص کن؛ جمله‌های اطراف را بر اساس حدس بازسازی نکن.
            تمام مثال‌ها، پرسش‌ها، تردیدها، تکرارهای معنادار و ترتیب گفتار را حفظ کن. چیزی را خلاصه، حذف، ادغام یا جابه‌جا نکن.
            از ادبیات مقاله‌نویسی و عبارت‌هایی مانند «در این جلسه بررسی شد»، «در نهایت می‌توان گفت» و «نکات کلیدی عبارت‌اند از» استفاده نکن، مگر اینکه خود متن همین را گفته باشد.
            خروجی باید تقریباً هم‌اندازهٔ ورودی باشد و فقط متن ویرایش‌شده را شامل شود.
            مهم: اگر ورودی یک قطعه از متن است، از آن عنوان مستقل نساز؛ فقط متن همان قطعه را ویرایش کن.
        """.trimIndent()

        val prompt = """
            این متن، بخشی از پیاده‌سازی گفتار استاد است. آن را با کمترین مداخله و با وفاداری کامل ویرایش کن.
            
            قواعد الزامی:
            ۱. فقط متن موجود را اصلاح کن؛ هیچ مطلبی به آن اضافه نکن.
            ۲. مقدمه، تیتر، نتیجه‌گیری، خلاصه یا پرسش‌وپاسخِ ساخته‌شده تولید نکن.
            ۳. اصطلاحات علمی را با بافت جمله و موضوع درس اصلاح کن. اگر بحث درباره ادله حجیت و ظن است، صورت‌هایی مانند «ادله حجیت»، «آیات ناهیه از عمل به ظن»، «تنافی ادله»، «تخصیص»، «حکومت»، «اطلاق»، «خبر واحد» و «تواتر لفظی/معنوی/اجمالی» را بررسی کن و فقط در صورت سازگاری با جمله به کار ببر.
            ۴. عبارات عربی را با حدس از حافظه کامل نکن. اگر بخشی از آیه یا حدیث روشن نیست، فقط همان بخش را با [عبارت نامفهوم در متن خام] مشخص کن؛ عبارت‌های روشن فارسی یا عربی مجاور را بی‌دلیل حذف یا نامفهوم نکن.
            ۵. مثال‌ها، قیود، اشکال‌ها، پاسخ‌ها، تردیدها و ترتیب مطالب را حفظ کن.
            ۵. گفتار را به نثر خواناتر تبدیل کن، اما معنا را بازسازی یا تفسیر نکن.
            ۶. متن را کوتاه نکن و بخش‌های متفاوت را با هم ادغام نکن.
            ۷. خروجی فقط متن ویرایش‌شدهٔ همین قطعه باشد، بدون توضیح دربارهٔ کار انجام‌شده.
            
            متن ورودی:
            $rawTranscript
        """.trimIndent()

        return generateContent(prompt, systemPrompt)
    }

    suspend fun summarizeLecture(
        transcript: String,
        lectureTitle: String
    ): Result<String> {
        val systemPrompt = """
            تو نویسنده چکیده علمی و خلاصه تفصیلیِ امانت‌دار برای درس‌های دانشگاهی و حوزوی هستی.
            خروجی باید دو بخش متمایز داشته باشد: «چکیده علمی» و «خلاصه تفصیلی درس».
            چکیده علمی باید کوتاه و منسجم، و شامل موضوع، مسئله محوری، محورهای اصلی بررسی و نتیجه‌ای باشد که واقعاً از متن به دست می‌آید.
            خلاصه تفصیلی باید همه محورهای مهم، تعاریف، مراحل استدلال، مقدمات، اشکال‌ها، پاسخ‌ها، مثال‌های روشنگر و نتیجه‌های واقعی متن را پوشش دهد؛ نه اینکه صرفاً چند جمله کلی باشد.
            فقط از متن منبع استفاده کن. هیچ استدلال، منبع، مثال، تعریف یا نتیجه تازه‌ای اضافه نکن.
            دیدگاه صریح استاد، احتمال، دیدگاه نقل‌شده از دیگران و پرسش حل‌نشده را با هم خلط نکن. اگر متن به نتیجه قطعی نرسیده است، نتیجه قطعی نساز.
            تکرارهای لفظیِ غیرضروری را می‌توان فشرده کرد، اما هیچ نکته یا مرحله استدلالی را حذف نکن.
            از تولید بخش‌های «نکات امتحانی»، «تله امتحانی»، پرسش امتحانی، توصیه آموزشی یا توضیحات تکمیلی هوش مصنوعی خودداری کن.
            اطلاعاتی مانند نام استاد، تاریخ، منابع و ارجاعات را حدس نزن.
            خروجی باید فارسی رسمی، خوانا، دقیق و متناسب با یک جزوه علمی باشد.
        """.trimIndent()

        val prompt = """
            بر اساس متن زیر دو خروجی مستقل اما هماهنگ تهیه کن:

            بخش اول: چکیده علمی
            - در یک یا دو بند کوتاه، موضوع و مسئله محوری درس، محورهای اصلی استدلال و جمع‌بندی واقعی استاد را بیان کن.
            - چکیده باید معرفی فشرده بحث باشد، نه مقدمه‌ای کلی و نه فهرست مباحث.
            - نتیجه‌ای را که استاد صریحاً نگفته یا متن پشتیبانی نمی‌کند، اضافه نکن. اگر نتیجه قطعی روشن نیست، این محدودیت را حفظ کن.

            بخش دوم: خلاصه تفصیلی درس
            - با عنوان‌های اصلی و فرعی، مسیر بحث را منظم و روشن ارائه کن.
            - تعاریف، مقدمات، دیدگاه‌ها، استدلال‌ها، اشکال‌ها، پاسخ‌ها، مثال‌های مهم، قیود و استثناها را حفظ کن.
            - فقط تکرار لفظیِ زائد را فشرده کن؛ مراحل استدلال یا مثال‌های لازم برای فهم بحث را حذف نکن.
            - نثر رسمی و علمی باشد و از عبارت‌های محاوره‌ای فاصله بگیرد.
            - مطلبی بیرون از متن، منبع یا نقل‌قول ساختگی، نکته امتحانی و نتیجه‌گیری بی‌پشتوانه اضافه نکن.
            - اگر عبارت منبع مبهم است، آن را حدس نزن.

            عنوان جلسه: $lectureTitle

            متن مبنا:
            $transcript
        """.trimIndent()

        return generateContent(prompt, systemPrompt)
    }

    suspend fun extractKeyPoints(transcript: String): Result<String> {
        return generateContent(
            """
            فقط کلیدواژه‌ها، اصطلاحات تخصصی و مفاهیم محوری موجود در متن را به‌صورت فهرست کوتاه استخراج کن.
            برای هر واژه توضیح یا تعریف ننویس. نکته امتحانی، سؤال، تله امتحانی، فرمول تازه یا محتوای خارج از متن تولید نکن.
            از تکرار مترادف‌ها و عبارت‌های عمومی پرهیز کن.

            متن:
            $transcript
            """.trimIndent(),
            "تو استخراج‌کننده دقیق کلیدواژه برای جزوه علمی هستی."
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