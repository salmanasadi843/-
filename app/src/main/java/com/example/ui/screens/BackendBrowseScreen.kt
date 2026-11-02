package com.example.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only preview of the future server-backed teacher → class → session hierarchy. */
@OptIn(ExperimentalMaterial3Api::class)
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
    var busy by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf("") }
    var accountRoles by remember { mutableStateOf<List<String>>(emptyList()) }
    var showNotifications by remember { mutableStateOf(false) }
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
                snapshot to repository.loadTeachers(token)
            }
            accountId = result.first.account.userId
            accountRoles = result.first.account.roles
            classes = result.first.classes
            teachers = result.second
            selectedTeacherId = null
            selectedClass = null
            sessions = emptyList()
            selectedSession = null
            status = "فهرست استادان و کلاس‌های قابل مشاهده دریافت شد."
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
        withFreshToken { token ->
            selectedSession = withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().loadSessionDetail(token, session.classId, session.id)
            }
            status = "جزئیات فقط برای جلسه انتخاب‌شده دریافت شد."
        }
    }

    LaunchedEffect(Unit) { loadDirectory() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("مرور ساختار سرور جدید", fontWeight = FontWeight.Bold)
                        Text("استاد ← کلاس ← جلسه · فقط خواندنی", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
                actions = {
                    IconButton(onClick = { showNotifications = true }) {
                        Icon(Icons.Default.Notifications, contentDescription = "اعلان‌ها")
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
                            detail.aiSummary?.takeIf { it.isNotBlank() }?.let {
                                Text("خلاصه هوشمند", fontWeight = FontWeight.SemiBold)
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                            detail.aiKeyPoints?.takeIf { it.isNotBlank() }?.let {
                                Text("کلیدواژه‌ها و نکات", fontWeight = FontWeight.SemiBold)
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                            detail.originalTranscript.takeIf { it.isNotBlank() }?.let {
                                Text("متن اصلی", fontWeight = FontWeight.SemiBold)
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("متن ویرایش‌شده", fontWeight = FontWeight.SemiBold)
                            Text(detail.transcript.ifBlank { "متنی برای این جلسه ثبت نشده است." }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (showNotifications) BackendNotificationsDialog(onDismiss = { showNotifications = false })
}
