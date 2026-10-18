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
import com.example.data.local.CloudMaterialsSync
import com.example.data.local.ClassEntity
import com.example.data.local.LectureEntity
import com.example.data.repository.LectureRepository
import com.example.data.repository.FirestoreSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        if (rolePrefs.getString("role", "STUDENT") == "TEACHER") UserRole.TEACHER else UserRole.STUDENT
    )
    val userRole: StateFlow<UserRole> = _userRole.asStateFlow()

    fun setUserRole(role: UserRole) {
        _userRole.value = role
        rolePrefs.edit().putString("role", role.name).apply()
    }

    private val _cloudSyncStatus = MutableStateFlow("وضعیت همگام‌سازی هنوز بررسی نشده است.")
    val cloudSyncStatus: StateFlow<String> = _cloudSyncStatus.asStateFlow()

    private val _studentMaterialsSyncStatus = MutableStateFlow("مطالب آنلاین هنوز بررسی نشده‌اند.")
    val studentMaterialsSyncStatus: StateFlow<String> = _studentMaterialsSyncStatus.asStateFlow()
    private var studentMaterialsSyncJob: Job? = null

    /**
     * Automatically fetch published teacher materials for a student after login.
     * The connection/settings screen remains available as a manual retry path.
     */
    fun startStudentMaterialsSync() {
        if (_userRole.value != UserRole.STUDENT) return
        if (studentMaterialsSyncJob?.isActive == true) return

        val app = getApplication<Application>()
        if (AuthPreferences.currentUid(app).isNullOrBlank()) {
            _studentMaterialsSyncStatus.value = "حساب کاربری مشخص نیست؛ از بخش اتصال دوباره تلاش کنید."
            return
        }

        _studentMaterialsSyncStatus.value = "در حال دریافت خودکار مطالب منتشرشده استادها…"
        studentMaterialsSyncJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    CloudMaterialsSync.sync(app)
                }
                _studentMaterialsSyncStatus.value = result
            } catch (e: Exception) {
                Log.e(TAG, "Automatic student materials sync failed", e)
                _studentMaterialsSyncStatus.value =
                    "دریافت خودکار مطالب انجام نشد؛ به بخش اتصال بروید و «دریافت مطالب آنلاین» را بزنید. ${e.localizedMessage ?: ""}".trim()
            }
        }
    }

    /** Restore cloud data first, then autosave subsequent Room changes. */
    @OptIn(FlowPreview::class)
    fun startCloudSync() {
        if (_userRole.value != UserRole.TEACHER) {
            _cloudSyncStatus.value = "همگام‌سازی خصوصی فقط برای حساب استاد فعال است."
            return
        }
        val app = getApplication<Application>()
        val uid = AuthPreferences.currentUid(app)
        if (uid.isNullOrBlank()) {
            _cloudSyncStatus.value = "حساب کاربری وارد نشده است؛ برای همگام‌سازی دوباره وارد شوید."
            return
        }
        cloudSyncJob?.cancel()
        _cloudSyncStatus.value = "در حال بررسی و همگام‌سازی با فضای ابری…"
        cloudSyncJob = viewModelScope.launch {
            try {
                // Verify approval against Firestore before restoring or uploading anything.
                if (!teacherPermissionGranted()) {
                    denyTeacherMutation()
                    _cloudSyncStatus.value = "مجوز استاد لغو شده است؛ همگام‌سازی متوقف شد و اطلاعات محلی ارسال نشد."
                    return@launch
                }
                val restored = cloudSyncRepository.restoreFromCloud(uid)
                if (!restored) {
                    // Keep old published material untouched; import it only if it
                    // follows the known snapshot shape, then create private backup.
                    cloudSyncRepository.restoreLegacySharedMaterials(uid)
                    cloudSyncRepository.uploadSnapshot(uid)
                    _cloudSyncStatus.value = "پشتیبان‌گیری ابری موفق بود؛ تغییرات بعدی نیز خودکار ارسال می‌شوند."
                } else {
                    _cloudSyncStatus.value = "بازیابی اطلاعات از فضای ابری موفق بود؛ تغییرات بعدی خودکار ارسال می‌شوند."
                }

                combine(
                    repository.allLectures,
                    repository.allClasses,
                    repository.allCoursesDetailed
                ) { _, _, _ -> Unit }
                    .debounce(1200)
                    .collect {
                        try {
                            // Approval can be revoked while autosave is waiting in debounce.
                            if (!teacherPermissionGranted()) {
                                denyTeacherMutation()
                                _cloudSyncStatus.value = "مجوز استاد لغو شده است؛ ارسال تغییرات متوقف شد."
                                cloudSyncJob?.cancel()
                                return@collect
                            }
                            cloudSyncRepository.uploadSnapshot(uid)
                            _cloudSyncStatus.value = "همگام‌سازی ابری موفق — آخرین ارسال انجام شد."
                        } catch (e: Exception) {
                            Log.e(TAG, "Teacher cloud backup failed", e)
                            _cloudSyncStatus.value = "همگام‌سازی ناموفق بود: ${e.localizedMessage ?: "خطای نامشخص"}"
                        }
                    }
            } catch (e: Exception) {
                // A failed cloud read must never trigger a destructive local upload.
                Log.e(TAG, "Teacher cloud restore failed; local data was kept", e)
                _cloudSyncStatus.value = "اتصال یا بازیابی ابری ناموفق بود: ${e.localizedMessage ?: "خطای نامشخص"}. اطلاعات محلی حفظ شد."
            }
        }
    }

    private val database = AppDatabase.getDatabase(application)
    val repository = LectureRepository(database.lectureDao(), database.classDao(), database.courseDao())
    private val cloudSyncRepository = FirestoreSyncRepository(
        database.lectureDao(), database.classDao(), database.courseDao()
    )
    private var cloudSyncJob: Job? = null

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
    val formOriginalTranscript = MutableStateFlow("")
    val formAiSummary = MutableStateFlow("")
    val formAiKeyPoints = MutableStateFlow("")
    val formAudioPath = MutableStateFlow<String?>(null)
    val formAudioUrl = MutableStateFlow("")
    val formAudioDurationMs = MutableStateFlow(0L)
    private var editingLectureId: Long? = null

    init {
        GeminiApiService.configure(application)

        viewModelScope.launch {
            if (AuthPreferences.currentUid(application) == null) {
                repository.seedInitialDataIfEmpty()
            }
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

    private suspend fun teacherPermissionGranted(): Boolean =
        withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { continuation ->
                AuthPreferences.hasActiveTeacherPermission(getApplication<Application>()) { allowed ->
                    if (continuation.isActive) continuation.resume(allowed)
                }
            }
        } ?: false

    private fun denyTeacherMutation() {
        // Keep the visible role unchanged on a failed live check (which may be a
        // temporary network/rules issue). Every mutation remains guarded below.
        _cloudSyncStatus.value = "مجوز فعال استاد تأیید نشد؛ ایجاد و ویرایش مطالب متوقف شد."
    }

    fun saveClass(
        item: com.example.data.local.ClassEntity,
        onSaved: (Long) -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                if (!teacherPermissionGranted()) {
                    denyTeacherMutation()
                    onFailure("مجوز فعال این حساب به‌عنوان استاد در Firebase تأیید نشد. از حساب مدیر اصلی، درخواست این استاد را بررسی و دوباره تأیید کنید؛ سپس خارج شوید و دوباره وارد شوید.")
                    return@launch
                }
                if (item.id < 0L) {
                    onFailure("این کلاس متعلق به استاد دیگری است و فقط برای مطالعه در دسترس است؛ نمی‌توانید آن را ویرایش کنید.")
                    return@launch
                }
                onSaved(repository.saveClass(item))
            } catch (e: Exception) {
                Log.e(TAG, "Saving class failed", e)
                onFailure("ذخیره کلاس ناموفق بود: ${e.localizedMessage ?: "خطای نامشخص Firebase یا پایگاه داده"}")
            }
        }
    }

    fun saveCourse(item: com.example.data.local.CourseEntity, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            if (!teacherPermissionGranted()) { denyTeacherMutation(); return@launch }
            if (item.id < 0L || item.classId < 0L) {
                _cloudSyncStatus.value = "این درس متعلق به مطالب منتشرشده استاد دیگری است؛ برای افزودن مطالب، کلاس خودتان را ایجاد کنید."
                return@launch
            }
            onSaved(repository.saveCourse(item))
        }
    }

    fun deleteClass(item: com.example.data.local.ClassEntity, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            if (!teacherPermissionGranted()) { denyTeacherMutation(); return@launch }
            if (item.id < 0L) {
                _cloudSyncStatus.value = "حذف یا پنهان‌کردن کلاس استاد دیگر مجاز نیست."
                return@launch
            }
            repository.deleteClass(item)
            try {
                withContext(Dispatchers.IO) { CloudMaterialsSync.sync(getApplication<Application>()) }
            } catch (e: Exception) {
                Log.e(TAG, "Could not refresh published feed after class deletion", e)
            }
            onDeleted()
        }
    }

    fun deleteCourse(item: com.example.data.local.CourseEntity, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            if (!teacherPermissionGranted()) { denyTeacherMutation(); return@launch }
            if (item.id < 0L || item.classId < 0L) {
                _cloudSyncStatus.value = "حذف درس استاد دیگر مجاز نیست."
                return@launch
            }
            repository.deleteCourse(item)
            onDeleted()
        }
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
                    formOriginalTranscript.value = lecture.originalTranscript.ifBlank { lecture.transcript }
                    formAiSummary.value = lecture.aiSummary.orEmpty()
                    formAiKeyPoints.value = lecture.aiKeyPoints.orEmpty()
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
            formOriginalTranscript.value = ""
            formAiSummary.value = ""
            formAiKeyPoints.value = ""
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
            if (!teacherPermissionGranted()) { denyTeacherMutation(); return@launch }

            val existing =
                if (lectureId != null)
                    repository.getLecture(lectureId)
                else
                    null

            if ((lectureId != null && lectureId < 0L) ||
                (existing?.classId ?: formClassId.value)?.let { it < 0L } == true
            ) {
                _cloudSyncStatus.value = "این جلسه یا کلاس متعلق به استاد دیگری است و قابل ویرایش نیست."
                return@launch
            }

            val updated =
                LectureEntity(
                    id = lectureId ?: 0L,
                    title = title,
                    courseName = formCourse.value.trim(),
                    professorName = formProfessor.value.trim(),
                    // Editing a session title must not detach it from its class.
                    // Preserve the stored relation if the form field is temporarily empty.
                    classId = formClassId.value ?: existing?.classId,
                    courseId = formCourseId.value ?: existing?.courseId,
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
                    originalTranscript =
                        formOriginalTranscript.value.trim().ifBlank { formTranscript.value.trim() },
                    aiSummary =
                        formAiSummary.value.takeIf { it.isNotBlank() } ?: existing?.aiSummary,
                    aiKeyPoints =
                        formAiKeyPoints.value.takeIf { it.isNotBlank() } ?: existing?.aiKeyPoints,
                    aiQuizJson =
                        existing?.aiQuizJson,
                    tags =
                        formTags.value.trim(),
                    isFavorite =
                        existing?.isFavorite ?: false,
                    isPublished =
                        existing?.isPublished ?: true,
                    lastEditedMillis =
                        System.currentTimeMillis()
                )

            val newId =
                repository.saveLecture(updated)

            loadLecture(newId)

            onSaved(newId)
        }
    }

    fun setClassPublished(classItem: ClassEntity, published: Boolean) {
        viewModelScope.launch {
            if (!teacherPermissionGranted()) { denyTeacherMutation(); return@launch }
            if (classItem.id < 0L) {
                _cloudSyncStatus.value = "تغییر وضعیت انتشار کلاس استاد دیگر مجاز نیست."
                return@launch
            }
            repository.saveClass(classItem.copy(isPublished = published))
            // Refresh the shared student feed immediately after class visibility changes.
            try {
                withContext(Dispatchers.IO) {
                    CloudMaterialsSync.sync(getApplication<Application>())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not refresh published class feed", e)
                _cloudSyncStatus.value =
                    "وضعیت انتشار کلاس در دستگاه ذخیره شد؛ همگام‌سازی آنلاین ناموفق بود و باید دوباره تلاش شود."
            }
        }
    }

    fun toggleFavorite(lecture: LectureEntity) {

        viewModelScope.launch {
            if (!teacherPermissionGranted()) { denyTeacherMutation(); return@launch }

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
            if (!teacherPermissionGranted()) { denyTeacherMutation(); return@launch }

            if (id < 0L) {
                _cloudSyncStatus.value = "حذف جلسه متعلق به استاد دیگر مجاز نیست."
                return@launch
            }
            val lecture = repository.getLecture(id)
            if (lecture?.classId?.let { it < 0L } == true) {
                _cloudSyncStatus.value = "حذف جلسه متعلق به استاد دیگر مجاز نیست."
                return@launch
            }

            audioPlayer.stop()

            repository.deleteLecture(id)

            _selectedLecture.value = null

            onDeleted()
        }
    }


    // برای استخراج و اصلاح متن: Gemini اول؛ در صورت نبودن کلید یا خطا، Groq.
    private suspend fun aiTextWithGroqFirst(
        groqCall: suspend () -> Result<String>,
        geminiCall: suspend () -> Result<String>
    ): Result<String> {
        if (GeminiApiService.getSavedApiKey(getApplication<Application>()).isNotBlank()) {
            val geminiResult = geminiCall()
            if (geminiResult.isSuccess) return geminiResult
            Log.w(TAG, "Gemini failed; falling back to Groq", geminiResult.exceptionOrNull())
        }
        return groqCall()
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
                detail = "اولویت: Gemini، سپس Groq و در پایان Speechmatics"
            )
            try {
                // مرحله اول: Gemini
                _aiOperationTitle.value = "مرحله ۱ از ۳: Gemini"
                if (GeminiApiService.getSavedApiKey(getApplication<Application>()).isNotBlank()) {
                    val result = try {
                        GeminiApiService.transcribeAudioFile(audioPath) { progress ->
                            _transcriptionProgress.value = progress
                            _aiOperationTitle.value = progress.stage
                        }
                    } catch (e: Exception) { Result.failure<String>(e) }
                    if (result.isSuccess) {
                        saveTranscriptionResult(result.getOrThrow())
                        return@launch
                    }
                    val reason = "Gemini: ${result.exceptionOrNull()?.localizedMessage ?: "خطای نامشخص"}"
                    providerErrors += reason
                    Log.w(TAG, reason, result.exceptionOrNull())
                } else {
                    providerErrors += "Gemini: کلید API تنظیم نشده است."
                }

                // مرحله دوم: Groq
                _aiOperationTitle.value = "مرحله ۲ از ۳: Groq"
                if (GroqApiService.hasApiKey(getApplication<Application>())) {
                    val result = try {
                        GroqApiService.transcribeAudioFile(audioPath) { progress ->
                            _transcriptionProgress.value = progress
                            _aiOperationTitle.value = progress.stage
                        }
                    } catch (e: Exception) { Result.failure<String>(e) }
                    if (result.isSuccess) {
                        saveTranscriptionResult(result.getOrThrow())
                        return@launch
                    }
                    val reason = "Groq: ${result.exceptionOrNull()?.localizedMessage ?: "خطای نامشخص"}"
                    providerErrors += reason
                    Log.w(TAG, reason, result.exceptionOrNull())
                } else {
                    providerErrors += "Groq: کلید API تنظیم نشده است."
                }

                // مرحله سوم: Speechmatics
                _aiOperationTitle.value = "مرحله ۳ از ۳: Speechmatics"
                if (SpeechmaticsApiService.hasApiKey(getApplication<Application>())) {
                    val result = try {
                        SpeechmaticsApiService.transcribeAudioFile(audioPath) { progress ->
                            _transcriptionProgress.value = progress
                            _aiOperationTitle.value = progress.stage
                        }
                    } catch (e: Exception) { Result.failure<String>(e) }
                    if (result.isSuccess) {
                        saveTranscriptionResult(result.getOrThrow())
                        return@launch
                    }
                    providerErrors += "Speechmatics: ${result.exceptionOrNull()?.localizedMessage ?: "خطای نامشخص"}"
                } else {
                    providerErrors += "Speechmatics: کلید API تنظیم نشده است."
                }

                Log.e(TAG, "All transcription providers failed: ${providerErrors.joinToString(" | ")}")
                _aiError.value = "تبدیل صوت انجام نشد. علت هر سرویس:\\n• " + providerErrors.joinToString("\\n• ")
                _transcriptionProgress.value = TranscriptionProgress(
                    "تبدیل صوت ناموفق بود", 100,
                    totalBytes = audioFile.length(),
                    detail = providerErrors.joinToString("؛ ")
                )
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
        if (!teacherPermissionGranted()) { denyTeacherMutation(); return }
        formTranscript.value = transcript
        formOriginalTranscript.value = transcript
        _aiError.value = null

        val id = editingLectureId ?: return
        val current = repository.getLecture(id) ?: return

        var updated = current.copy(
            transcript = transcript,
            originalTranscript = transcript,
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

        val handoutForKeywords = updated.aiSummary?.takeIf { it.isNotBlank() } ?: transcript
        val keyPoints = aiTextWithGroqFirst(
            groqCall = { GroqApiService.extractKeyPoints(handoutForKeywords) },
            geminiCall = { GeminiApiService.extractKeyPoints(handoutForKeywords) }
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
            if (!teacherPermissionGranted()) { denyTeacherMutation(); return@launch }

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
            if (!teacherPermissionGranted()) { denyTeacherMutation(); return@launch }

            _isAiLoading.value = true

            _aiOperationTitle.value =
                "در حال استخراج کلیدواژه‌های جزوه..."

            _aiError.value = null

            val handoutForKeywords = current.aiSummary?.takeIf { it.isNotBlank() } ?: current.transcript
            val result =
                aiTextWithGroqFirst(
                    groqCall = { GroqApiService.extractKeyPoints(handoutForKeywords) },
                    geminiCall = { GeminiApiService.extractKeyPoints(handoutForKeywords) }
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
            if (!teacherPermissionGranted()) { denyTeacherMutation(); return@launch }

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
            if (!teacherPermissionGranted()) { denyTeacherMutation(); return@launch }

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
        // Always polish the preserved source transcript, not the previously polished text
        // currently displayed in formTranscript. This makes repeated edits deterministic.
        val original = formOriginalTranscript.value.ifBlank { formTranscript.value }
        val raw = original
        if (raw.isBlank()) return

        viewModelScope.launch {
            if (!teacherPermissionGranted()) { denyTeacherMutation(); return@launch }
            _isAiLoading.value = true
            _aiError.value = null
            formAiSummary.value = ""
            formAiKeyPoints.value = ""
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

                // متن ویرایش‌شده را در فرم قرار می‌دهیم، سپس خلاصه و کلیدواژه‌ها را از همین متن می‌سازیم.
                val editedTranscript = editedParts.joinToString("\n\n")
                formOriginalTranscript.value = original
                formTranscript.value = editedTranscript

                _aiOperationTitle.value = "در حال تهیه خلاصه از متن ویرایش‌شده..."
                val summaryResult = aiTextWithGroqFirst(
                    groqCall = { GroqApiService.summarizeLecture(editedTranscript, formTitle.value) },
                    geminiCall = { GeminiApiService.summarizeLecture(editedTranscript, formTitle.value) }
                )
                summaryResult.onSuccess { formAiSummary.value = it.trim() }

                _aiOperationTitle.value = "در حال استخراج کلیدواژه‌ها..."
                // Extract keywords from the complete edited transcript, not the shorter summary.
                val keywordSource = editedTranscript
                val keywordResult = aiTextWithGroqFirst(
                    groqCall = { GroqApiService.extractKeyPoints(keywordSource) },
                    geminiCall = { GeminiApiService.extractKeyPoints(keywordSource) }
                )
                keywordResult.onSuccess { formAiKeyPoints.value = it.trim() }

                val failures = buildList {
                    summaryResult.exceptionOrNull()?.let { add("خلاصه: ${it.localizedMessage ?: "خطای نامشخص"}") }
                    keywordResult.exceptionOrNull()?.let { add("کلیدواژه‌ها: ${it.localizedMessage ?: "خطای نامشخص"}") }
                }
                if (failures.isNotEmpty()) {
                    _aiError.value = "متن ویرایش شد، اما تولید خودکار کامل نشد. می‌توانید متن را ذخیره کنید و بعداً دوباره تلاش کنید.\n" + failures.joinToString("\n")
                    _aiOperationTitle.value = "ویرایش متن انجام شد؛ تولید خلاصه/کلیدواژه نیاز به تلاش دوباره دارد"
                } else {
                    _aiOperationTitle.value = "متن ویرایش، خلاصه و کلیدواژه‌ها آماده شد"
                }

                // اگر جلسه از قبل ذخیره شده باشد، خروجی‌ها را همان لحظه نیز در پایگاه داده ثبت می‌کنیم.
                val savedId = editingLectureId
                if (savedId != null) {
                    val existing = repository.getLecture(savedId)
                    if (existing != null) {
                        repository.saveLecture(existing.copy(
                            transcript = editedTranscript,
                            originalTranscript = original,
                            aiSummary = formAiSummary.value.takeIf { it.isNotBlank() } ?: existing.aiSummary,
                            aiKeyPoints = formAiKeyPoints.value.takeIf { it.isNotBlank() } ?: existing.aiKeyPoints,
                            lastEditedMillis = System.currentTimeMillis()
                        ))
                    }
                }
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
