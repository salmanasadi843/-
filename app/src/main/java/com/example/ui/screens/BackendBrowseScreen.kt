package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.data.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Server-backed preview for teacher → class → session browsing and authorized management. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BackendBrowseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var teachers by remember { mutableStateOf<List<BackendTeacherDto>>(emptyList()) }
    var classes by remember { mutableStateOf<List<BackendClassDto>>(emptyList()) }
    var teacherFolders by remember { mutableStateOf<List<BackendTeacherFolderDto>>(emptyList()) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var folderNameDraft by remember { mutableStateOf("") }
    var folderParentDraft by remember { mutableStateOf<String?>(null) }
    var editingFolder by remember { mutableStateOf<BackendTeacherFolderDto?>(null) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var classFolderDraft by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf<List<BackendSessionSummaryDto>>(emptyList()) }
    var selectedTeacherId by remember { mutableStateOf<String?>(null) }
    var selectedClass by remember { mutableStateOf<BackendClassDto?>(null) }
    var selectedSession by remember { mutableStateOf<BackendSessionDto?>(null) }
    var selectedSessionState by remember { mutableStateOf<BackendSessionStateDto?>(null) }
    var sessionNotes by remember { mutableStateOf<List<BackendSessionNoteDto>>(emptyList()) }
    var myNotes by remember { mutableStateOf<List<BackendGlobalSessionNoteDto>>(emptyList()) }
    var myNotesSearch by remember { mutableStateOf("") }
    var showMyNotesDialog by remember { mutableStateOf(false) }
    var pendingGlobalNoteFocusId by remember { mutableStateOf<String?>(null) }
    var noteQuote by remember { mutableStateOf("") }
    var noteDraft by remember { mutableStateOf("") }
    var noteStartOffset by remember { mutableStateOf<Int?>(null) }
    var noteEndOffset by remember { mutableStateOf<Int?>(null) }
    var activeNoteId by remember { mutableStateOf<String?>(null) }
    var originalTranscriptSelection by remember { mutableStateOf(TextRange.Zero) }
    var editedTranscriptSelection by remember { mutableStateOf(TextRange.Zero) }
    val originalTranscriptRequester = remember { BringIntoViewRequester() }
    val editedTranscriptRequester = remember { BringIntoViewRequester() }
    var editingNoteId by remember { mutableStateOf<String?>(null) }
    var playbackPositionDraft by remember { mutableStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf("") }
    var accountRoles by remember { mutableStateOf<List<String>>(emptyList()) }
    var showNotifications by remember { mutableStateOf(false) }
    var unreadNotificationCount by remember { mutableIntStateOf(0) }
    var notificationTopicId by remember { mutableStateOf<String?>(null) }
    var notificationPostId by remember { mutableStateOf<String?>(null) }
    var notificationOpenSequence by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("در حال آماده‌سازی اتصال…") }
    var editingClass by remember { mutableStateOf<BackendClassDto?>(null) }
    var classTitleDraft by remember { mutableStateOf("") }
    var classDescriptionDraft by remember { mutableStateOf("") }
    var classTermDraft by remember { mutableStateOf("") }
    var classVisibilityDraft by remember { mutableStateOf("private") }
    var classPublishedDraft by remember { mutableStateOf(false) }
    var classHiddenDraft by remember { mutableStateOf(false) }
    var movingSession by remember { mutableStateOf<BackendSessionSummaryDto?>(null) }
    var moveTargetClassId by remember { mutableStateOf("") }
    var editingSession by remember { mutableStateOf<BackendSessionDto?>(null) }
    var sessionTitleDraft by remember { mutableStateOf("") }
    var sessionCourseDraft by remember { mutableStateOf("") }
    var sessionProfessorDraft by remember { mutableStateOf("") }
    var sessionTranscriptDraft by remember { mutableStateOf("") }
    var sessionSummaryDraft by remember { mutableStateOf("") }
    var sessionKeyPointsDraft by remember { mutableStateOf("") }
    var sessionTagsDraft by remember { mutableStateOf("") }
    var sessionPublishedDraft by remember { mutableStateOf(false) }
    var showDeletedSessions by remember { mutableStateOf(false) }
    var deletedSessions by remember { mutableStateOf<List<BackendSessionSummaryDto>>(emptyList()) }
    var confirmDeleteSession by remember { mutableStateOf<BackendSessionSummaryDto?>(null) }
    var deletedClasses by remember { mutableStateOf<List<BackendClassDto>>(emptyList()) }
    var showDeletedClassesDialog by remember { mutableStateOf(false) }
    var confirmDeleteClass by remember { mutableStateOf<BackendClassDto?>(null) }

    fun folderDepth(folder: BackendTeacherFolderDto): Int {
        var parentId = folder.parentFolderId
        var depth = 0
        val visited = mutableSetOf(folder.id)
        while (parentId != null && visited.add(parentId)) {
            val parent = teacherFolders.firstOrNull { it.id == parentId } ?: break
            depth++
            parentId = parent.parentFolderId
        }
        return depth
    }

    fun folderAndDescendantIds(rootId: String?): Set<String>? {
        if (rootId == null) return null
        val result = mutableSetOf(rootId)
        var changed = true
        while (changed) {
            changed = false
            teacherFolders.forEach { folder ->
                if (folder.parentFolderId in result && result.add(folder.id)) changed = true
            }
        }
        return result
    }
    fun withFreshToken(action: suspend (String) -> Unit) {
        if (busy) return
        busy = true
        status = "در حال دریافت اطلاعات…"
        AuthPreferences.getFirebaseIdToken(context) { tokenResult ->
            tokenResult.fold(
                onSuccess = { token ->
                    scope.launch {
                        try {
                            action(token)
                        } catch (error: Exception) {
                            status = "دریافت اطلاعات ناموفق بود: ${error.localizedMessage ?: "خطای نامشخص"}"
                        } finally {
                            busy = false
                        }
                    }
                },
                onFailure = { error ->
                    busy = false
                    status = error.localizedMessage ?: "دریافت توکن ورود ناموفق بود."
                }
            )
        }
    }

    fun loadDirectory() {
        withFreshToken { token ->
            val result = withContext(Dispatchers.IO) {
                val repository = DarsyarReadOnlyApiFactory.create()
                val snapshot = repository.loadSnapshot(token)
                val directory = repository.loadTeachers(token)
                val folders = repository.loadTeacherFolders(token)
                val unreadCount = runCatching {
                    DarsyarCommunityApiFactory.create().notifications(token, unreadOnly = true).size
                }.getOrDefault(0)
                listOf(snapshot, directory, unreadCount, folders)
            }
            val snapshot = result[0] as BackendReadOnlySnapshot
            val directory = result[1] as List<BackendTeacherDto>
            val unreadCount = result[2] as Int
            val folders = result[3] as List<BackendTeacherFolderDto>
            accountId = snapshot.account.userId
            accountRoles = snapshot.account.roles
            classes = snapshot.classes
            teachers = directory
            teacherFolders = folders
            unreadNotificationCount = unreadCount
            selectedTeacherId = null
            selectedClass = null
            sessions = emptyList()
            selectedSession = null
            status = "فهرست استادان و کلاس‌های قابل مشاهده دریافت شد."
        }
    }

    fun loadMyNotes() {
        withFreshToken { token ->
            myNotes = withContext(Dispatchers.IO) { DarsyarCommunityApiFactory.create().myNotes(token) }
            showMyNotesDialog = true
            status = "یادداشت‌های شخصی شما دریافت شد."
        }
    }

    fun openGlobalNote(note: BackendGlobalSessionNoteDto, editAfterOpen: Boolean = false) {
        showMyNotesDialog = false
        withFreshToken { token ->
            val clazz = classes.firstOrNull { it.id == note.classId }
                ?: throw IllegalStateException("کلاس این یادداشت دیگر در فهرست قابل‌دسترسی نیست.")
            val result = withContext(Dispatchers.IO) {
                val readApi = DarsyarReadOnlyApiFactory.create()
                val sessionList = readApi.loadSessions(token, clazz.id)
                val session = sessionList.firstOrNull { it.id == note.sessionId }
                    ?: throw IllegalStateException("جلسهٔ این یادداشت دیگر در دسترس نیست.")
                val detail = readApi.loadSessionDetail(token, clazz.id, session.id)
                val community = DarsyarCommunityApiFactory.create()
                val state = community.sessionState(token, clazz.id, session.id)
                val notes = community.sessionNotes(token, clazz.id, session.id)
                Triple(sessionList, session, Triple(detail, state, notes))
            }
            sessions = result.first
            selectedTeacherId = clazz.ownerUserId
            selectedClass = clazz
            selectedSession = result.third.first
            selectedSessionState = result.third.second
            sessionNotes = result.third.third
            playbackPositionDraft = selectedSessionState!!.lastPlaybackPositionMs.toFloat()
            activeNoteId = note.id
            pendingGlobalNoteFocusId = note.id
            if (editAfterOpen) {
                editingNoteId = note.id
                noteQuote = note.quotedText
                noteDraft = note.noteText
                noteStartOffset = note.startOffset
                noteEndOffset = note.endOffset
                status = "یادداشت برای ویرایش باز شد؛ پس از تغییر، ذخیرهٔ ویرایش را بزنید."
            } else {
                status = "جلسه و یادداشت مرتبط باز شد."
            }
        }
    }

    fun deleteGlobalNote(note: BackendGlobalSessionNoteDto) {
        withFreshToken { token ->
            withContext(Dispatchers.IO) {
                DarsyarCommunityApiFactory.create().deleteSessionNote(token, note.id)
            }
            myNotes = myNotes.filterNot { it.id == note.id }
            sessionNotes = sessionNotes.filterNot { it.id == note.id }
            if (activeNoteId == note.id) activeNoteId = null
            if (editingNoteId == note.id) {
                editingNoteId = null
                noteQuote = ""; noteDraft = ""; noteStartOffset = null; noteEndOffset = null
            }
            status = "یادداشت حذف شد."
        }
    }

    fun beginEditClass(clazz: BackendClassDto) {
        editingClass = clazz
        classTitleDraft = clazz.title
        classDescriptionDraft = clazz.description
        classTermDraft = clazz.term
        classVisibilityDraft = clazz.visibility
        classPublishedDraft = clazz.isPublished
        classHiddenDraft = clazz.isHidden
        classFolderDraft = clazz.folderId
    }

    fun saveClassChanges() {
        val current = editingClass ?: return
        if (classTitleDraft.isBlank()) {
            status = "عنوان کلاس نمی‌تواند خالی باشد."
            return
        }
        withFreshToken { token ->
            val updated = withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().updateClass(
                    token,
                    current.id,
                    BackendClassWriteRequest(
                        title = classTitleDraft.trim(),
                        description = classDescriptionDraft,
                        term = classTermDraft,
                        visibility = classVisibilityDraft,
                        isPublished = classPublishedDraft,
                        isHidden = classHiddenDraft,
                        sortOrder = current.sortOrder,
                        folderId = classFolderDraft
                    )
                )
            }
            classes = classes.map { if (it.id == updated.id) updated else it }
            selectedClass = selectedClass?.let { if (it.id == updated.id) updated else it }
            editingClass = null
            status = "تغییرات کلاس ذخیره شد."
        }
    }

    fun deleteClassFromDirectory(clazz: BackendClassDto) {
        withFreshToken { token ->
            withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().deleteClass(token, clazz.id)
            }
            classes = classes.filterNot { it.id == clazz.id }
            if (selectedClass?.id == clazz.id) {
                selectedClass = null
                selectedSession = null
                sessions = emptyList()
            }
            editingClass = null
            confirmDeleteClass = null
            status = "کلاس به سطل بازیافت منتقل شد؛ جلسه‌ها و محتوای آن حفظ شده‌اند."
        }
    }

    fun openDeletedClasses() {
        withFreshToken { token ->
            deletedClasses = withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().loadDeletedClasses(token)
            }
            showDeletedClassesDialog = true
            status = "کلاس‌های حذف‌شده دریافت شدند."
        }
    }

    fun restoreClassFromRecycleBin(clazz: BackendClassDto) {
        withFreshToken { token ->
            withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().restoreClass(token, clazz.id)
            }
            deletedClasses = deletedClasses.filterNot { it.id == clazz.id }
            classes = classes.filterNot { it.id == clazz.id } + clazz
            status = "کلاس بازیابی شد."
            if (deletedClasses.isEmpty()) showDeletedClassesDialog = false
        }
    }

    fun openClass(clazz: BackendClassDto) {
        selectedClass = clazz
        sessions = emptyList()
        selectedSession = null
        withFreshToken { token ->
            sessions = withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().loadSessions(token, clazz.id)
            }
            status = "فهرست جلسه‌ها دریافت شد؛ متن کامل هنوز دریافت نشده است."
        }
    }

    fun openSession(session: BackendSessionSummaryDto) {
        selectedSession = null
        selectedSessionState = null
        withFreshToken { token ->
            val result = withContext(Dispatchers.IO) {
                val detail = DarsyarReadOnlyApiFactory.create().loadSessionDetail(token, session.classId, session.id)
                val community = DarsyarCommunityApiFactory.create()
                val state = community.sessionState(token, session.classId, session.id)
                val notes = community.sessionNotes(token, session.classId, session.id)
                Triple(detail, state, notes)
            }
            selectedSession = result.first
            selectedSessionState = result.second
            sessionNotes = result.third
            noteQuote = ""; noteDraft = ""; noteStartOffset = null; noteEndOffset = null
            activeNoteId = null; originalTranscriptSelection = TextRange.Zero; editedTranscriptSelection = TextRange.Zero; editingNoteId = null
            playbackPositionDraft = result.second.lastPlaybackPositionMs.toFloat()
            status = "جزئیات جلسه، وضعیت مطالعه و یادداشت‌های شخصی دریافت شد."
        }
    }

    fun beginEditSession(detail: BackendSessionDto) {
        editingSession = detail
        sessionTitleDraft = detail.title
        sessionCourseDraft = detail.courseName
        sessionProfessorDraft = detail.professorName
        sessionTranscriptDraft = detail.transcript
        sessionSummaryDraft = detail.aiSummary.orEmpty()
        sessionKeyPointsDraft = detail.aiKeyPoints.orEmpty()
        sessionTagsDraft = detail.tags
        sessionPublishedDraft = detail.isPublished
    }

    fun saveSessionChanges() {
        val current = editingSession ?: return
        if (sessionTitleDraft.isBlank()) {
            status = "عنوان جلسه نمی‌تواند خالی باشد."
            return
        }
        withFreshToken { token ->
            val updated = withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().updateSession(
                    token,
                    current.id,
                    BackendSessionWriteRequest(
                        courseId = current.courseId,
                        title = sessionTitleDraft.trim(),
                        courseName = sessionCourseDraft,
                        professorName = sessionProfessorDraft,
                        dateMs = current.dateMs,
                        audioDurationMs = current.audioDurationMs,
                        transcript = sessionTranscriptDraft,
                        originalTranscript = current.originalTranscript,
                        aiSummary = sessionSummaryDraft.ifBlank { null },
                        aiKeyPoints = sessionKeyPointsDraft.ifBlank { null },
                        aiQuizJson = current.aiQuizJson,
                        tags = sessionTagsDraft,
                        isPublished = sessionPublishedDraft,
                        lastEditedMs = System.currentTimeMillis(),
                        sortOrder = current.sortOrder
                    )
                )
            }
            selectedSession = updated
            sessions = sessions.map {
                if (it.id == updated.id) it.copy(
                    title = updated.title,
                    courseName = updated.courseName,
                    professorName = updated.professorName,
                    tags = updated.tags,
                    isPublished = updated.isPublished,
                    lastEditedMs = updated.lastEditedMs,
                    version = updated.version
                ) else it
            }
            editingSession = null
            status = "محتوای جلسه ذخیره شد؛ متن اصلی بدون تغییر باقی ماند."
        }
    }

    fun reorderSessionInClass(session: BackendSessionSummaryDto, direction: String) {
        if (!("super_admin" in accountRoles || ("teacher" in accountRoles && selectedClass?.ownerUserId == accountId))) return
        withFreshToken { token ->
            val reordered = withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().reorderSession(token, session.id, direction)
            }
            val refreshed = withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().loadSessions(token, session.classId)
            }
            sessions = refreshed.sortedWith(compareBy<BackendSessionSummaryDto> { it.sortOrder }.thenBy { it.dateMs ?: Long.MAX_VALUE }.thenBy { it.title })
            selectedSession = selectedSession?.let { detail ->
                if (detail.id == reordered.id) detail.copy(sortOrder = reordered.sortOrder, version = reordered.version) else detail
            }
            status = "ترتیب جلسه‌ها به‌روزرسانی شد."
        }
    }

    fun reorderClassInTeacher(clazz: BackendClassDto, direction: String) {
        if (!("super_admin" in accountRoles || ("teacher" in accountRoles && clazz.ownerUserId == accountId))) return
        val siblings = classes.filter { it.ownerUserId == clazz.ownerUserId && it.folderId == clazz.folderId }
            .sortedWith(compareBy<BackendClassDto> { it.sortOrder }.thenBy { it.title })
        val index = siblings.indexOfFirst { it.id == clazz.id }
        val target = siblings.getOrNull(index + if (direction == "up") -1 else 1) ?: return
        withFreshToken { token ->
            val api = DarsyarReadOnlyApiFactory.create()
            suspend fun updateSort(item: BackendClassDto, sortOrder: Int) = withContext(Dispatchers.IO) {
                api.updateClass(token, item.id, BackendClassWriteRequest(
                    title = item.title,
                    description = item.description,
                    term = item.term,
                    visibility = item.visibility,
                    isPublished = item.isPublished,
                    isHidden = item.isHidden,
                    sortOrder = sortOrder,
                    folderId = item.folderId
                ))
            }
            val moved = updateSort(clazz, target.sortOrder)
            try {
                updateSort(target, clazz.sortOrder)
            } catch (error: Throwable) {
                updateSort(clazz, clazz.sortOrder)
                throw error
            }
            classes = withContext(Dispatchers.IO) { api.loadClasses(token) }
                .sortedWith(compareBy<BackendClassDto> { it.ownerUserId }.thenBy { it.sortOrder }.thenBy { it.title })
            selectedClass = selectedClass?.let { selected -> classes.find { it.id == selected.id } ?: selected }
            status = "ترتیب کلاس‌ها به‌روزرسانی شد."
        }
    }

    fun openDeletedSessions() {
        showDeletedSessions = true
        withFreshToken { token ->
            deletedSessions = withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().loadDeletedSessions(token)
            }
        }
    }

    fun deleteSelectedSession() {
        val target = confirmDeleteSession ?: return
        withFreshToken { token ->
            withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().deleteSession(token, target.id)
            }
            sessions = sessions.filterNot { it.id == target.id }
            if (selectedSession?.id == target.id) {
                selectedSession = null
                selectedSessionState = null
                sessionNotes = emptyList()
            }
            deletedSessions = withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().loadDeletedSessions(token)
            }
            confirmDeleteSession = null
            status = "جلسه به سطل بازیافت منتقل شد؛ امکان بازیابی آن وجود دارد."
        }
    }

    fun restoreDeletedSession(target: BackendSessionSummaryDto) {
        withFreshToken { token ->
            withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().restoreSession(token, target.id)
            }
            deletedSessions = deletedSessions.filterNot { it.id == target.id }
            if (selectedClass?.id == target.classId) {
                sessions = withContext(Dispatchers.IO) {
                    DarsyarReadOnlyApiFactory.create().loadSessions(token, target.classId)
                }
            }
            status = "جلسه بازیابی شد."
        }
    }

    fun moveSelectedSession() {
        val session = movingSession ?: return
        if (moveTargetClassId.isBlank() || moveTargetClassId == session.classId) {
            status = "کلاس مقصد را انتخاب کنید."
            return
        }
        withFreshToken { token ->
            val result = withContext(Dispatchers.IO) {
                val repository = DarsyarReadOnlyApiFactory.create()
                val moved = repository.moveSession(token, session.id, moveTargetClassId)
                // Reload the source list from the server instead of relying on a local removal.
                val refreshedSourceSessions = if (selectedClass?.id == session.classId) {
                    repository.loadSessions(token, session.classId)
                } else null
                moved to refreshedSourceSessions
            }
            val moved = result.first
            result.second?.let { sessions = it }
            if (selectedSession?.id == session.id) {
                selectedSession = null
                selectedSessionState = null
                sessionNotes = emptyList()
                activeNoteId = null
            }
            movingSession = null
            moveTargetClassId = ""
            status = "جلسه «${moved.title}» به کلاس مقصد منتقل شد. برای مشاهدهٔ آن، کلاس مقصد را باز کنید."
        }
    }

    fun savePersonalNote() {
        val detail = selectedSession ?: return
        if (noteDraft.isBlank()) return
        val matchedStart = noteQuote.takeIf { it.isNotBlank() }?.let { quote ->
            val original = detail.originalTranscript.indexOf(quote)
            if (original >= 0) original else detail.transcript.indexOf(quote).takeIf { it >= 0 }
        }
        val start = noteStartOffset ?: matchedStart
        val end = noteEndOffset ?: start?.plus(noteQuote.length)
        withFreshToken { token ->
            withContext(Dispatchers.IO) {
                val repository = DarsyarCommunityApiFactory.create()
                if (editingNoteId == null) repository.createSessionNote(token, detail.classId, detail.id, noteQuote, noteDraft, start, end)
                else repository.updateSessionNote(token, editingNoteId!!, noteQuote, noteDraft, start, end)
            }
            sessionNotes = withContext(Dispatchers.IO) {
                DarsyarCommunityApiFactory.create().sessionNotes(token, detail.classId, detail.id)
            }
            noteQuote = ""; noteDraft = ""; noteStartOffset = null; noteEndOffset = null
            activeNoteId = null; originalTranscriptSelection = TextRange.Zero; editedTranscriptSelection = TextRange.Zero; editingNoteId = null
            status = "یادداشت شخصی ذخیره شد."
        }
    }

    fun deletePersonalNote(noteId: String) {
        val detail = selectedSession ?: return
        withFreshToken { token ->
            withContext(Dispatchers.IO) { DarsyarCommunityApiFactory.create().deleteSessionNote(token, noteId) }
            sessionNotes = withContext(Dispatchers.IO) {
                DarsyarCommunityApiFactory.create().sessionNotes(token, detail.classId, detail.id)
            }
            if (editingNoteId == noteId) { editingNoteId = null; noteQuote = ""; noteDraft = ""; noteStartOffset = null; noteEndOffset = null }
            if (activeNoteId == noteId) activeNoteId = null
            status = "یادداشت حذف شد."
        }
    }

    fun saveSessionState(isFavorite: Boolean, positionMs: Long) {
        val detail = selectedSession ?: return
        withFreshToken { token ->
            val saved = withContext(Dispatchers.IO) {
                DarsyarCommunityApiFactory.create().saveSessionState(
                    token, detail.classId, detail.id, isFavorite, positionMs.coerceIn(0L, 604_800_000L)
                )
            }
            selectedSessionState = saved
            playbackPositionDraft = saved.lastPlaybackPositionMs.toFloat()
            status = "علاقه‌مندی و پیشرفت شخصی ذخیره شد."
        }
    }

    LaunchedEffect(Unit) { loadDirectory() }

    LaunchedEffect(pendingGlobalNoteFocusId, selectedSession, sessionNotes) {
        val noteId = pendingGlobalNoteFocusId ?: return@LaunchedEffect
        val note = sessionNotes.firstOrNull { it.id == noteId } ?: return@LaunchedEffect
        val detail = selectedSession ?: return@LaunchedEffect
        delay(180)
        val originalMatch = detail.originalTranscript.indexOf(note.quotedText)
        if (note.quotedText.isNotBlank()) {
            if (originalMatch >= 0) originalTranscriptRequester.bringIntoView()
            else editedTranscriptRequester.bringIntoView()
        }
        pendingGlobalNoteFocusId = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("کلاس‌های آنلاین و تالار گفتگو", fontWeight = FontWeight.Bold)
                        Text("استاد ← کلاس ← جلسه · تالار و اطلاعیه‌ها", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
                actions = {
                    TextButton(onClick = { loadMyNotes() }, enabled = !busy) { Text("یادداشت‌های من") }
                    IconButton(onClick = { showNotifications = true }) {
                        BadgedBox(badge = {
                            if (unreadNotificationCount > 0) {
                                Badge { Text(if (unreadNotificationCount > 99) "۹۹+" else unreadNotificationCount.toString()) }
                            }
                        }) {
                            Icon(Icons.Default.Notifications, contentDescription = "اعلان‌ها")
                        }
                    }
                    IconButton(onClick = { loadDirectory() }, enabled = !busy) {
                        Icon(Icons.Default.Cloud, contentDescription = "دریافت دوباره")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Cloud, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text(
                    "در این بخشِ اندروید، کلاس عمومیِ منتشرشده برای حساب واردشده بدون عضویت قابل مشاهده است؛ کلاس خصوصی فقط برای اعضای فعال نمایش داده می‌شود. مشاهده مهمان بدون ورود از مسیر عمومی وب انجام می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                BackendTeacherApplicationsPanel(
                    accountRoles = accountRoles,
                    onStatus = { status = it }
                )
            }
            item { Text("۱. استاد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (teachers.isEmpty() && !busy) {
                item { Text("استادی با کلاس قابل مشاهده پیدا نشد.", style = MaterialTheme.typography.bodySmall) }
            }
            items(teachers, key = { it.userId }) { teacher ->
                Card(
                    Modifier.fillMaxWidth().clickable {
                        selectedTeacherId = teacher.userId
                        selectedFolderId = null
                        selectedClass = null
                        sessions = emptyList()
                        selectedSession = null
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedTeacherId == teacher.userId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(teacher.displayName.ifBlank { "استاد" }, fontWeight = FontWeight.SemiBold)
                            Text("${classes.count { it.ownerUserId == teacher.userId }} کلاس", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (selectedTeacherId != null) {
                val folderOwnerId = selectedTeacherId
                val canManageTeacherFolders = "super_admin" in accountRoles ||
                    ("teacher" in accountRoles && folderOwnerId == accountId)
                val visibleTeacherFolders = teacherFolders.filter {
                    it.ownerUserId == folderOwnerId && (canManageTeacherFolders || !it.isHidden)
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("پوشه‌های استاد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (canManageTeacherFolders) {
                            TextButton(onClick = {
                                editingFolder = null
                                folderNameDraft = ""
                                folderParentDraft = null
                                showFolderDialog = true
                            }, enabled = !busy) { Text("پوشه جدید") }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedFolderId == null,
                            onClick = { selectedFolderId = null },
                            label = { Text("همه") }
                        )
                        visibleTeacherFolders.sortedWith(compareBy<BackendTeacherFolderDto> { folderDepth(it) }.thenBy { it.sortOrder })
                            .forEach { folder ->
                                FilterChip(
                                    selected = selectedFolderId == folder.id,
                                    onClick = { selectedFolderId = if (selectedFolderId == folder.id) null else folder.id },
                                    label = { Text(("　".repeat(folderDepth(folder))) + (if (folderDepth(folder) > 0) "↳ " else "") + folder.name) }
                                )
                                if (canManageTeacherFolders) {
                                    TextButton(onClick = {
                                        editingFolder = folder
                                        folderNameDraft = folder.name
                                        folderParentDraft = folder.parentFolderId
                                        showFolderDialog = true
                                    }, enabled = !busy) { Text("ویرایش") }
                                }
                            }
                    }
                }
            }

            val teacherId = selectedTeacherId
            if (teacherId != null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("۲. کلاس‌های استاد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        if ("super_admin" in accountRoles || ("teacher" in accountRoles && teacherId == accountId)) {
                            TextButton(onClick = { openDeletedClasses() }, enabled = !busy) { Text("کلاس‌های حذف‌شده") }
                        }
                        TextButton(onClick = { selectedTeacherId = null; selectedClass = null; sessions = emptyList(); selectedSession = null }) {
                            Text("بازگشت")
                        }
                    }
                }
                val canSeeHiddenFolders = "super_admin" in accountRoles || ("teacher" in accountRoles && teacherId == accountId)
                val visibleFolderIds = teacherFolders.filter {
                    it.ownerUserId == teacherId && (canSeeHiddenFolders || !it.isHidden)
                }.map { it.id }.toSet()
                val selectedFolderTree = folderAndDescendantIds(selectedFolderId)
                val teacherClasses = classes.filter { clazz ->
                    val folderIsVisible = clazz.folderId == null || clazz.folderId in visibleFolderIds
                    clazz.ownerUserId == teacherId && folderIsVisible &&
                        (selectedFolderTree == null || clazz.folderId in selectedFolderTree)
                }.sortedBy { it.sortOrder }
                if (teacherClasses.isEmpty()) item { Text("کلاسی برای این استاد ثبت نشده است.", style = MaterialTheme.typography.bodySmall) }
                itemsIndexed(teacherClasses, key = { _, clazz -> clazz.id }) { _, clazz ->
                    val reorderableSiblings = teacherClasses.filter { it.folderId == clazz.folderId }
                        .sortedWith(compareBy<BackendClassDto> { it.sortOrder }.thenBy { it.title })
                    val classOrderIndex = reorderableSiblings.indexOfFirst { it.id == clazz.id }
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedClass?.id == clazz.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.weight(1f).clickable { openClass(clazz) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(clazz.title, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${if (clazz.visibility == "public") "عمومی · بدون عضویت" else "خصوصی · عضویت لازم"} · ${if (clazz.isPublished) "منتشرشده" else "منتشرنشده"}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text("جلسه‌ها", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            }
                            if ("super_admin" in accountRoles || ("teacher" in accountRoles && clazz.ownerUserId == accountId)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    TextButton(onClick = { reorderClassInTeacher(clazz, "up") }, enabled = !busy && classOrderIndex > 0) { Text("↑") }
                                    TextButton(onClick = { reorderClassInTeacher(clazz, "down") }, enabled = !busy && classOrderIndex >= 0 && classOrderIndex < reorderableSiblings.lastIndex) { Text("↓") }
                                    TextButton(onClick = { beginEditClass(clazz) }, enabled = !busy) { Text("مدیریت") }
                                }
                            }
                        }
                    }
                }
            }

            selectedClass?.let { clazz ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("۳. جلسه‌های «${clazz.title}»", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if ("super_admin" in accountRoles || ("teacher" in accountRoles && clazz.ownerUserId == accountId)) {
                            TextButton(onClick = { openDeletedSessions() }, enabled = !busy) { Text("سطل بازیافت") }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { selectedClass = null; sessions = emptyList(); selectedSession = null }) {
                            Text("بازگشت")
                        }
                    }
                }
                if (sessions.isEmpty() && !busy) item { Text("جلسه‌ای برای این کلاس ثبت نشده است.", style = MaterialTheme.typography.bodySmall) }
                itemsIndexed(sessions, key = { _, session -> session.id }) { sessionIndex, session ->
                    Card(
                        Modifier.fillMaxWidth().clickable { openSession(session) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedSession?.id == session.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(session.title, fontWeight = FontWeight.SemiBold)
                                if (session.courseName.isNotBlank()) Text("درس: ${session.courseName}", style = MaterialTheme.typography.bodySmall)
                            }
                            if ("super_admin" in accountRoles || ("teacher" in accountRoles && selectedClass?.ownerUserId == accountId)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    TextButton(
                                        onClick = { reorderSessionInClass(session, "up") },
                                        enabled = !busy && sessionIndex > 0
                                    ) { Text("↑") }
                                    TextButton(
                                        onClick = { reorderSessionInClass(session, "down") },
                                        enabled = !busy && sessionIndex < sessions.lastIndex
                                    ) { Text("↓") }
                                }
                                TextButton(
                                    onClick = { confirmDeleteSession = session },
                                    enabled = !busy
                                ) { Text("حذف") }
                                TextButton(
                                    onClick = {
                                        movingSession = session
                                        moveTargetClassId = classes.firstOrNull { it.id != session.classId && ("super_admin" in accountRoles || it.ownerUserId == accountId) }?.id.orEmpty()
                                    },
                                    enabled = !busy && classes.any { it.id != session.classId && ("super_admin" in accountRoles || it.ownerUserId == accountId) }
                                ) { Text("انتقال") }
                            }
                            Text("مشاهده", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                item {
                    BackendClassAccessPanel(
                        classId = clazz.id,
                        isPrivate = clazz.visibility == "private",
                        canManage = "super_admin" in accountRoles || ("teacher" in accountRoles && clazz.ownerUserId == accountId),
                        onStatus = { status = it }
                    )
                }
                item {
                    BackendAnnouncementsPanel(
                        classId = clazz.id,
                        canManage = "super_admin" in accountRoles || ("teacher" in accountRoles && clazz.ownerUserId == accountId),
                        onStatus = { status = it }
                    )
                }
                item {
                    BackendForumPanel(
                        classId = clazz.id,
                        initialTopicId = notificationTopicId,
                        initialPostId = notificationPostId,
                        initialOpenRequestId = notificationOpenSequence,
                        sessionId = selectedSession?.id,
                        currentUserId = accountId,
                        canModerate = "super_admin" in accountRoles || ("teacher" in accountRoles && clazz.ownerUserId == accountId),
                        onStatus = { status = it },
                        onOpenSession = { sessionId ->
                            scope.launch {
                                while (busy) delay(50)
                                sessions.firstOrNull { it.id == sessionId }?.let { linkedSession -> openSession(linkedSession) }
                                    ?: run { status = "جلسه مرتبط با اعلان در این کلاس پیدا نشد." }
                            }
                        }
                    )
                }
            }

            selectedSession?.let { detail ->
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("محتوای جلسه انتخاب‌شده", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if ("super_admin" in accountRoles || ("teacher" in accountRoles && selectedClass?.ownerUserId == accountId)) {
                                OutlinedButton(
                                    onClick = { beginEditSession(detail) },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("ویرایش محتوای جلسه") }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (selectedSessionState?.isFavorite == true) "این جلسه در علاقه‌مندی‌های شماست." else "این جلسه هنوز ذخیره نشده است.",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(
                                    enabled = !busy && selectedSessionState != null,
                                    onClick = {
                                        saveSessionState(
                                            selectedSessionState?.isFavorite != true,
                                            playbackPositionDraft.toLong()
                                        )
                                    }
                                ) {
                                    Text(if (selectedSessionState?.isFavorite == true) "حذف از علاقه‌مندی‌ها" else "افزودن به علاقه‌مندی‌ها")
                                }
                            }
                            if (detail.audioDurationMs > 0L) {
                                val duration = detail.audioDurationMs.coerceAtMost(604_800_000L)
                                Text(
                                    "پیشرفت شنیدن: ${(playbackPositionDraft.toLong() / 60_000L)} از ${(duration / 60_000L)} دقیقه",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Slider(
                                    value = playbackPositionDraft.coerceIn(0f, duration.toFloat()),
                                    onValueChange = { playbackPositionDraft = it },
                                    valueRange = 0f..duration.toFloat(),
                                    enabled = !busy
                                )
                                Button(
                                    enabled = !busy && selectedSessionState != null,
                                    onClick = {
                                        saveSessionState(
                                            selectedSessionState?.isFavorite == true,
                                            playbackPositionDraft.toLong()
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("ذخیره پیشرفت شنیدن") }
                            }
                            HorizontalDivider()
                            Text("یادداشت‌های شخصی من (${sessionNotes.size})", fontWeight = FontWeight.SemiBold)
                            if (sessionNotes.isEmpty()) {
                                Text("هنوز یادداشتی برای این جلسه ثبت نکرده‌اید.", style = MaterialTheme.typography.bodySmall)
                            }
                            sessionNotes.forEach { note ->
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                                    containerColor = if (activeNoteId == note.id) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface
                                )) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (note.quotedText.isNotBlank()) {
                                            Text("عبارت مرتبط: ${note.quotedText}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                        }
                                        Text(note.noteText, style = MaterialTheme.typography.bodyMedium)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(onClick = {
                                                activeNoteId = note.id
                                                val originalMatch = detail.originalTranscript.indexOf(note.quotedText)
                                                scope.launch {
                                                    if (originalMatch >= 0) originalTranscriptRequester.bringIntoView()
                                                    else editedTranscriptRequester.bringIntoView()
                                                }
                                            }, enabled = note.quotedText.isNotBlank()) { Text("رفتن به متن") }
                                            TextButton(onClick = {
                                                editingNoteId = note.id
                                                noteQuote = note.quotedText
                                                noteDraft = note.noteText
                                                noteStartOffset = note.startOffset
                                                noteEndOffset = note.endOffset
                                            }) { Text("ویرایش") }
                                            TextButton(enabled = !busy, onClick = { deletePersonalNote(note.id) }) { Text("حذف") }
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = noteQuote, onValueChange = { noteQuote = it; noteStartOffset = null; noteEndOffset = null },
                                modifier = Modifier.fillMaxWidth(), minLines = 2,
                                label = { Text("عبارت مرتبط از متن درس (اختیاری)") }
                            )
                            OutlinedTextField(
                                value = noteDraft, onValueChange = { noteDraft = it },
                                modifier = Modifier.fillMaxWidth(), minLines = 3,
                                label = { Text(if (editingNoteId == null) "یادداشت شخصی" else "ویرایش یادداشت") }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { savePersonalNote() }, enabled = !busy && noteDraft.isNotBlank()) {
                                    Text(if (editingNoteId == null) "ذخیره یادداشت" else "ذخیره ویرایش")
                                }
                                if (editingNoteId != null) {
                                    TextButton(onClick = { editingNoteId = null; noteQuote = ""; noteDraft = ""; noteStartOffset = null; noteEndOffset = null }) { Text("انصراف") }
                                }
                            }
                            HorizontalDivider()
                            detail.aiSummary?.takeIf { it.isNotBlank() }?.let {
                                Text("خلاصه هوشمند", fontWeight = FontWeight.SemiBold)
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                            detail.aiKeyPoints?.takeIf { it.isNotBlank() }?.let {
                                Text("کلیدواژه‌ها و نکات", fontWeight = FontWeight.SemiBold)
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                            detail.originalTranscript.takeIf { it.isNotBlank() }?.let { transcript ->
                                Text("متن اصلی", fontWeight = FontWeight.SemiBold)
                                val anchor = sessionNotes.firstOrNull { it.id == activeNoteId }
                                val start = anchor?.let { note ->
                                    if (note.startOffset != null && note.endOffset != null && transcript.substringOrNull(note.startOffset, note.endOffset) == note.quotedText) note.startOffset
                                    else transcript.indexOf(note.quotedText).takeIf { it >= 0 }
                                } ?: -1
                                val annotated = buildAnnotatedString {
                                    append(transcript)
                                    if (start >= 0 && anchor != null) addStyle(SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer), start, (start + anchor.quotedText.length).coerceAtMost(transcript.length))
                                }
                                BasicTextField(
                                    value = TextFieldValue(annotated, selection = originalTranscriptSelection),
                                    onValueChange = { value ->
                                        originalTranscriptSelection = value.selection
                                        if (!value.selection.collapsed) {
                                            val a = value.selection.min.coerceIn(0, transcript.length)
                                            val b = value.selection.max.coerceIn(a, transcript.length)
                                            noteQuote = transcript.substring(a, b); noteStartOffset = a; noteEndOffset = b
                                        }
                                    },
                                    readOnly = true,
                                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(originalTranscriptRequester),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)
                                )
                                Text("برای پیوست‌کردن عبارت، آن را لمس طولانی کنید و دستگیره‌های انتخاب را تنظیم کنید.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("متن ویرایش‌شده", fontWeight = FontWeight.SemiBold)
                            val edited = detail.transcript.ifBlank { "متنی برای این جلسه ثبت نشده است." }
                            val editedAnchor = sessionNotes.firstOrNull { it.id == activeNoteId }
                            val editedStart = editedAnchor?.let { note ->
                                if (note.startOffset != null && note.endOffset != null && edited.substringOrNull(note.startOffset, note.endOffset) == note.quotedText) note.startOffset
                                else edited.indexOf(note.quotedText).takeIf { it >= 0 }
                            } ?: -1
                            val editedAnnotated = buildAnnotatedString {
                                append(edited)
                                if (editedStart >= 0 && editedAnchor != null) addStyle(SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer), editedStart, (editedStart + editedAnchor.quotedText.length).coerceAtMost(edited.length))
                            }
                            BasicTextField(
                                value = TextFieldValue(editedAnnotated, selection = editedTranscriptSelection),
                                onValueChange = { value ->
                                    editedTranscriptSelection = value.selection
                                    if (!value.selection.collapsed) {
                                        val a = value.selection.min.coerceIn(0, edited.length)
                                        val b = value.selection.max.coerceIn(a, edited.length)
                                        noteQuote = edited.substring(a, b); noteStartOffset = a; noteEndOffset = b
                                    }
                                },
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(editedTranscriptRequester),
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmDeleteSession != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDeleteSession = null },
            title = { Text("حذف جلسه") },
            text = { Text("جلسه «${confirmDeleteSession?.title.orEmpty()}» به سطل بازیافت منتقل شود؟ محتوای آن فعلاً برای بازیابی حفظ می‌شود.") },
            confirmButton = {
                TextButton(onClick = { deleteSelectedSession() }, enabled = !busy) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSession = null }, enabled = !busy) { Text("انصراف") }
            }
        )
    }
    if (confirmDeleteClass != null) {
        val target = confirmDeleteClass
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDeleteClass = null },
            title = { Text("انتقال کلاس به سطل بازیافت؟") },
            text = { Text("کلاس «${target?.title.orEmpty()}» از فهرست فعال خارج می‌شود؛ جلسه‌ها و محتوای آن حذف دائمی نمی‌شوند و امکان بازیابی وجود دارد.") },
            confirmButton = {
                TextButton(onClick = { target?.let { deleteClassFromDirectory(it) } }, enabled = !busy) { Text("تأیید حذف") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteClass = null }, enabled = !busy) { Text("انصراف") }
            }
        )
    }
    if (showDeletedClassesDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showDeletedClassesDialog = false },
            title = { Text("کلاس‌های حذف‌شده") },
            text = {
                if (deletedClasses.isEmpty()) {
                    Text("کلاس حذف‌شده‌ای وجود ندارد.")
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        deletedClasses.forEach { deleted ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(deleted.title, modifier = Modifier.weight(1f))
                                TextButton(onClick = { restoreClassFromRecycleBin(deleted) }, enabled = !busy) {
                                    Text("بازیابی")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeletedClassesDialog = false }, enabled = !busy) { Text("بستن") }
            }
        )
    }
    if (showDeletedSessions) {
        AlertDialog(
            onDismissRequest = { showDeletedSessions = false },
            title = { Text("سطل بازیافت جلسه‌ها") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (deletedSessions.isEmpty()) {
                        Text("جلسه حذف‌شده‌ای برای بازیابی وجود ندارد.")
                    } else {
                        deletedSessions.forEach { deleted ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(deleted.title, fontWeight = FontWeight.Medium)
                                    Text("شناسه کلاس: ${deleted.classId.take(8)}…", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = { restoreDeletedSession(deleted) },
                                    enabled = !busy
                                ) { Text("بازیابی") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeletedSessions = false }) { Text("بستن") }
            }
        )
    }
    if (editingSession != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) editingSession = null },
            title = { Text("ویرایش محتوای جلسه") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = sessionTitleDraft,
                        onValueChange = { sessionTitleDraft = it },
                        label = { Text("عنوان جلسه") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sessionCourseDraft,
                        onValueChange = { sessionCourseDraft = it },
                        label = { Text("عنوان درس / موضوع") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sessionProfessorDraft,
                        onValueChange = { sessionProfessorDraft = it },
                        label = { Text("نام استاد") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sessionTranscriptDraft,
                        onValueChange = { sessionTranscriptDraft = it },
                        label = { Text("متن ویرایش‌شده") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8
                    )
                    Text("متن اصلی جلسه تغییر نمی‌کند و به‌عنوان نسخه مرجع حفظ می‌شود.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = sessionSummaryDraft,
                        onValueChange = { sessionSummaryDraft = it },
                        label = { Text("خلاصه هوشمند") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5
                    )
                    OutlinedTextField(
                        value = sessionKeyPointsDraft,
                        onValueChange = { sessionKeyPointsDraft = it },
                        label = { Text("کلیدواژه‌ها و نکات") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5
                    )
                    OutlinedTextField(
                        value = sessionTagsDraft,
                        onValueChange = { sessionTagsDraft = it },
                        label = { Text("برچسب‌ها") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = sessionPublishedDraft, onCheckedChange = { sessionPublishedDraft = it })
                        Text("انتشار جلسه")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { saveSessionChanges() },
                    enabled = !busy && sessionTitleDraft.isNotBlank()
                ) { Text("ذخیره تغییرات") }
            },
            dismissButton = {
                TextButton(onClick = { editingSession = null }, enabled = !busy) { Text("انصراف") }
            }
        )
    }
    if (movingSession != null) {
        val availableTargets = classes.filter { it.id != movingSession?.classId && ("super_admin" in accountRoles || it.ownerUserId == accountId) }
        AlertDialog(
            onDismissRequest = { if (!busy) movingSession = null },
            title = { Text("انتقال جلسه به کلاس دیگر") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("جلسه: ${movingSession?.title.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                    if (availableTargets.isEmpty()) {
                        Text("کلاس مقصد دیگری در دسترس نیست.")
                    } else {
                        availableTargets.forEach { target ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { moveTargetClassId = target.id },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = moveTargetClassId == target.id,
                                    onClick = { moveTargetClassId = target.id }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(target.title, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (target.ownerUserId == accountId) "کلاس شما" else "کلاس استاد دیگر",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { moveSelectedSession() },
                    enabled = !busy && moveTargetClassId.isNotBlank() && availableTargets.any { it.id == moveTargetClassId }
                ) { Text("انتقال") }
            },
            dismissButton = {
                TextButton(onClick = { movingSession = null }, enabled = !busy) { Text("انصراف") }
            }
        )
    }
    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showFolderDialog = false },
            title = { Text(if (editingFolder == null) "ایجاد پوشه استاد" else "ویرایش پوشه استاد") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = folderNameDraft,
                        onValueChange = { folderNameDraft = it },
                        label = { Text("نام پوشه") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("پوشه مادر", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = folderParentDraft == null,
                            onClick = { folderParentDraft = null },
                            label = { Text("بدون والد") }
                        )
                        teacherFolders.filter {
                            it.ownerUserId == (editingFolder?.ownerUserId ?: if ("super_admin" in accountRoles) selectedTeacherId ?: accountId else accountId) &&
                                it.id != editingFolder?.id
                        }.forEach { parent ->
                            FilterChip(
                                selected = folderParentDraft == parent.id,
                                onClick = { folderParentDraft = parent.id },
                                label = { Text(parent.name) }
                            )
                        }
                    }
                    if (editingFolder != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = editingFolder?.isHidden == true,
                                onCheckedChange = { hidden ->
                                    editingFolder = editingFolder?.copy(isHidden = hidden)
                                }
                            )
                            Text("پنهان‌کردن پوشه")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val current = editingFolder
                    withFreshToken { token ->
                        val api = DarsyarReadOnlyApiFactory.create()
                        val request = BackendTeacherFolderWriteRequest(
                            ownerUserId = current?.ownerUserId ?: if ("super_admin" in accountRoles) selectedTeacherId ?: accountId else accountId,
                            parentFolderId = folderParentDraft,
                            name = folderNameDraft.trim(),
                            sortOrder = current?.sortOrder ?: teacherFolders.size,
                            isHidden = current?.isHidden ?: false
                        )
                        val saved = withContext(Dispatchers.IO) {
                            if (current == null) api.createTeacherFolder(token, request)
                            else api.updateTeacherFolder(token, current.id, request)
                        }
                        teacherFolders = if (current == null) teacherFolders + saved
                            else teacherFolders.map { if (it.id == saved.id) saved else it }
                        showFolderDialog = false
                        status = "پوشه استاد ذخیره شد."
                    }
                }, enabled = !busy && folderNameDraft.isNotBlank()) { Text("ذخیره") }
            },
            dismissButton = {
                Row {
                    if (editingFolder != null) {
                        TextButton(onClick = {
                            val target = editingFolder ?: return@TextButton
                            withFreshToken { token ->
                                withContext(Dispatchers.IO) {
                                    DarsyarReadOnlyApiFactory.create().deleteTeacherFolder(token, target.id)
                                }
                                teacherFolders = teacherFolders.filterNot { it.id == target.id }
                                classes = classes.map { if (it.folderId == target.id) it.copy(folderId = null) else it }
                                if (selectedFolderId == target.id) selectedFolderId = null
                                showFolderDialog = false
                                status = "پوشه حذف شد؛ کلاس‌ها حفظ شدند و بدون پوشه باقی ماندند."
                            }
                        }, enabled = !busy) { Text("حذف") }
                    }
                    TextButton(onClick = { showFolderDialog = false }, enabled = !busy) { Text("انصراف") }
                }
            }
        )
    }
    if (editingClass != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) editingClass = null },
            title = { Text("مدیریت کلاس") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = classTitleDraft,
                        onValueChange = { classTitleDraft = it },
                        label = { Text("عنوان کلاس") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = classDescriptionDraft,
                        onValueChange = { classDescriptionDraft = it },
                        label = { Text("توضیحات") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                    OutlinedTextField(
                        value = classTermDraft,
                        onValueChange = { classTermDraft = it },
                        label = { Text("ترم / سال تحصیلی") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("پوشه استاد", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = classFolderDraft == null,
                            onClick = { classFolderDraft = null },
                            label = { Text("بدون پوشه") }
                        )
                        teacherFolders.filter {
                            it.ownerUserId == editingClass?.ownerUserId &&
                                ("super_admin" in accountRoles || ("teacher" in accountRoles && editingClass?.ownerUserId == accountId) || !it.isHidden)
                        }.sortedWith(compareBy<BackendTeacherFolderDto> { folderDepth(it) }.thenBy { it.sortOrder })
                            .forEach { folder ->
                                FilterChip(
                                    selected = classFolderDraft == folder.id,
                                    onClick = { classFolderDraft = folder.id },
                                    label = { Text(("　".repeat(folderDepth(folder))) + (if (folderDepth(folder) > 0) "↳ " else "") + folder.name) }
                                )
                            }
                    }
                    Text("دسترسی کلاس", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = classVisibilityDraft == "public",
                            onClick = { classVisibilityDraft = "public" }
                        )
                        Text("عمومی")
                        Spacer(Modifier.width(12.dp))
                        RadioButton(
                            selected = classVisibilityDraft == "private",
                            onClick = { classVisibilityDraft = "private" }
                        )
                        Text("خصوصی")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = classPublishedDraft, onCheckedChange = { classPublishedDraft = it })
                        Text("انتشار کلاس")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = classHiddenDraft, onCheckedChange = { classHiddenDraft = it })
                        Text("پنهان‌کردن کلاس از فهرست")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { saveClassChanges() }, enabled = !busy && classTitleDraft.isNotBlank()) {
                    Text("ذخیره")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmDeleteClass = editingClass }, enabled = !busy) { Text("حذف کلاس") }
                    TextButton(onClick = { editingClass = null }, enabled = !busy) { Text("انصراف") }
                }
            }
        )
    }
    if (showMyNotesDialog) {
        Dialog(onDismissRequest = { showMyNotesDialog = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("یادداشت‌های من", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showMyNotesDialog = false }) { Text("بستن") }
                    }
                    Text("${myNotes.size} یادداشت در کلاس‌ها و جلسه‌های قابل‌دسترسی", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = myNotesSearch,
                        onValueChange = { myNotesSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("جست‌وجو در یادداشت‌ها") }
                    )
                    val filteredNotes = myNotes.filter { note ->
                        listOf(note.noteText, note.quotedText, note.classTitle, note.sessionTitle, note.courseName, note.teacherName)
                            .any { it.contains(myNotesSearch.trim(), ignoreCase = true) }
                    }
                    if (filteredNotes.isEmpty()) {
                        Text(if (myNotes.isEmpty()) "هنوز یادداشتی ثبت نکرده‌اید." else "نتیجه‌ای برای جست‌وجو پیدا نشد.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredNotes, key = { it.id }) { note ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(note.classTitle, fontWeight = FontWeight.SemiBold)
                                        Text("${note.teacherName} · ${note.sessionTitle}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (note.quotedText.isNotBlank()) Text("«${note.quotedText}»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        Text(note.noteText, style = MaterialTheme.typography.bodyMedium)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton(onClick = { openGlobalNote(note) }, enabled = !busy) { Text("رفتن به جلسه") }
                                            TextButton(onClick = { openGlobalNote(note, editAfterOpen = true) }, enabled = !busy) { Text("ویرایش") }
                                            TextButton(onClick = { deleteGlobalNote(note) }, enabled = !busy) { Text("حذف") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { loadMyNotes() }, enabled = !busy) { Text("تازه‌سازی") }
                    }
                }
            }
        }
    }
    if (showNotifications) BackendNotificationsDialog(
        onDismiss = { showNotifications = false },
        onUnreadCountChanged = { unreadNotificationCount = it },
        onOpenNotification = { notification ->
            showNotifications = false
            val targetClass = classes.firstOrNull { it.id == notification.classId }
            if (targetClass != null) {
                notificationTopicId = notification.topicId
                notificationPostId = notification.postId
                notificationOpenSequence++
                selectedTeacherId = targetClass.ownerUserId
                openClass(targetClass)
                status = if (notification.topicId != null) "کلاس مرتبط باز شد؛ گفت‌وگوی مربوطه در تالار انتخاب می‌شود." else "کلاس مرتبط با اعلان باز شد."
            } else {
                status = "کلاس مرتبط با این اعلان در فهرست فعلی در دسترس نیست."
            }
        }
    )
}


private fun String.substringOrNull(start: Int, end: Int): String? =
    if (start >= 0 && end >= start && end <= length) substring(start, end) else null
