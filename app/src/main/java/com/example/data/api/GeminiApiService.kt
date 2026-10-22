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

            val candidates =
                jsonObject.optJSONArray("candidates")

            if (candidates == null || candidates.length() == 0) {
                return@withContext Result.failure(
                    Exception(
                        "پاسخی از هوش مصنوعی دریافت نشد"
                    )
                )
            }

            val firstCandidate =
                candidates.getJSONObject(0)

            val content =
                firstCandidate.getJSONObject("content")

            val parts =
                content.getJSONArray("parts")

            val textBuilder =
                StringBuilder()

            for (i in 0 until parts.length()) {
                val part =
                    parts.getJSONObject(i)

                textBuilder.append(
                    part.optString("text", "")
                )
            }

            val resultText =
                textBuilder.toString().trim()

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
            تو دستیار پژوهش علمیِ امانت‌دار برای درس‌های دانشگاهی و حوزوی هستی.
            ممکن است متن ورودی شامل یک جلسه یا چند جلسه باشد. ابتدا ساختار و مرز جلسات را از نشانه‌های موجود در خود متن تشخیص بده؛ اگر مرزها مشخص نیستند، جلسه یا منبع تازه‌ای فرض نکن.
            خروجی باید شامل «چکیده علمی»، «خلاصه تفصیلی»، «نسبت مطالب و دیدگاه‌ها»، و «ملاحظات منبع و ابهام» باشد.
            فقط بر اساس متن ورودی کار کن. دانش عمومی را برای تکمیل استدلال، بازسازی آیات و احادیث مخدوش، افزودن منابع یا حل ابهام‌های متن به کار نبر.
            بیان صریح استاد، دیدگاه نقل‌شده از دیگری، پرسش یا احتمال، اشکال مطرح‌شده، پاسخ ارائه‌شده و استنباط ترکیبی هوش مصنوعی را از هم جدا کن. هیچ‌کدام را بی‌دلیل به دیگری تبدیل نکن.
            مسیر استدلال را حفظ کن: مسئله، مقدمات، تعریف‌ها، ادعا، دلیل، مثال، اشکال، پاسخ، قید و نتیجه. تکرار لفظیِ محض را می‌توان فشرده کرد، اما تکرار آموزشی یا استدلالی را حذف نکن.
            در ترکیب چند جلسه، مشخص کن کدام مطالب مشترک، مکمل، متفاوت یا ناسازگارند. ناسازگاری ظاهری را خودسرانه حل نکن و تفاوت را به‌عنوان مسئله‌ای نیازمند بررسی نشان بده.
            متن ورودی با شناسه‌های ماشینی قطعه منبع مانند [P001] نشانه‌گذاری شده است. برای هر ادعای محوری یا نتیجه مهم، شناسه قطعه‌ای را که واقعاً آن را پشتیبانی می‌کند درج کن؛ برای ادعای چندبخشی چند شناسه بده. فقط از شناسه‌های موجود در متن ورودی استفاده کن و هیچ شماره جلسه، پاراگراف اصلی، تاریخ، صفحه، نقل‌قول یا شناسه تازه‌ای نساز. اگر ادعا از قطعه‌های موجود پشتیبانی نمی‌شود، آن را به منبع نسبت نده و محدودیت را صریح بگو.
            اگر متن خام از نظر بازشناسی صوت مخدوش است، عبارت علمی یا عربی را از روی حدس بازسازی نکن؛ همان ابهام را مشخص کن و نتیجه وابسته به آن را قطعی ندان.
            هیچ نتیجه‌ای را که از متن پشتیبانی نمی‌شود به استاد نسبت نده. نتیجه‌گیری ترکیبی خودت را با برچسب «استنباط ترکیبی هوش مصنوعی» مشخص کن.
            فارسی رسمی و روشن بنویس؛ از حاشیه‌پردازی، نکات امتحانی، پرسش ساختگی، توصیه آموزشی و منابع بیرونی استفاده نکن.
        """.trimIndent()

        val sourceText = LectureSourceFormatter.withSourceAnchors(transcript)
        val prompt = """
            عنوان یا موضوع ارائه‌شده: $lectureTitle

            بر اساس متن منبع زیر، یک گزارش علمیِ قابل بررسی تولید کن. اگر متن چند جلسه دارد، همه جلسات را در کنار هم تحلیل کن، نه اینکه صرفاً خلاصه‌های جداگانه را پشت سر هم قرار بدهی.

            ۱. چکیده علمی
            - مسئله اصلی، مسیر کلی بحث و نتیجه‌ای را که واقعاً از متن به دست می‌آید در یک یا دو بند بیان کن.
            - اگر متن نتیجه قطعی ندارد، این محدودیت را صریح نگه دار.

            ۲. خلاصه تفصیلی و بازسازی استدلال
            - با تیترهای دقیق، مباحث را منظم کن.
            - برای هر محور مهم، تا حد امکان این ترتیب را حفظ کن: مسئله، تعریف یا مقدمه، ادعا، دلیل، مثال، اشکال، پاسخ و نتیجه.
            - مثال‌ها و قیود لازم برای فهم استدلال را نگه دار؛ تنها تکرار لفظیِ زائد را فشرده کن.

            ۳. نسبت مطالب و دیدگاه‌ها
            - موارد را در صورت وجود با برچسب‌های روشن مشخص کن: «بیان مستقیم استاد»، «دیدگاه نقل‌شده»، «اشکال یا احتمال»، «پرسش حل‌نشده» و «استنباط ترکیبی هوش مصنوعی».
            - در ترکیب چند جلسه، اشتراک‌ها، تکمیل یکدیگر و تفاوت‌ها یا تعارض‌های احتمالی را جداگانه بیان کن.
            - اگر نسبت دادن یک گزاره به استاد از متن روشن نیست، آن را به‌صورت قطعی به او نسبت نده.

            ۴. ملاحظات منبع و ابهام
            - برای هر ادعای محوری یا نتیجه مهم، شناسه قطعه پشتیبان را دقیقاً با قالب [P001] و فقط از شناسه‌های واقعاً موجود در ورودی درج کن.
            - شناسه قطعه را با شماره پاراگراف اصلی اشتباه نگیر؛ [P001] فقط به قطعه نشانه‌گذاری‌شده همین ورودی اشاره دارد. هیچ شناسه‌ای مانند [S1] یا شماره پاراگراف ساختگی نساز.
            - اگر ادعایی پشتوانه مشخصی در قطعه‌ها ندارد، آن را به استاد نسبت نده و محدودیت شواهد را بنویس.
            - اصطلاحات، آیات، احادیث و نام‌های مخدوش را حدس نزن. مواردی را که بر اثر خرابی متن قابل اطمینان نیستند، مشخص کن.
            - میان «در متن موجود بحث نشده» و «در متن در دسترس پیدا نشد» تفاوت بگذار.

            قواعد نهایی:
            - هیچ منبع، نقل‌قول، مثال، مقدمه، استدلال یا نتیجه تازه‌ای اضافه نکن.
            - مطالب مشترک را ادغام کن، اما منشأ دیدگاه‌ها و تفاوت جلسات را تا حد ممکن حفظ کن.
            - خروجی باید خلاصه‌ای علمی و قابل پیگیری باشد، نه صرفاً متنی روان و قاطع.

            متن منبع نشانه‌گذاری‌شده:
            $sourceText
        """.trimIndent()

        return generateContent(
            prompt,
            systemPrompt
        )
    }

    suspend fun extractKeyPoints(
        transcript: String
    ): Result<String> {

        val systemPrompt = """
