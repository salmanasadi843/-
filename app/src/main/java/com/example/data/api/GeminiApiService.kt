package com.example.data.api

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class TranscriptionProgress(val stage:String,val percent:Int,val uploadedBytes:Long=0L,val totalBytes:Long=0L,val elapsedMs:Long=0L,val etaMs:Long?=null,val detail:String="")

private class ProgressRequestBody(private val file:File,private val type:okhttp3.MediaType,private val cb:(Long,Long)->Unit):okhttp3.RequestBody(){ override fun contentType()=type; override fun contentLength()=file.length(); override fun writeTo(sink:okio.BufferedSink){val total=contentLength();var sent=0L;val b=ByteArray(64*1024);file.inputStream().use{input->while(true){val n=input.read(b);if(n<=0)break;sink.write(b,0,n);sent+=n;cb(sent,total)}}}}

object GeminiApiService {
    private const val TAG = "GeminiApiService"
    private const val MODEL = "gemini-3.6-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    @Volatile
    private var appContext: Context? = null

    fun configure(context: Context) {
        appContext = context.applicationContext
    }

    fun getSavedApiKey(context: Context): String {
        return ApiKeyStore.getGeminiApiKey(context) ?: ""
    }

    fun saveApiKey(
        context: Context,
        apiKey: String
    ) {
        ApiKeyStore.saveGeminiApiKey(context, apiKey)
        configure(context)
    }

