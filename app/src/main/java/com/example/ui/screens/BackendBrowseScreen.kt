package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/** Read-only preview of the future server-backed teacher → class → session hierarchy. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BackendBrowseScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var teachers by remember { mutableStateOf<List<BackendTeacherDto>>(emptyList()) }
    var classes by remember { mutableStateOf<List<BackendClassDto>>(emptyList()) }
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
    var status by remember { mutableStateOf("در حال آماده‌سازی اتصال…") }

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
                val unreadCount = runCatching {
                    DarsyarCommunityApiFactory.create().notifications(token, unreadOnly = true).size
                }.getOrDefault(0)
                Triple(snapshot, directory, unreadCount)
            }
            accountId = result.first.account.userId
            accountRoles = result.first.account.roles
            classes = result.first.classes
            teachers = result.second
            unreadNotificationCount = result.third
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

    fun openGlobalNote(note: BackendGlobalSessionNoteDto) {
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
            status = "جلسه و یادداشت مرتبط باز شد."
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
                    "کلاس عمومی برای کاربرِ واردشده، بدون عضویت قابل مشاهده است؛ مشاهده بدون ورود هنوز فعال نشده است. کلاس خصوصی به عضویت نیاز دارد.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

            val teacherId = selectedTeacherId
            if (teacherId != null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("۲. کلاس‌های استاد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { selectedTeacherId = null; selectedClass = null; sessions = emptyList(); selectedSession = null }) {
                            Text("بازگشت")
                        }
                    }
                }
                val teacherClasses = classes.filter { it.ownerUserId == teacherId }.sortedBy { it.sortOrder }
                if (teacherClasses.isEmpty()) item { Text("کلاسی برای این استاد ثبت نشده است.", style = MaterialTheme.typography.bodySmall) }
                items(teacherClasses, key = { it.id }) { clazz ->
                    Card(
                        Modifier.fillMaxWidth().clickable { openClass(clazz) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedClass?.id == clazz.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    }
                }
            }

            selectedClass?.let { clazz ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("۳. جلسه‌های «${clazz.title}»", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { selectedClass = null; sessions = emptyList(); selectedSession = null }) {
                            Text("بازگشت")
                        }
                    }
                }
                if (sessions.isEmpty() && !busy) item { Text("جلسه‌ای برای این کلاس ثبت نشده است.", style = MaterialTheme.typography.bodySmall) }
                items(sessions, key = { it.id }) { session ->
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
                            Text("مشاهده", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
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
                        sessionId = selectedSession?.id,
                        currentUserId = accountId,
                        canModerate = "super_admin" in accountRoles || ("teacher" in accountRoles && clazz.ownerUserId == accountId),
                        onStatus = { status = it }
                    )
                }
            }

            selectedSession?.let { detail ->
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("محتوای جلسه انتخاب‌شده", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                                            TextButton(onClick = { openGlobalNote(note) }, enabled = !busy) { Text("رفتن به جلسه و متن") }
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
