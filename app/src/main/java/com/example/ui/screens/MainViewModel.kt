package com.example.ui.screens

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPlayerManager
import com.example.audio.AudioRecorderManager
import com.example.audio.SpeechRecognizerHelper
import com.example.data.api.GeminiApiService
import com.example.data.api.GroqApiService
import com.example.data.api.SpeechmaticsApiService
import com.example.data.api.TranscriptionProgress
import com.example.data.local.AppDatabase
import com.example.data.local.LectureEntity
import com.example.data.repository.LectureRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

sealed class Screen {
    object Home : Screen()
    object Classes : Screen()
    data class ClassDetail(val classId: Long) : Screen()
    data class Detail(val lectureId: Long) : Screen()
    data class AddEdit(val lectureId: Long? = null, val classId: Long? = null) : Screen()
    data class AiStudy(val lectureId: Long) : Screen()
    object Settings : Screen()
}

data class ChatMessage(
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageSender {
    USER, AI
}

data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String
)

enum class UserRole { TEACHER, STUDENT }

data class FilterCriteria(
    val query: String = "",
    val course: String? = null,
    val professor: String? = null,
    val tag: String? = null,
    val onlyAudio: Boolean = false,
    val onlyFav: Boolean = false,
    val onlyAi: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MainViewModel"

    init {
        GeminiApiService.configure(application)
        GroqApiService.configure(application)
        SpeechmaticsApiService.configure(application)
        GroqApiService.configure(application)
        SpeechmaticsApiService.configure(application)
    }

    private val rolePrefs = application.getSharedPreferences("ostadyar_role", Context.MODE_PRIVATE)
    private val _userRole = MutableStateFlow(
        if (rolePrefs.getString("role", "TEACHER") == "STUDENT") UserRole.STUDENT else UserRole.TEACHER
    )
    val userRole: StateFlow<UserRole> = _userRole.asStateFlow()

    fun setUserRole(role: UserRole) {
        _userRole.value = role
        rolePrefs.edit().putString("role", role.name).apply()
    }

    private val database = AppDatabase.getDatabase(application)
    val repository = LectureRepository(database.lectureDao(), database.classDao(), database.courseDao())

    val audioPlayer = AudioPlayerManager(application)
    val audioRecorder = AudioRecorderManager(application)
    val speechRecognizer = SpeechRecognizerHelper(application)

    // Navigation State
    private val _currentScreen = MutableStateFlow<Screen>(Screen.Home)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // Unified Filter Criteria State
    private val _filterCriteria = MutableStateFlow(FilterCriteria())

    val searchQuery: StateFlow<String> = _filterCriteria
        .combine(MutableStateFlow(Unit)) { f, _ -> f.query }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ""
        )

    val selectedCourseFilter: StateFlow<String?> = _filterCriteria
        .combine(MutableStateFlow(Unit)) { f, _ -> f.course }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null
        )

    val selectedProfessorFilter: StateFlow<String?> = _filterCriteria
        .combine(MutableStateFlow(Unit)) { f, _ -> f.professor }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null
        )

    val selectedTagFilter: StateFlow<String?> = _filterCriteria
        .combine(MutableStateFlow(Unit)) { f, _ -> f.tag }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null
        )

    val filterOnlyWithAudio: StateFlow<Boolean> = _filterCriteria
        .combine(MutableStateFlow(Unit)) { f, _ -> f.onlyAudio }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false
        )

    val filterOnlyFavorites: StateFlow<Boolean> = _filterCriteria
        .combine(MutableStateFlow(Unit)) { f, _ -> f.onlyFav }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false
        )

    val filterOnlyWithAiSummary: StateFlow<Boolean> = _filterCriteria
        .combine(MutableStateFlow(Unit)) { f, _ -> f.onlyAi }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false
        )

    // Active Lecture for Detail / Edit
    private val _selectedLecture = MutableStateFlow<LectureEntity?>(null)
    val selectedLecture: StateFlow<LectureEntity?> =
        _selectedLecture.asStateFlow()

    // AI Operation States
    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading: StateFlow<Boolean> =
        _isAiLoading.asStateFlow()

    private val _aiOperationTitle = MutableStateFlow("")
    val aiOperationTitle: StateFlow<String> =
        _aiOperationTitle.asStateFlow()

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> =
        _aiError.asStateFlow()

    private val _transcriptionProgress = MutableStateFlow<TranscriptionProgress?>(null)
    val transcriptionProgress: StateFlow<TranscriptionProgress?> = _transcriptionProgress.asStateFlow()

    private val _chatMessages =
        MutableStateFlow<List<ChatMessage>>(emptyList())

    val chatMessages: StateFlow<List<ChatMessage>> =
        _chatMessages.asStateFlow()

    private val _parsedQuiz =
        MutableStateFlow<List<QuizQuestion>>(emptyList())

    val parsedQuiz: StateFlow<List<QuizQuestion>> =
        _parsedQuiz.asStateFlow()

    private val _quizSelectedAnswers =
        MutableStateFlow<Map<Int, Int>>(emptyMap())

    val quizSelectedAnswers: StateFlow<Map<Int, Int>> =
        _quizSelectedAnswers.asStateFlow()

    // Add/Edit Lecture Form State
    val formTitle = MutableStateFlow("")
    val formDateText = MutableStateFlow("")
    val formCourse = MutableStateFlow("")
    val formCourseId = MutableStateFlow<Long?>(null)
    val formClassId = MutableStateFlow<Long?>(null)
    val formProfessor = MutableStateFlow("")
    val formTags = MutableStateFlow("")
    val formTranscript = MutableStateFlow("")
    val formAudioPath = MutableStateFlow<String?>(null)
    val formAudioUrl = MutableStateFlow("")
    val formAudioDurationMs = MutableStateFlow(0L)
    private var editingLectureId: Long? = null

    init {
        GeminiApiService.configure(application)

        viewModelScope.launch {
            repository.seedInitialDataIfEmpty()
        }
    }

    // Filtered lectures
    val lectures: StateFlow<List<LectureEntity>> =
        repository.allLectures
            .combine(_filterCriteria) { all, filter ->
                all.filter { lecture ->

                    val matchQuery =
                        if (filter.query.isBlank()) {
                            true
                        } else {
                            lecture.title.contains(
                                filter.query,
                                ignoreCase = true
                            ) ||
                            lecture.courseName.contains(
                                filter.query,
                                ignoreCase = true
                            ) ||
                            lecture.professorName.contains(
                                filter.query,
                                ignoreCase = true
                            ) ||
                            lecture.tags.contains(
                                filter.query,
                                ignoreCase = true
                            ) ||
                            lecture.transcript.contains(
                                filter.query,
                                ignoreCase = true
                            ) ||
                            (
                                lecture.aiSummary?.contains(
                                    filter.query,
                                    ignoreCase = true
                                ) == true
                            )
                        }

                    val matchCourse =
                        filter.course == null ||
                        lecture.courseName.equals(
                            filter.course,
                            ignoreCase = true
                        )

                    val matchProf =
                        filter.professor == null ||
                        lecture.professorName.equals(
                            filter.professor,
                            ignoreCase = true
                        )

                    val matchTag =
                        filter.tag == null ||
                        lecture.tags
                            .split(",")
                            .map { it.trim() }
                            .any {
                                it.equals(
                                    filter.tag,
                                    ignoreCase = true
                                )
                            }

                    val matchAudio =
                        !filter.onlyAudio ||
                        !lecture.audioFilePath.isNullOrBlank()

                    val matchFav =
                        !filter.onlyFav ||
                        lecture.isFavorite

                    val matchAi =
                        !filter.onlyAi ||
                        !lecture.aiSummary.isNullOrBlank()

                    matchQuery &&
                            matchCourse &&
                            matchProf &&
                            matchTag &&
                            matchAudio &&
                            matchFav &&
                            matchAi
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    // All distinct tags
    val allTags: StateFlow<List<String>> =
        repository.allLectures
            .combine(_filterCriteria) { list, _ ->
                list.flatMap { it.tags.split(",") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    fun navigateTo(screen: Screen) {

        if (screen is Screen.Detail) {
            loadLecture(screen.lectureId)

        } else if (screen is Screen.AiStudy) {
            loadLecture(screen.lectureId)

        } else if (screen is Screen.AddEdit) {
            initForm(screen.lectureId, classId = screen.classId)
        }

        _currentScreen.value = screen
    }

    fun navigateBack() {
        when (val screen = _currentScreen.value) {
            is Screen.AiStudy -> {
                _currentScreen.value = Screen.Detail(screen.lectureId)
            }
            Screen.Classes -> {
                _currentScreen.value = Screen.Home
            }
            is Screen.ClassDetail -> {
                _currentScreen.value = Screen.Classes
            }
            is Screen.Detail -> {
                audioPlayer.stop()
                speechRecognizer.stopListening()
                viewModelScope.launch {
                    val lecture = repository.getLecture(screen.lectureId)
                    val classId = lecture?.classId ?: lecture?.courseId?.let { courseId ->
                        repository.getCourse(courseId)?.classId
                    }
                    _currentScreen.value = classId?.let { Screen.ClassDetail(it) } ?: Screen.Home
                }
            }
            is Screen.AddEdit -> {
                audioPlayer.stop()
                speechRecognizer.stopListening()
                if (screen.classId != null) {
                    _currentScreen.value = Screen.ClassDetail(screen.classId)
                } else if (screen.lectureId != null) {
                    viewModelScope.launch {
                        val lecture = repository.getLecture(screen.lectureId)
                        _currentScreen.value = lecture?.classId?.let { Screen.ClassDetail(it) } ?: Screen.Home
                    }
                } else {
                    _currentScreen.value = Screen.Home
                }
            }
            Screen.Settings -> {
                _currentScreen.value = Screen.Home
            }
            Screen.Home -> {
                // Keep the app open on Home.
            }
        }
    }

    fun openClasses() { _currentScreen.value = Screen.Classes }

    fun openClass(classId: Long) { _currentScreen.value = Screen.ClassDetail(classId) }

    fun openCourse(courseId: Long) { /* Legacy compatibility: course screens are no longer part of the user flow. */ }

    fun saveClass(item: com.example.data.local.ClassEntity, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch { onSaved(repository.saveClass(item)) }
    }

    fun saveCourse(item: com.example.data.local.CourseEntity, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch { onSaved(repository.saveCourse(item)) }
    }

    fun deleteClass(item: com.example.data.local.ClassEntity, onDeleted: () -> Unit = {}) {
        viewModelScope.launch { repository.deleteClass(item); onDeleted() }
    }

    fun deleteCourse(item: com.example.data.local.CourseEntity, onDeleted: () -> Unit = {}) {
        viewModelScope.launch { repository.deleteCourse(item); onDeleted() }
    }

    fun setSearchQuery(query: String) {
        _filterCriteria.value =
            _filterCriteria.value.copy(query = query)
    }

    fun setCourseFilter(course: String?) {

        val current = _filterCriteria.value.course

        _filterCriteria.value =
            _filterCriteria.value.copy(
                course =
                    if (current == course) null
                    else course
            )
    }

    fun setProfessorFilter(prof: String?) {

        val current = _filterCriteria.value.professor

        _filterCriteria.value =
            _filterCriteria.value.copy(
                professor =
                    if (current == prof) null
                    else prof
            )
    }

    fun setTagFilter(tag: String?) {

        val current = _filterCriteria.value.tag

        _filterCriteria.value =
            _filterCriteria.value.copy(
                tag =
                    if (current == tag) null
                    else tag
            )
    }

    fun toggleFilterOnlyWithAudio() {
        _filterCriteria.value =
            _filterCriteria.value.copy(
                onlyAudio = !_filterCriteria.value.onlyAudio
            )
    }

    fun toggleFilterOnlyFavorites() {
        _filterCriteria.value =
            _filterCriteria.value.copy(
                onlyFav = !_filterCriteria.value.onlyFav
            )
    }

    fun toggleFilterOnlyWithAiSummary() {
        _filterCriteria.value =
            _filterCriteria.value.copy(
                onlyAi = !_filterCriteria.value.onlyAi
            )
    }

    fun clearAllFilters() {
        _filterCriteria.value = FilterCriteria()
    }

    fun loadLecture(id: Long) {

        viewModelScope.launch {

            val lecture = repository.getLecture(id)

            _selectedLecture.value = lecture

            lecture?.let {

                parseQuizJson(it.aiQuizJson)

                _chatMessages.value =
                    listOf(
                        ChatMessage(
                            sender = MessageSender.AI,
                            text =
                                "سلام! من استادیار هوشمند کلاس «${it.title}» هستم. " +
                                "هر سوالی درباره مباحث تدریس شده، فرمول‌ها یا نکات این جلسه داری بپرس تا برات توضیح بدم."
                        )
                    )
            }
        }
    }

    private fun initForm(lectureId: Long?, classId: Long? = null) {
        editingLectureId = lectureId

        if (lectureId != null) {

            viewModelScope.launch {

                val lecture =
                    repository.getLecture(lectureId)

                if (lecture != null) {

                    formTitle.value = lecture.title
                    formDateText.value = PersianDateUtils.format(lecture.dateMillis)
                    formCourse.value = lecture.courseName
                    formCourseId.value = lecture.courseId
                    formClassId.value = lecture.classId
                    formProfessor.value = lecture.professorName
                    formTags.value = lecture.tags
                    formTranscript.value = lecture.transcript
                    formAudioPath.value = lecture.audioFilePath
                    formAudioDurationMs.value =
                        lecture.audioDurationMs
                }
            }

        } else {

            formTitle.value = ""
            formDateText.value = PersianDateUtils.format(System.currentTimeMillis())
            formCourse.value = ""
            formCourseId.value = null
            formClassId.value = classId
            formProfessor.value = ""
            if (classId != null) {
                viewModelScope.launch {
                    repository.getClass(classId)?.let { clazz ->
                        formClassId.value = clazz.id
                        formProfessor.value = clazz.teacherName
                    }
                }
            }
            formTags.value = ""
            formTranscript.value = ""
            formAudioPath.value = null
            formAudioUrl.value = ""
            formAudioDurationMs.value = 0L
        }

        speechRecognizer.clearSegments()
    }

    fun saveCurrentForm(
        lectureId: Long?,
        onSaved: (Long) -> Unit
    ) {

        val title =
            formTitle.value.ifBlank {
                "جلسه بدون عنوان"
            }

        viewModelScope.launch {

            val existing =
                if (lectureId != null)
                    repository.getLecture(lectureId)
                else
                    null

            val updated =
                LectureEntity(
                    id = lectureId ?: 0L,
                    title = title,
                    courseName = formCourse.value.trim(),
                    professorName = formProfessor.value.trim(),
                    classId = formClassId.value,
                    courseId = formCourseId.value,
                    dateMillis = PersianDateUtils.parse(
                        formDateText.value,
                        existing?.dateMillis ?: System.currentTimeMillis()
                    ),
                    audioFilePath =
                        formAudioPath.value,
                    audioUrl =
                        formAudioUrl.value.trim().ifBlank { null },
                    audioDurationMs =
                        formAudioDurationMs.value,
                    transcript =
                        formTranscript.value.trim(),
                    aiSummary =
                        existing?.aiSummary,
                    aiKeyPoints =
                        existing?.aiKeyPoints,
                    aiQuizJson =
                        existing?.aiQuizJson,
                    tags =
                        formTags.value.trim(),
                    isFavorite =
                        existing?.isFavorite ?: false,
                    lastEditedMillis =
                        System.currentTimeMillis()
                )

            val newId =
                repository.saveLecture(updated)

            loadLecture(newId)

            onSaved(newId)
        }
    }

    fun toggleFavorite(lecture: LectureEntity) {

        viewModelScope.launch {

            repository.toggleFavorite(lecture)

            if (_selectedLecture.value?.id == lecture.id) {

                _selectedLecture.value =
                    _selectedLecture.value?.copy(
                        isFavorite = !lecture.isFavorite
                    )
            }
        }
    }

    fun deleteLecture(
        id: Long,
        onDeleted: () -> Unit
    ) {

        viewModelScope.launch {

            audioPlayer.stop()

            repository.deleteLecture(id)

            _selectedLecture.value = null

            onDeleted()
        }
    }


    // متن: Groq اول، Gemini در صورت خطا یا نبودن کلید Groq.
    private suspend fun aiTextWithGroqFirst(
        groqCall: suspend () -> Result<String>,
        geminiCall: suspend () -> Result<String>
    ): Result<String> {
        if (GroqApiService.hasApiKey(getApplication<Application>())) {
            val groqResult = groqCall()
            if (groqResult.isSuccess) return groqResult
            Log.w(TAG, "Groq failed; falling back to Gemini", groqResult.exceptionOrNull())
        }
        return geminiCall()
    }

    fun testGroqConnection(
        onComplete: (String?) -> Unit
    ) {
        viewModelScope.launch {
            if (!GroqApiService.hasApiKey(getApplication<Application>())) {
                onComplete("کلید Groq تنظیم نشده است.")
                return@launch
            }
            val result = GroqApiService.generateContent(
                prompt = "پاسخ را فقط با کلمه «آماده» بده."
            )
            result.onSuccess {
                onComplete(null)
            }.onFailure { error ->
                onComplete(error.localizedMessage ?: "اتصال به Groq ناموفق بود.")
            }
        }
    }

    fun testGeminiConnection(
        onComplete: (String?) -> Unit
    ) {
        viewModelScope.launch {
            val result = GeminiApiService.generateContent(
                prompt = "پاسخ را فقط با کلمه «آماده» بده."
            )

            result.onSuccess {
                onComplete(null)
            }.onFailure { error ->
                onComplete(
                    error.localizedMessage
                        ?: "اتصال به Gemini ناموفق بود."
                )
            }
        }
    }

    // ---------------------------------------------------------
    // Audio Import / File Picker
    // ---------------------------------------------------------

    fun importAudioUri(uri: Uri) {

        viewModelScope.launch(Dispatchers.IO) {

            try {

                val context =
                    getApplication<Application>()

                val audioDir =
                    File(
                        context.filesDir,
                        "imported_audio"
                    ).apply {
                        if (!exists()) mkdirs()
                    }

                val targetFile =
                    File(
                        audioDir,
                        "audio_${System.currentTimeMillis()}.m4a"
                    )

                context.contentResolver
                    .openInputStream(uri)
                    ?.use { input ->

                        FileOutputStream(targetFile)
                            .use { output ->

                                input.copyTo(output)
                            }
                    }

                var durationMs = 0L

                try {

                    val retriever =
                        MediaMetadataRetriever()

                    retriever.setDataSource(
                        targetFile.absolutePath
                    )

                    val durStr =
                        retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )

                    durationMs =
                        durStr?.toLongOrNull() ?: 0L

                    retriever.release()

                } catch (e: Exception) {

                    Log.w(
                        TAG,
                        "Could not extract audio duration",
                        e
                    )
                }

                withContext(Dispatchers.Main) {

                    formAudioPath.value =
                        targetFile.absolutePath

                    formAudioDurationMs.value =
                        durationMs
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Error importing audio file",
                    e
                )
            }
        }
    }

    fun finishRecordingAndAttach() {

        val file =
            audioRecorder.stopRecording()

        if (file != null && file.exists()) {

            formAudioPath.value =
                file.absolutePath

            formAudioDurationMs.value =
                audioRecorder.recordingDurationMs.value
        }
    }

    // ---------------------------------------------------------
    // تبدیل فایل صوتی موجود به متن با Gemini
    // ---------------------------------------------------------

    fun transcribeCurrentAudio() {
        val audioPath = formAudioPath.value
        if (audioPath.isNullOrBlank()) {
            _aiError.value = "ابتدا یک فایل صوتی ضبط یا بارگذاری کنید."
            return
        }

        val audioFile = File(audioPath)
        if (!audioFile.exists() || audioFile.length() == 0L) {
            _aiError.value = "فایل صوتی معتبر نیست."
            return
        }

        viewModelScope.launch {
            _isAiLoading.value = true
            _aiError.value = null
            val providerErrors = mutableListOf<String>()
            _transcriptionProgress.value = TranscriptionProgress(
                "شروع تبدیل صوت به متن", 0,
                totalBytes = audioFile.length(),
                detail = "اولویت: Groq، سپس Speechmatics و در پایان Gemini"
            )
            try {
                // Groq همیشه بررسی می‌شود؛ اگر کلید قابل‌خواندن نباشد علت صریح ثبت می‌شود.
                _aiOperationTitle.value = "مرحله ۱ از ۳: Groq"
                if (!GroqApiService.hasApiKey(getApplication<Application>())) {
                    val reason = "Groq: کلید API ذخیره نشده یا قابل خواندن نیست؛ وارد تنظیمات شو، کلید را ذخیره و اتصال را آزمایش کن."
                    providerErrors += reason
                    Log.w(TAG, reason)
                    _transcriptionProgress.value = TranscriptionProgress(
                        "Groq اجرا نشد", 2, totalBytes = audioFile.length(), detail = reason
                    )
                } else {
                    _transcriptionProgress.value = TranscriptionProgress(
                        "شروع درخواست Groq", 3, totalBytes = audioFile.length(),
                        detail = "کلید شناسایی شد؛ درخواست تبدیل در حال اجراست."
                    )
                    val groqResult = try {
                        GroqApiService.transcribeAudioFile(audioPath) { progress ->
                            _transcriptionProgress.value = progress
                            _aiOperationTitle.value = progress.stage
                        }
                    } catch (e: Exception) {
                        Result.failure<String>(e)
                    }
                    if (groqResult.isSuccess) {
                        saveTranscriptionResult(groqResult.getOrThrow())
                        return@launch
                    }
                    val reason = "Groq: ${groqResult.exceptionOrNull()?.localizedMessage ?: "خطای نامشخص"}"
                    providerErrors += reason
                    Log.w(TAG, reason, groqResult.exceptionOrNull())
                    _transcriptionProgress.value = TranscriptionProgress(
                        "Groq ناموفق بود؛ انتقال به سرویس بعدی", 5,
                        totalBytes = audioFile.length(), detail = reason
                    )
                }

                _aiOperationTitle.value = "مرحله ۲ از ۳: Speechmatics"
                if (!SpeechmaticsApiService.hasApiKey(getApplication<Application>())) {
                    val reason = "Speechmatics: کلید API ذخیره نشده یا قابل خواندن نیست."
                    providerErrors += reason
                    Log.w(TAG, reason)
                    _transcriptionProgress.value = TranscriptionProgress(
                        "Speechmatics اجرا نشد", 7, totalBytes = audioFile.length(), detail = reason
                    )
                } else {
                    _transcriptionProgress.value = TranscriptionProgress(
                        "شروع درخواست Speechmatics", 8, totalBytes = audioFile.length(),
                        detail = "کلید شناسایی شد؛ در حال ثبت درخواست تبدیل."
                    )
                    val speechResult = try {
                        SpeechmaticsApiService.transcribeAudioFile(audioPath) { progress ->
                            _transcriptionProgress.value = progress
                            _aiOperationTitle.value = progress.stage
                        }
                    } catch (e: Exception) {
                        Result.failure<String>(e)
                    }
                    if (speechResult.isSuccess) {
                        saveTranscriptionResult(speechResult.getOrThrow())
                        return@launch
                    }
                    val reason = "Speechmatics: ${speechResult.exceptionOrNull()?.localizedMessage ?: "خطای نامشخص"}"
                    providerErrors += reason
                    Log.w(TAG, reason, speechResult.exceptionOrNull())
                    _transcriptionProgress.value = TranscriptionProgress(
                        "Speechmatics ناموفق بود؛ انتقال به Gemini", 10,
                        totalBytes = audioFile.length(), detail = reason
                    )
                }

                _aiOperationTitle.value = "مرحله ۳ از ۳: Gemini"
                val geminiResult = try {
                    GeminiApiService.transcribeAudioFile(audioPath) { progress ->
                        _transcriptionProgress.value = progress
                        _aiOperationTitle.value = progress.stage
                    }
                } catch (e: Exception) {
                    Result.failure<String>(e)
                }

                if (geminiResult.isSuccess) {
                    saveTranscriptionResult(geminiResult.getOrThrow())
                } else {
                    val reason = "Gemini: ${geminiResult.exceptionOrNull()?.localizedMessage ?: "خطای نامشخص"}"
                    providerErrors += reason
                    Log.e(TAG, "All transcription providers failed: ${providerErrors.joinToString(" | ")}", geminiResult.exceptionOrNull())
                    _aiError.value = "تبدیل صوت انجام نشد. علت هر سرویس:\n• " + providerErrors.joinToString("\n• ")
                    _transcriptionProgress.value = TranscriptionProgress(
                        "تبدیل صوت ناموفق بود", 100,
                        totalBytes = audioFile.length(),
                        detail = providerErrors.joinToString("؛ ")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio transcription exception", e)
                _aiError.value = e.localizedMessage ?: "خطا در تبدیل فایل صوتی."
                _transcriptionProgress.value = TranscriptionProgress(
                    "خطا در تبدیل صوت", 100, totalBytes = audioFile.length(),
                    detail = e.localizedMessage ?: "خطای نامشخص"
                )
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    private suspend fun saveTranscriptionResult(transcript: String) {
        formTranscript.value = transcript
        _aiError.value = null

        val id = editingLectureId ?: return
        val current = repository.getLecture(id) ?: return

        var updated = current.copy(
            transcript = transcript,
            audioFilePath = formAudioPath.value,
            audioUrl = formAudioUrl.value.trim().ifBlank { null },
            audioDurationMs = formAudioDurationMs.value,
            lastEditedMillis = System.currentTimeMillis()
        )

        repository.saveLecture(updated)
        _selectedLecture.value = updated

        // بعد از پیاده‌سازی، متن، خلاصه و نکات کلیدی بلافاصله در همان جلسه ذخیره می‌شوند.
        _aiOperationTitle.value = "در حال تهیه خلاصه و کلیدواژه‌ها..."

        val summary = aiTextWithGroqFirst(
            groqCall = { GroqApiService.summarizeLecture(transcript, current.title) },
            geminiCall = { GeminiApiService.summarizeLecture(transcript, current.title) }
        )
        summary.onSuccess { value ->
            updated = updated.copy(aiSummary = value, lastEditedMillis = System.currentTimeMillis())
        }

        val keyPoints = aiTextWithGroqFirst(
            groqCall = { GroqApiService.extractKeyPoints(transcript) },
            geminiCall = { GeminiApiService.extractKeyPoints(transcript) }
        )
        keyPoints.onSuccess { value ->
            updated = updated.copy(aiKeyPoints = value, lastEditedMillis = System.currentTimeMillis())
        }

        repository.saveLecture(updated)
        _selectedLecture.value = updated
        _aiError.value = null
    }

    fun clearTranscriptionProgress() {
        _transcriptionProgress.value = null
    }

    // ---------------------------------------------------------
    // AI Operations
    // ---------------------------------------------------------

    fun generateAiSummary(lectureId: Long) {

        val current =
            _selectedLecture.value
                ?: return

        if (current.transcript.isBlank()) {

            _aiError.value =
                "ابتدا باید متن پیاده‌شده کلاس را وارد کنید."

            return
        }

        viewModelScope.launch {

            _isAiLoading.value = true

            _aiOperationTitle.value =
                "در حال نگارش خلاصه هوشمند کلاس با هوش مصنوعی..."

            _aiError.value = null

            val result =
                aiTextWithGroqFirst(
                    groqCall = { GroqApiService.summarizeLecture(current.transcript, current.title) },
                    geminiCall = { GeminiApiService.summarizeLecture(current.transcript, current.title) }
                )

            result.onSuccess { summary ->

                val updated =
                    current.copy(
                        aiSummary = summary,
                        lastEditedMillis =
                            System.currentTimeMillis()
                    )

                repository.saveLecture(updated)

                _selectedLecture.value =
                    updated

            }.onFailure { error ->

                _aiError.value =
                    error.localizedMessage
                        ?: "خطا در تولید خلاصه هوشمند"
            }

            _isAiLoading.value = false
        }
    }

    fun extractAiKeyPoints(lectureId: Long) {

        val current =
            _selectedLecture.value
                ?: return

        if (current.transcript.isBlank()) {

            _aiError.value =
                "متن کلاس خالی است."

            return
        }

        viewModelScope.launch {

            _isAiLoading.value = true

            _aiOperationTitle.value =
                "در حال استخراج فرمول‌ها و نکات کلیدی با هوش مصنوعی..."

            _aiError.value = null

            val result =
                aiTextWithGroqFirst(
                    groqCall = { GroqApiService.extractKeyPoints(current.transcript) },
                    geminiCall = { GeminiApiService.extractKeyPoints(current.transcript) }
                )

            result.onSuccess { keyPoints ->

                val updated =
                    current.copy(
                        aiKeyPoints = keyPoints,
                        lastEditedMillis =
                            System.currentTimeMillis()
                    )

                repository.saveLecture(updated)

                _selectedLecture.value =
                    updated

            }.onFailure { error ->

                _aiError.value =
                    error.localizedMessage
                        ?: "خطا در استخراج نکات کلیدی"
            }

            _isAiLoading.value = false
        }
    }

    fun generateAiQuiz(lectureId: Long) {

        val current =
            _selectedLecture.value
                ?: return

        if (current.transcript.isBlank()) {

            _aiError.value =
                "متن کلاس خالی است."

            return
        }

        viewModelScope.launch {

            _isAiLoading.value = true

            _aiOperationTitle.value =
                "در حال طراحی سوالات ۴ گزینه‌ای و آزمونک کلاسی..."

            _aiError.value = null

            val result =
                aiTextWithGroqFirst(
                    groqCall = { GroqApiService.generateQuiz(current.transcript) },
                    geminiCall = { GeminiApiService.generateQuiz(current.transcript) }
                )

            result.onSuccess { quizJson ->

                val cleanJson =
                    cleanJsonString(quizJson)

                parseQuizJson(cleanJson)

                val updated =
                    current.copy(
                        aiQuizJson = cleanJson,
                        lastEditedMillis =
                            System.currentTimeMillis()
                    )

                repository.saveLecture(updated)

                _selectedLecture.value =
                    updated

            }.onFailure { error ->

                _aiError.value =
                    error.localizedMessage
                        ?: "خطا در تولید آزمونک هوشمند"
            }

            _isAiLoading.value = false
        }
    }

    fun askAiQuestion(question: String) {

        val current =
            _selectedLecture.value
                ?: return

        if (question.isBlank()) return

        val userMsg =
            ChatMessage(
                sender = MessageSender.USER,
                text = question
            )

        _chatMessages.value =
            _chatMessages.value + userMsg

        viewModelScope.launch {

            _isAiLoading.value = true

            _aiOperationTitle.value =
                "استادیار در حال تفکر و بررسی جزوه..."

            _aiError.value = null

            val result =
                aiTextWithGroqFirst(
                    groqCall = { GroqApiService.askLectureQuestion(current.transcript, question) },
                    geminiCall = { GeminiApiService.askLectureQuestion(current.transcript, question) }
                )

            result.onSuccess { answer ->

                val aiMsg =
                    ChatMessage(
                        sender = MessageSender.AI,
                        text = answer
                    )

                _chatMessages.value =
                    _chatMessages.value + aiMsg

            }.onFailure { error ->

                val errorMsg =
                    ChatMessage(
                        sender = MessageSender.AI,
                        text =
                            "متأسفانه در پاسخ‌دهی خطایی رخ داد: " +
                            "${error.localizedMessage}"
                    )

                _chatMessages.value =
                    _chatMessages.value + errorMsg
            }

            _isAiLoading.value = false
        }
    }

    fun polishTranscriptInForm() {
        val raw = formTranscript.value
        if (raw.isBlank()) return

        viewModelScope.launch {
            _isAiLoading.value = true
            _aiError.value = null
            try {
                // ویرایش متن‌های بلند در بخش‌های جداگانه برای جلوگیری از کوتاه‌شدن خروجی مدل.
                val parts = splitTranscriptForEditing(raw, 6000)
                val editedParts = mutableListOf<String>()

                for ((index, part) in parts.withIndex()) {
                    _aiOperationTitle.value =
                        "ویرایش امانت‌دارانه متن؛ بخش ${index + 1} از ${parts.size}"

                    val result = aiTextWithGroqFirst(
                        groqCall = { GroqApiService.polishTranscript(part) },
                        geminiCall = { GeminiApiService.polishTranscript(part) }
                    )

                    if (result.isFailure) {
                        throw result.exceptionOrNull()
                            ?: Exception("ویرایش بخش ${index + 1} ناموفق بود.")
                    }

                    val edited = result.getOrThrow().trim()
                    if (edited.isBlank()) {
                        throw Exception("بخش ${index + 1} خروجی خالی داشت؛ متن اصلی حفظ شد.")
                    }
                    editedParts += edited
                }

                // فقط پس از موفقیت همه بخش‌ها متن فرم جایگزین می‌شود.
                formTranscript.value = editedParts.joinToString("\n\n")
                _aiOperationTitle.value = "ویرایش متن کامل شد؛ محتوای درس حفظ شده است"
            } catch (error: Exception) {
                Log.e(TAG, "Transcript editing failed", error)
                _aiError.value = error.localizedMessage ?: "ویرایش متن ناموفق بود؛ متن اصلی تغییر نکرد."
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    private fun splitTranscriptForEditing(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChars, text.length)
            if (end < text.length) {
                val lowerBound = start + maxChars / 2
                val candidates = listOf(
                    text.lastIndexOf("\n\n", end),
                    text.lastIndexOf('\n', end),
                    text.lastIndexOf('۔', end),
                    text.lastIndexOf('.', end),
                    text.lastIndexOf('؟', end)
                )
                val boundary = candidates.filter { it >= lowerBound }.maxOrNull()
                if (boundary != null) {
                    end = if (text.startsWith("\n\n", boundary)) boundary + 2 else boundary + 1
                }
            }
            if (end <= start) end = minOf(start + maxChars, text.length)
            val piece = text.substring(start, end).trim()
            if (piece.isNotBlank()) parts += piece
            start = end
        }
        return parts
    }

    fun answerQuiz(
        questionIndex: Int,
        optionIndex: Int
    ) {

        val map =
            _quizSelectedAnswers.value.toMutableMap()

        map[questionIndex] =
            optionIndex

        _quizSelectedAnswers.value =
            map
    }

    fun resetQuizAnswers() {

        _quizSelectedAnswers.value =
            emptyMap()
    }

    private fun cleanJsonString(
        raw: String
    ): String {

        var str =
            raw.trim()

        if (str.startsWith("```json")) {

            str =
                str.removePrefix("```json")
                    .trim()

        } else if (str.startsWith("```")) {

            str =
                str.removePrefix("```")
                    .trim()
        }

        if (str.endsWith("```")) {

            str =
                str.removeSuffix("```")
                    .trim()
        }

        return str
    }

    private fun parseQuizJson(
        rawJson: String?
    ) {

        if (rawJson.isNullOrBlank()) {

            _parsedQuiz.value =
                emptyList()

            return
        }

        try {

            val list =
                mutableListOf<QuizQuestion>()

            val array =
                JSONArray(rawJson)

            for (i in 0 until array.length()) {

                val obj =
                    array.getJSONObject(i)

                val question =
                    obj.optString(
                        "question",
                        ""
                    )

                val optionsArray =
                    obj.optJSONArray("options")
                        ?: JSONArray()

                val options =
                    mutableListOf<String>()

                for (
                    j in 0 until optionsArray.length()
                ) {

                    options.add(
                        optionsArray.getString(j)
                    )
                }

                val correctIndex =
                    obj.optInt(
                        "correctIndex",
                        0
                    )

                val explanation =
                    obj.optString(
                        "explanation",
                        ""
                    )

                list.add(
                    QuizQuestion(
                        question,
                        options,
                        correctIndex,
                        explanation
                    )
                )
            }

            _parsedQuiz.value =
                list

            _quizSelectedAnswers.value =
                emptyMap()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error parsing quiz JSON",
                e
            )

            _parsedQuiz.value =
                emptyList()
        }
    }

    fun clearAiError() {
        _aiError.value = null
    }

    override fun onCleared() {

        super.onCleared()

        audioPlayer.stop()

        audioRecorder.stopRecording()

        speechRecognizer.stopListening()
    }
}
