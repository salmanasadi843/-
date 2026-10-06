package com.example.data.api

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.ApiKeyStore
import kotlinx.coroutines.Dispatchers
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

data class TranscriptionProgress(val stage:String,val percent:Int,val uploadedBytes:Long=0L,val totalBytes:Long=0L,val elapsedMs:Long=0L,val etaMs:Long?=null,val detail:String="")

private class ProgressRequestBody(private val file:File,private val type:okhttp3.MediaType,private val cb:(Long,Long)->Unit):okhttp3.RequestBody(){ override fun contentType()=type; override fun contentLength()=file.length(); override fun writeTo(sink:okio.BufferedSink){val total=contentLength();var sent=0L;val b=ByteArray(64*1024);file.inputStream().use{input->while(true){val n=input.read(b);if(n<=0)break;sink.write(b,0,n);sent+=n;cb(sent,total)}}}}

object GeminiApiService {
    private const val TAG = "GeminiApiService"
    private const val MODEL = "gemini-3.5-flash"
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
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

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

            report("در حال تبدیل گفتار به متن",65,audioFile.length(),audioFile.length(),detail="تشخیص گفتار و استخراج متن فارسی در حال انجام است")
            val response =
                client.newCall(request).execute()

            val responseBody =
                response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                Log.e(
                    TAG,
                    "Gemini API error: ${response.code} - $responseBody"
                )

                return@withContext Result.failure(
                    Exception(
                        "خطا از سرور هوش مصنوعی (کد ${response.code})"
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

        val systemPrompt =
            "تو یک استادیار و دستیار دانشگاهی هوشمند هستی. وظیفه تو تحلیل متن پیاده‌شده کلاس درس دانشگاهی و نگارش خلاصه جامع، دقیق و روان به زبان فارسی آکادمیک است."

        val prompt = """
متن کلاس درس زیر را تحلیل کن و یک خلاصه جامع، روان و ساختارمند شامل:
۱. موضوع و هدف کلی درس
۲. مباحث و فصول بررسی شده در این جلسه
۳. نتایج و نکات امتحانی
ارائه بده.

عنوان جلسه: $lectureTitle

متن کلاس:
$transcript
        """.trimIndent()

        return generateContent(
            prompt,
            systemPrompt
        )
    }

    suspend fun extractKeyPoints(
        transcript: String
    ): Result<String> {

        val systemPrompt =
            "تو یک دستیار تحصیلی برای دانشجویان برتر هستی که نکات کلیدی، فرمول‌ها، تعاریف اصطلاحات و تله‌های امتحانی را با بالت پوینت دقیق استخراج می‌کنی."

        val prompt = """
از متن کلاس درس زیر، مهم‌ترین «نکات کلیدی»، «فرمول‌ها یا معادلات»، «تعاریف اصلی» و «نکات پر تکرار امتحانی» را به صورت لیست گلوله‌ای (Bullet Points) شیک و کاربردی استخراج کن:

متن کلاس:
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

        val systemPrompt =
            "تو یک ویراستار متون علمی و دانشگاهی هستی. متون پیاده‌شده از صوت را به متنی شسته و رفته، با پاراگراف‌بندی، نشانه‌گذاری صحیح (نقطه، ویرگول) و تیترهای فرعی تبدیل می‌کنی."

        val prompt = """
متن پیاده‌شده خام زیر از صوت استاد را ویرایش کن. واژه‌های پرکننده عامیانه و تپق‌ها را حذف کن، غلط‌های املایی صوتی را تصحیح کن، علائم نگارشی را قرار بده و با تیترهای مشخص، متن را به یک جزوه تمیز و خواندنی تبدیل کن:

متن خام:
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
                client.newCall(startRequest).execute()

            if (!startResponse.isSuccessful) {

                val error =
                    startResponse.body?.string()

                Log.e(
                    TAG,
                    "Upload start error: ${startResponse.code} - $error"
                )

                return@withContext Result.failure(
                    Exception(
                        "خطا در شروع آپلود فایل صوتی: ${startResponse.code}"
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
- اصطلاحات علمی و تخصصی را دقیق بنویس.
- آیات قرآن، احادیث و عبارات عربی را تا حد امکان صحیح ثبت کن.
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

            val response =
                client.newCall(request).execute()

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
                        "خطا در تبدیل صوت به متن (کد ${response.code})"
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