تو استخراج‌کننده کلیدواژه برای جزوه‌های علمی هستی.
فقط اصطلاحات تخصصی، مفاهیم محوری و نام‌های علمیِ واقعاً موجود در متن را کوتاه و بدون توضیح فهرست کن.
هیچ نکته امتحانی، سؤال، تله امتحانی، فرمول افزوده، تعریف توضیحی یا مطلبی بیرون از متن تولید نکن.
از تکرار مترادف‌ها و عبارت‌های عمومی پرهیز کن.
        """.trimIndent()

        val prompt = """
از متن زیر فقط کلیدواژه‌ها و اصطلاحات محوری را استخراج کن.
خروجی شامل عنوان «کلیدواژه‌ها» و سپس فهرستی کوتاه از واژه‌ها یا عبارت‌های تخصصی باشد؛ برای هر مورد توضیح ننویس.
کلیدواژه‌ها را از متن جزوه منظم‌شده استخراج کن؛ اگر متن ورودی جزوه است، صرفاً واژه‌ها و مفاهیم موجود در همان متن را انتخاب کن.
کلیدواژه‌ها باید از خود متن گرفته شده باشند و با موضوع جزوه تناسب داشته باشند. نکات امتحانی، جمع‌بندی طولانی و تعریف‌های چندجمله‌ای ننویس.

