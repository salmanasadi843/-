package com.example.data.api

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiApiService {
    private const val TAG = "GeminiApiService"
    private const val MODEL = "gemini-3.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun getApiKey(): String {
        return try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun generateContent(prompt: String, systemPrompt: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext Result.failure(
                IllegalStateException("کلید هوش مصنوعی (GEMINI_API_KEY) در فایل تنظیمات مشخص نشده است. لطفاً کلید معتبر را در بخش Secrets وارد کنید.")
            )
        }

        try {
            val endpoint = "$BASE_URL/$MODEL:generateContent?key=$apiKey"

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
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", systemPrompt)
                            })
                        })
                    })
                }
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.4)
                    put("topP", 0.9)
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = requestJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                Log.e(TAG, "Gemini API error: ${response.code} - $responseBody")
                return@withContext Result.failure(Exception("خطا از سرور هوش مصنوعی (کد ${response.code})"))
            }

            val jsonObject = JSONObject(responseBody)
            val candidates = jsonObject.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return@withContext Result.failure(Exception("پاسخی از هوش مصنوعی دریافت نشد"))
            }

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.getJSONObject("content")
            val parts = content.getJSONArray("parts")
            val textBuilder = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                textBuilder.append(part.optString("text", ""))
            }

            val resultText = textBuilder.toString().trim()
            Result.success(resultText)
        } catch (e: Exception) {
            Log.e(TAG, "Request failed", e)
            Result.failure(e)
        }
    }

    suspend fun summarizeLecture(transcript: String, lectureTitle: String): Result<String> {
        val systemPrompt = "تو یک استادیار و دستیار دانشگاهی هوشمند هستی. وظیفه تو تحلیل متن پیاده‌شده کلاس درس دانشگاهی و نگارش خلاصه جامع، دقیق و روان به زبان فارسی آکادمیک است."
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
        return generateContent(prompt, systemPrompt)
    }

    suspend fun extractKeyPoints(transcript: String): Result<String> {
        val systemPrompt = "تو یک دستیار تحصیلی برای دانشجویان برتر هستی که نکات کلیدی، فرمول‌ها، تعاریف اصطلاحات و تله‌های امتحانی را با بالت پوینت دقیق استخراج می‌کنی."
        val prompt = """
از متن کلاس درس زیر، مهم‌ترین «نکات کلیدی»، «فرمول‌ها یا معادلات»، «تعاریف اصلی» و «نکات پر تکرار امتحانی» را به صورت لیست گلوله‌ای (Bullet Points) شیک و کاربردی استخراج کن:

متن کلاس:
$transcript
        """.trimIndent()
        return generateContent(prompt, systemPrompt)
    }

    suspend fun generateQuiz(transcript: String): Result<String> {
        val systemPrompt = "تو یک طراح سوالات امتحانی دانشگاهی هستی. پاسخ را منحصراً در قالب یک آرایه JSON معتبر بازگردان."
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
        return generateContent(prompt, systemPrompt)
    }

    suspend fun askLectureQuestion(transcript: String, question: String): Result<String> {
        val systemPrompt = "تو استادیار و معلم خصوصی این درس هستی. فقط بر مبنای مباحث تدریس شده در این جلسه کلاس درس و با لحنی دلسوزانه و علمی به سوال دانشجو پاسخ بده. اگر موضوعی در متن کلاس مطرح نشده، این نکته را قید کن."
        val prompt = """
متن جلسه کلاس:
$transcript

سوال دانشجو:
$question
        """.trimIndent()
        return generateContent(prompt, systemPrompt)
    }

    suspend fun polishTranscript(rawTranscript: String): Result<String> {
        val systemPrompt = "تو یک ویراستار متون علمی و دانشگاهی هستی. متون پیاده‌شده از صوت را به متنی شسته و رفته، با پاراگراف‌بندی، نشانه‌گذاری صحیح (نقطه، ویرگول) و تیترهای فرعی تبدیل می‌کنی."
        val prompt = """
متن پیاده‌شده خام زیر از صوت استاد را ویرایش کن. واژه‌های پرکننده عامیانه و تپق‌ها را حذف کن، غلط‌های املایی صوتی را تصحیح کن، علائم نگارشی را قرار بده و با تیترهای مشخص، متن را به یک جزوه تمیز و خواندنی تبدیل کن:

متن خام:
$rawTranscript
        """.trimIndent()
        return generateContent(prompt, systemPrompt)
    }
}
