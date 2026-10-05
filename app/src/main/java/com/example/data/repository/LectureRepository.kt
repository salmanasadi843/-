package com.example.data.repository

import com.example.data.local.LectureDao
import com.example.data.local.LectureEntity
import kotlinx.coroutines.flow.Flow

class LectureRepository(private val lectureDao: LectureDao) {

    val allLectures: Flow<List<LectureEntity>> = lectureDao.getAllLectures()
    val allCourses: Flow<List<String>> = lectureDao.getAllCourseNames()
    val allProfessors: Flow<List<String>> = lectureDao.getAllProfessorNames()

    fun searchLectures(query: String): Flow<List<LectureEntity>> =
        lectureDao.searchLectures(query)

    fun observeLecture(id: Long): Flow<LectureEntity?> =
        lectureDao.observeLectureById(id)

    suspend fun getLecture(id: Long): LectureEntity? =
        lectureDao.getLectureById(id)

    suspend fun saveLecture(lecture: LectureEntity): Long {
        return if (lecture.id == 0L) {
            lectureDao.insertLecture(lecture)
        } else {
            lectureDao.updateLecture(lecture)
            lecture.id
        }
    }

    suspend fun deleteLecture(id: Long) {
        lectureDao.deleteLectureById(id)
    }

    suspend fun toggleFavorite(lecture: LectureEntity) {
        lectureDao.updateLecture(lecture.copy(isFavorite = !lecture.isFavorite))
    }

    suspend fun seedInitialDataIfEmpty() {
        if (lectureDao.getLectureCount() == 0) {
            val sample1 = LectureEntity(
                title = "جلسه ۴: شبکه‌های عصبی و یادگیری عمیق",
                courseName = "هوش مصنوعی و یادگیری ماشین",
                professorName = "دکتر مهدوی",
                dateMillis = System.currentTimeMillis() - 86400000L * 2,
                audioDurationMs = 274000L,
                transcript = """
سلام و درود به همه دانشجویان عزیز. در جلسه امروز قصد داریم مبحث معماری شبکه‌های عصبی مصنوعی (Neural Networks) و نحوه انتشار رو به جلو (Forward Propagation) و پس‌انتشار خطا (Backpropagation) را با جزئیات بررسی کنیم.

همان‌طور که خاطرتان هست، پرسپترون ساده‌ترین مدل نورون مصنوعی است که ورودی‌ها را با وزن‌ها ضرب کرده و از تابع فعال‌سازی (Activation Function) مانند Sigmoid یا ReLU عبور می‌دهد. مسئله خطی نبودن داده‌ها با شبکه‌های چند لایه (MLP) حل می‌شود.

مبحث اصلی امتحان میان‌ترم شامل نحوه محاسبه گرادیان نزولی (Gradient Descent) با قاعده زنجیره‌ای دیفرانسیل خواهد بود. فرمول به‌روزرسانی وزن‌ها:
W_new = W_old - alpha * dL/dW

لطفاً تمرین برنامه‌نویسی شماره ۳ را تا آخر هفته تحویل دهید.
                """.trimIndent(),
                aiSummary = "این جلسه به آموزش بنیاد شبکه‌های عصبی چندلایه (MLP)، توابع فعال‌سازی و مکانیزم پس‌انتشار خطا با قاعده زنجیره‌ای اختصاص داشت. استاد بر اهمیت محاسبه گرادیان نزولی برای امتحان میان‌ترم و لزوم ارسال تمرین ۳ تأکید کردند.",
                aiKeyPoints = "• تعریف پرسپترون و توابع فعال‌سازی (ReLU, Sigmoid)\n• حل مسئله غیرخطی با شبکه‌های چندلایه\n• فرمول گرادیان نزولی: W_new = W_old - alpha * (dL/dW)\n• نکته امتحانی: قاعده زنجیره‌ای در پس‌انتشار خطا",
                aiQuizJson = """[
                    {"question":"ساده‌ترین واحد پردازشی در شبکه‌های عصبی چیست؟","options":["پرسپترون","کانولوشن","بایاس","ماکس‌پولینگ"],"correctIndex":0,"explanation":"پرسپترون ساده‌ترین مدل ریاضی از یک نورون بیولوژیکی است."},
                    {"question":"در به‌روزرسانی گرادیان نزولی پارامتر alpha نشان‌دهنده چیست؟","options":["نرخ یادگیری (Learning Rate)","تعداد لایه‌ها","خطای نهایی","تعداد نورون‌ها"],"correctIndex":0,"explanation":"آلفا یا Learning Rate گام حرکت در جهت منفی گرادیان است."}
                ]""",
                tags = "هوش مصنوعی,شبکه عصبی,امتحان میان‌ترم,یادگیری عمیق",
                isFavorite = true
            )

            val sample2 = LectureEntity(
                title = "جلسه ۷: سری و تبدیل فوریه و کاربرد در سیگنال",
                courseName = "ریاضی مهندسی",
                professorName = "دکتر کاظمی",
                dateMillis = System.currentTimeMillis() - 86400000L * 5,
                audioDurationMs = 195000L,
                transcript = """
در این جلسه به تشریح سری فوریه مثلثاتی و تبدیل فوریه برای توابع متناوب و غیرمتناوب می‌پردازیم. 
شرایط دیریکله تضمین می‌کند که تابع دارای سری فوریه همگرا باشد. ضرایب a0, an و bn با انتگرال‌گیری معین روی یک دوره تناوب ۲L محاسبه می‌شوند.

نکته کلیدی: برای توابع زوج ضریب bn صفر است و برای توابع فرد ضرایب a0 و an صفر می‌شوند. این نکته زمان محاسبات انتگرال در کنکور ارشد و امتحانات پایان‌ترم را به نصف کاهش می‌دهد.
                """.trimIndent(),
                aiSummary = "بررسی شرایط دیریکله، فرمول‌های محاسبه ضرایب سری فوریه (a0, an, bn) و روش‌های تقارن زوج و فرد برای ساده‌سازی انتگرال‌ها در توابع متناوب.",
                aiKeyPoints = "• شرایط دیریکله برای همگرایی سری فوریه\n• توابع زوج: فقط ضرایب an وجود دارد (bn = 0)\n• توابع فرد: فقط ضرایب bn وجود دارد (an = 0)\n• تبدیل فوریه پل ارتباطی بین حوزه زمان و فرکانس است.",
                tags = "ریاضی مهندسی,تبدیل فوریه,کنکور ارشد,فرکانس",
                isFavorite = false
            )

            lectureDao.insertLecture(sample1)
            lectureDao.insertLecture(sample2)
        }
    }
}