متن:
$transcript
        """.trimIndent()

        return generateContent(
            prompt,
            systemPrompt
        )
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
            تو ویراستار وفادارِ متن پیاده‌سازی‌شده از سخنرانی هستی، نه نویسنده یا شارح مستقل.
            این ورودی ممکن است فقط یک قطعه از متن بلند یک جلسه باشد؛ آن را متن مستقل تلقی نکن.
            فقط خطاهای روشنِ نگارشی، نشانه‌گذاری، فاصله‌گذاری و جمله‌بندی را اصلاح کن. تبدیل محاوره به فارسی رسمی فقط تا جایی مجاز است که معنا با اطمینان از متن فهمیده شود.
            هیچ مقدمه، نتیجه‌گیری، جمع‌بندی، پرسش‌وپاسخ، نکته آموزشی، مثال، تعریف، استدلال، تفسیر فلسفی یا عنوان تازه‌ای تولید نکن.
            اصطلاحات علمی و حوزوی را با توجه به موضوع و جمله اصلاح کن، اگر صورت درست آن از بافت به‌روشنی قابل تشخیص است. اگر بحث درباره اصول فقه و حجیت خبر یا ظن است، به صورت‌های معیار این اصطلاحات توجه کن: ادله حجیت، ادله ناهیه از عمل به ظن، خبر واحد، خبر ظنی، حجیت خبر، کشف از واقع، وصول به واقع، تنافی ادله، جمع عرفی، تخصیص، حکومت، اطلاق، تقیید، آیات ناهیه از عمل به ظن، تواتر لفظی، تواتر معنوی، تواتر اجمالی، علم اجمالی، منجّزیت و معذّریت. این واژه‌نامه فقط راهنمای تشخیص است؛ اصطلاحی را که با گفتار و بافت سازگار نیست جایگزین نکن.
            عبارات عربی را از نظر رسم‌الخط اصلاح کن، اما متن حدیث یا آیه را از حافظه بازسازی نکن و واژه‌ای به آن نیفزا. عبارت شناخته‌شده را فقط وقتی اصلاح کن که خود متن و بافت شواهد کافی بدهند. اگر واژه یا عبارت عربی واقعاً قابل تشخیص نیست، فقط همان قسمت را با [عبارت نامفهوم در متن خام] مشخص کن؛ بخش‌های قابل فهم اطراف را نامفهوم علامت نزن.
            هیچ ادعایی را از روی دانش عمومی تکمیل نکن و هیچ واژهٔ نامفهومی را با حدس آوایی به اصطلاح تخصصی تبدیل نکن.
            اگر عبارتی نامفهوم است، فقط همان قسمت را با برچسب [عبارت نامفهوم در متن خام] مشخص کن؛ جمله‌های اطراف را بر اساس حدس بازسازی نکن.
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
            ۶. گفتار را به نثر خواناتر تبدیل کن، اما معنا را بازسازی یا تفسیر نکن.
            ۷. متن را کوتاه نکن و بخش‌های متفاوت را با هم ادغام نکن.
            ۸. خروجی فقط متن ویرایش‌شدهٔ همین قطعه باشد، بدون توضیح دربارهٔ کار انجام‌شده.

            متن ورودی:
            $rawTranscript
        """.trimIndent()

        return generateContent(
            prompt,
            systemPrompt
        )
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

            val candidates =
                json.optJSONArray("candidates")

            if (candidates == null ||
                candidates.length() == 0
            ) {

                return@withContext Result.failure(
                    Exception(
                        "هوش مصنوعی متنی برای فایل صوتی برنگرداند."
                    )
                )
            }

            val parts =
                candidates
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")

            val textBuilder =
                StringBuilder()

            for (i in 0 until parts.length()) {

                val text =
                    parts
                        .getJSONObject(i)
                        .optString("text")

                if (text.isNotBlank()) {
                    textBuilder.append(text)
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