    fun deleteSavedApiKey(
        context: Context
    ) {
        ApiKeyStore.removeGeminiApiKey(context)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    /**
     * Retry transient Gemini HTTP failures with exponential backoff.
     * The finalized audio-data upload is intentionally not retried automatically,
     * because the server may already have accepted the uploaded bytes.
     */
    private suspend fun executeWithRetry(
        request: Request,
        operation: String,
        maxRetries: Int = 4
    ): okhttp3.Response {
        var retryIndex = 0
        while (true) {
            val response = client.newCall(request).execute()
            val retryable = response.code == 408 ||
                response.code == 429 ||
                response.code == 500 ||
                response.code == 502 ||
                response.code == 503 ||
                response.code == 504

            if (!retryable || retryIndex >= maxRetries) {
                if (retryable && retryIndex >= maxRetries) {
                    Log.w(TAG, "$operation: retry limit reached (HTTP ${response.code})")
                }
                return response
            }

            val retryAfterSeconds = response.header("Retry-After")
                ?.toLongOrNull()
                ?.coerceIn(1L, 30L)
            val exponentialDelay = (1000L shl retryIndex).coerceAtMost(16000L)
            val waitMs = retryAfterSeconds?.times(1000L)
                ?: (exponentialDelay + Random.nextLong(0L, 501L))

            Log.w(
                TAG,
                "$operation: transient HTTP ${response.code}; retry ${retryIndex + 1}/$maxRetries in ${waitMs}ms"
            )
            response.close()
            delay(waitMs)
            retryIndex++
        }
    }

    private fun getApiKey(): String {
        val savedKey = appContext?.let {
            ApiKeyStore.getGeminiApiKey(it)
        }

        if (!savedKey.isNullOrBlank()) {
            return savedKey
        }

        return try {
            BuildConfig.GEMINI_API_KEY
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun generateContent(
        prompt: String,
        systemPrompt: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {

        val apiKey = getApiKey()

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext Result.failure(
                IllegalStateException(
                    "کلید Gemini تنظیم نشده است. لطفاً از بخش «تنظیمات» یک کلید معتبر وارد کنید."
                )
            )
        }

        try {
            val endpoint =
                "$BASE_URL/$MODEL:generateContent?key=$apiKey"

            val contentsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            }

            val requestJson = JSONObject().apply {
                put("contents", contentsArray)

                if (!systemPrompt.isNullOrBlank()) {
                    put(
                        "systemInstruction",
                        JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", systemPrompt)
                                })
                            })
                        }
                    )
                }

                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.4)
                        put("topP", 0.9)
                        put("maxOutputTokens", 8192)
                    }
                )
            }

            val mediaType =
                "application/json; charset=utf-8".toMediaType()

            val body =
                requestJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .build()

            val response =
                executeWithRetry(request, "درخواست متنی Gemini")

            val responseBody =
                response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                Log.e(
                    TAG,
                    "Gemini API error: ${response.code} - $responseBody"
                )

                return@withContext Result.failure(
                    Exception(
                        if (response.code == 503 || response.code == 502 || response.code == 500 || response.code == 504) {
                            "سرویس Gemini پس از چند تلاش هنوز در دسترس نیست (کد ${response.code}). متن و فایل شما حفظ شده‌اند؛ کمی بعد دوباره تلاش کنید یا از Groq استفاده کنید."
                        } else if (response.code == 429) {
                            "محدودیت درخواست Gemini فعال شده است (429). کمی بعد دوباره تلاش کنید یا از Groq استفاده کنید."
                        } else {
                            "خطا در درخواست Gemini (کد ${response.code}). جزئیات برای بررسی در گزارش برنامه ثبت شد."
                        }
                    )
                )
            }

            val jsonObject =
                JSONObject(responseBody)

            val candidates = jsonObject.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            if (firstCandidate == null) {
                val apiError = jsonObject.optJSONObject("error")?.optString("message").orEmpty()
                val blockReason = jsonObject.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                val detail = listOf(apiError, blockReason).filter { it.isNotBlank() }.joinToString("؛ ")
                return@withContext Result.failure(
                    IllegalStateException(
                        if (detail.isNotBlank()) "Gemini پاسخی تولید نکرد: $detail"
                        else "Gemini هیچ پاسخ متنی برنگرداند؛ ممکن است درخواست مسدود یا پاسخ سرویس ناقص باشد."
                    )
                )
            }

            val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
            val textBuilder = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val text = part.optString("text").trim()
                    if (text.isNotBlank()) textBuilder.append(text)
                }
            }

            val resultText = textBuilder.toString().trim()
            if (resultText.isBlank()) {
                val finishReason = firstCandidate.optString("finishReason").takeIf { it.isNotBlank() }
                val blockReason = jsonObject.optJSONObject("promptFeedback")?.optString("blockReason")
                    ?.takeIf { it.isNotBlank() }
                val detail = listOfNotNull(blockReason, finishReason).joinToString("؛ ")
                Log.w(TAG, "Gemini response contained no text. $detail")
                return@withContext Result.failure(
                    IllegalStateException(
                        if (detail.isNotBlank()) "Gemini پاسخ متنی نداد: $detail"
                        else "ساختار پاسخ Gemini فاقد متن قابل‌خواندن بود."
                    )
                )
            }

            Result.success(resultText)

        } catch (e: Exception) {

            Log.e(TAG, "Request failed", e)

            Result.failure(e)
        }
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

        suspend fun extractKeyPoints(
        transcript: String
    ): Result<String> {
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

    suspend fun generateQuiz(
        transcript: String
    ): Result<String> {

        val systemPrompt =
            "تو یک طراح سوالات امتحانی دانشگاهی هستی. پاسخ را منحصراً در قالب یک آرایه JSON معتبر بازگردان."

        val prompt = """
بر اساس متن کلاس درس زیر، ۴ سوال ۴ گزینه‌ای استاندارد به همراه گزینه‌ها، ایندکس پاسخ صحیح (از 0 تا 3) و توضیح تشریحی مختصر تولید کن.

خروجی فقط و فقط باید آرایه JSON معتبر با ساختار زیر باشد و هیچ متن اضافی قبل یا بعد آن نوشته نشود:

[
  {
    "question": "متن سوال به زبان فارسی",
    "options": ["گزینه ۱", "گزینه ۲", "گزینه ۳", "گزینه ۴"],
    "correctIndex": 0,
    "explanation": "دلیل و توضیح تشریحی پاسخ صحیح"
  }
]

متن کلاس:
$transcript
        """.trimIndent()

        return generateContent(
            prompt,
            systemPrompt
        )
    }

    suspend fun askLectureQuestion(
        transcript: String,
        question: String
    ): Result<String> {

        val systemPrompt =
            "تو استادیار و معلم خصوصی این درس هستی. فقط بر مبنای مباحث تدریس شده در این جلسه کلاس درس و با لحنی دلسوزانه و علمی به سوال دانشجو پاسخ بده. اگر موضوعی در متن کلاس مطرح نشده، این نکته را قید کن."

        val prompt = """
متن جلسه کلاس:
$transcript

سوال دانشجو:
$question
        """.trimIndent()

        return generateContent(
            prompt,
            systemPrompt
        )
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

    // ============================================================
    // تبدیل فایل صوتی ضبط‌شده به متن
    // ============================================================

    suspend fun transcribeAudioFile(filePath:String,onProgress:(TranscriptionProgress)->Unit={}):Result<String> = withContext(Dispatchers.IO) {

        val apiKey = getApiKey()

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext Result.failure(
                IllegalStateException(
                    "کلید هوش مصنوعی (GEMINI_API_KEY) در تنظیمات برنامه مشخص نشده است."
                )
            )
        }

        try {
            val startedAt=System.currentTimeMillis()
            fun report(stage:String,p:Int,uploaded:Long=0L,total:Long=0L,eta:Long?=null,detail:String=""){onProgress(TranscriptionProgress(stage,p.coerceIn(0,100),uploaded,total,System.currentTimeMillis()-startedAt,eta,detail))}
            report("در حال بررسی فایل صوتی",2,detail="فرمت، حجم و سلامت فایل بررسی می‌شود")
            val audioFile = File(filePath)

            if (!audioFile.exists()) {
                return@withContext Result.failure(
                    Exception("فایل صوتی پیدا نشد.")
                )
            }

            if (audioFile.length() == 0L) {
                return@withContext Result.failure(
                    Exception("فایل صوتی خالی است.")
                )
            }

            val mimeType = when (
                audioFile.extension.lowercase()
            ) {
                "m4a" -> "audio/mp4"
                "mp4" -> "audio/mp4"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "aac" -> "audio/aac"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                else -> "audio/mp4"
            }

            report("در حال آماده‌سازی ارسال",5,total=audioFile.length(),detail="اتصال امن برای ارسال فایل در حال آماده‌سازی است")

            // شروع آپلود فایل
            val uploadUrl =
                "https://generativelanguage.googleapis.com/upload/v1beta/files?key=$apiKey"

            val startRequest = Request.Builder()
                .url(uploadUrl)
                .post(
                    "{}".toRequestBody(
                        "application/json".toMediaType()
                    )
                )
                .header(
                    "X-Goog-Upload-Protocol",
                    "resumable"
                )
                .header(
                    "X-Goog-Upload-Command",
                    "start"
                )
                .header(
                    "X-Goog-Upload-Header-Content-Length",
                    audioFile.length().toString()
                )
                .header(
                    "X-Goog-Upload-Header-Content-Type",
                    mimeType
                )
                .header(
                    "Content-Type",
                    "application/json"
                )
                .build()

            val startResponse =
                executeWithRetry(startRequest, "شروع آپلود صوت Gemini")

            if (!startResponse.isSuccessful) {

                val error =
                    startResponse.body?.string()

                Log.e(
                    TAG,
                    "Upload start error: ${startResponse.code} - $error"
                )

                return@withContext Result.failure(
                    Exception(
                        if (startResponse.code == 503 || startResponse.code == 502 || startResponse.code == 500 || startResponse.code == 504) {
                            "سرویس Gemini هنگام شروع آپلود صوت در دسترس نیست (کد ${startResponse.code}). فایل اصلی حذف نشده است؛ کمی بعد دوباره تلاش کنید یا از Groq استفاده کنید."
                        } else {
                            "خطا در شروع آپلود فایل صوتی: ${startResponse.code}"
                        }
                    )
                )
            }

            val resumableUploadUrl =
                startResponse.header(
                    "X-Goog-Upload-URL"
                )

            if (resumableUploadUrl.isNullOrBlank()) {
                return@withContext Result.failure(
                    Exception(
                        "آدرس آپلود فایل صوتی دریافت نشد."
                    )
                )
            }

            report("در حال ارسال فایل صوتی",8,total=audioFile.length(),detail="فایل در حال ارسال به سرور است")
            val uploadStartedAt=System.currentTimeMillis()
            var last=-1
            val uploadBody=ProgressRequestBody(audioFile,mimeType.toMediaType()){sent,total->val p=if(total>0)((sent*100)/total).toInt() else 0;if(p!=last||sent==total){last=p;val el=(System.currentTimeMillis()-uploadStartedAt).coerceAtLeast(1);val eta=if(sent>0&&total>sent)((total-sent)*el/sent)else null;report("در حال ارسال فایل صوتی",8+p*42/100,sent,total,eta,"ارسال $p% فایل")}}
            val uploadRequest = Request.Builder()
                .url(resumableUploadUrl)
                .post(uploadBody)
                .header(
                    "X-Goog-Upload-Offset",
                    "0"
                )
                .header(
                    "X-Goog-Upload-Command",
                    "upload, finalize"
                )
                .build()

            val uploadResponse =
                client.newCall(uploadRequest).execute()

            val uploadResponseBody =
                uploadResponse.body?.string()

            if (!uploadResponse.isSuccessful ||
                uploadResponseBody.isNullOrBlank()
            ) {

                Log.e(
                    TAG,
                    "Upload error: ${uploadResponse.code} - $uploadResponseBody"
                )

                return@withContext Result.failure(
                    Exception(
                        "خطا در آپلود فایل صوتی: ${uploadResponse.code}"
                    )
                )
            }

            report("ارسال فایل کامل شد",52,audioFile.length(),audioFile.length(),0,"فایل با موفقیت دریافت شد")
            val uploadJson =
                JSONObject(uploadResponseBody)

            val fileObject =
                uploadJson.optJSONObject("file")

            if (fileObject == null) {
                return@withContext Result.failure(
                    Exception(
                        "اطلاعات فایل صوتی دریافت نشد."
                    )
                )
            }

            val fileUri =
                fileObject.optString("uri")

            val uploadedMimeType =
                fileObject.optString(
                    "mimeType",
                    mimeType
                )

            if (fileUri.isBlank()) {
                return@withContext Result.failure(
                    Exception(
                        "شناسه فایل صوتی دریافت نشد."
                    )
                )
            }

            report("فایل دریافت شد؛ در صف پردازش",56,audioFile.length(),audioFile.length(),detail="هوش مصنوعی در حال آماده‌سازی فایل صوتی است")
            // درخواست تبدیل صوت به متن
            val prompt = """
این فایل صوتی، صدای یک استاد و یک جلسه آموزشی به زبان فارسی است.

کل فایل را با دقت به متن فارسی تبدیل کن.

قوانین:
- هیچ بخش قابل تشخیصی از صحبت را حذف نکن.
- زبان خروجی فارسی باشد.
- اصطلاحات علمی و تخصصی را با کمک بافت دقیق بنویس؛ اگر اصطلاحی روشن است، صورت معیار آن را به کار ببر. در درس‌های اصول فقه و حدیث، اصطلاحاتی مانند «خبر واحد»، «حجیت»، «تزاحم»، «تواتر لفظی»، «تواتر معنوی» و «تواتر اجمالی» را فقط وقتی ثبت کن که با صدای شنیده‌شده و بافت سازگار باشند.
- آیات قرآن، احادیث و عبارات عربی را تا حد امکان دقیق ثبت کن؛ عبارت عربی را از حافظه حدس نزن یا تکمیل نکن. اگر بخشی قابل تشخیص نیست، همان بخش را با [عبارت نامفهوم در متن خام] مشخص کن.
- هیچ بخش قابل تشخیصی از گفتار را حذف نکن.
- متن را به صورت پاراگراف‌بندی‌شده ارائه کن.
- توضیح اضافی درباره کار خودت نده.
- فقط متن پیاده‌سازی‌شده صوت را برگردان.
            """.trimIndent()

            val requestJson =
                JSONObject().apply {

                    put(
                        "contents",
                        JSONArray().apply {

                            put(
                                JSONObject().apply {

                                    put(
                                        "parts",
                                        JSONArray().apply {

                                            put(
                                                JSONObject().apply {
                                                    put(
                                                        "text",
                                                        prompt
                                                    )
                                                }
                                            )

                                            put(
                                                JSONObject().apply {

                                                    put(
                                                        "file_data",
                                                        JSONObject().apply {
                                                            put(
                                                                "mime_type",
                                                                uploadedMimeType
                                                            )
                                                            put(
                                                                "file_uri",
                                                                fileUri
                                                            )
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )

                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put(
                                "temperature",
                                0.1
                            )
                        }
                    )
                }

            val endpoint =
                "$BASE_URL/$MODEL:generateContent?key=$apiKey"

            val request =
                Request.Builder()
                    .url(endpoint)
                    .post(
                        requestJson
                            .toString()
                            .toRequestBody(
                                "application/json; charset=utf-8"
                                    .toMediaType()
                            )
                    )
                    .build()

            report("در حال تبدیل گفتار به متن",65,audioFile.length(),audioFile.length(),detail="تشخیص گفتار و استخراج متن فارسی در حال انجام است")

            val response =
                executeWithRetry(request, "تبدیل صوت به متن Gemini")

            val responseBody =
                response.body?.string()

            if (!response.isSuccessful ||
                responseBody.isNullOrBlank()
            ) {

                Log.e(
                    TAG,
                    "Transcription error: ${response.code} - $responseBody"
                )

                return@withContext Result.failure(
                    Exception(
                        if (response.code == 503 || response.code == 502 || response.code == 500 || response.code == 504) {
                            "سرویس Gemini پس از چند تلاش نتوانست صوت را به متن تبدیل کند (کد ${response.code}). فایل صوتی حفظ شده است؛ کمی بعد دوباره تلاش کنید یا از Groq استفاده کنید."
                        } else if (response.code == 429) {
                            "محدودیت درخواست Gemini فعال شده است (429). کمی بعد دوباره تلاش کنید یا از Groq استفاده کنید."
                        } else {
                            "خطا در تبدیل صوت به متن (کد ${response.code})"
                        }
                    )
                )
            }

            val json =
                JSONObject(responseBody)

            val candidates = json.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            if (firstCandidate == null) {
                val apiError = json.optJSONObject("error")?.optString("message").orEmpty()
                val blockReason = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                val detail = listOf(apiError, blockReason).filter { it.isNotBlank() }.joinToString("؛ ")
                return@withContext Result.failure(
                    IllegalStateException(
                        if (detail.isNotBlank()) "Gemini برای فایل صوتی پاسخی تولید نکرد: $detail"
                        else "Gemini برای فایل صوتی متن برنگرداند؛ پاسخ سرویس فاقد نامزد متنی بود."
                    )
                )
            }

            val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
            val textBuilder = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val text = part.optString("text").trim()
                    if (text.isNotBlank()) textBuilder.append(text)
                }
            }

            report("متن دریافت شد؛ در حال تکمیل",88,audioFile.length(),audioFile.length(),detail="متن خام دریافت شده و در حال آماده‌سازی نهایی است")
            val transcript =
                textBuilder
                    .toString()
                    .trim()

            if (transcript.isBlank()) {
                return@withContext Result.failure(
                    Exception(
                        "متن استخراج‌شده خالی است."
                    )
                )
            }

            report("تبدیل صوت به متن کامل شد",100,audioFile.length(),audioFile.length(),0,"متن با موفقیت آماده شد")
            Result.success(transcript)

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Audio transcription failed",
                e
            )

            Result.failure(e)
        }
    }
}
