package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.LectureEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassSessionsScreen(
    classId: Long,
    viewModel: MainViewModel,
    userRole: UserRole,
    onOpenSession: (Long) -> Unit,
    onNewSession: () -> Unit,
    onBack: () -> Unit
) {
    val allLectures by viewModel.repository.allLectures.collectAsState(initial = emptyList())
    val allCourses by viewModel.repository.allCoursesDetailed.collectAsState(initial = emptyList())
    var className by remember { mutableStateOf("کلاس") }
    var teacherName by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf<LectureEntity?>(null) }

    LaunchedEffect(classId) {
        viewModel.repository.getClass(classId)?.let {
            className = it.name
            teacherName = it.teacherName
        }
    }

    val courseIdsForClass = remember(allCourses, classId) {
        allCourses.filter { it.classId == classId }.map { it.id }.toSet()
    }

    val sessions = allLectures
        .filter { it.classId == classId || (it.classId == null && it.courseId in courseIdsForClass) }
        .sortedByDescending { it.dateMillis }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(className, fontWeight = FontWeight.Bold)
                        if (teacherName.isNotBlank()) {
                            Text("استاد: $teacherName", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
        },
        floatingActionButton = {
            if (userRole == UserRole.TEACHER) {
                FloatingActionButton(
                    onClick = onNewSession,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "جلسه جدید")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("جلسات کلاس", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("همه جلسات به ترتیب تاریخ در همین صفحه قرار می‌گیرند.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (sessions.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CalendarToday, contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("هنوز جلسه‌ای برای این کلاس ثبت نشده است.", fontWeight = FontWeight.Bold)
                            if (userRole == UserRole.TEACHER) {
                                Spacer(Modifier.height(6.dp))
                                Text("از دکمه + جلسه اول را ایجاد کنید.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            items(sessions, key = { it.id }) { lecture ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenSession(lecture.id) },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(48.dp), shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.CalendarToday, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(lecture.title, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(PersianDateUtils.format(lecture.dateMillis),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (lecture.audioFilePath != null || !lecture.audioUrl.isNullOrBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AudioFile, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("صوت موجود", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (userRole == UserRole.TEACHER) {
                            IconButton(onClick = { showDeleteDialog = lecture }) {
                                Icon(Icons.Default.Delete, contentDescription = "حذف جلسه")
                            }
                        }
                    }
                }
            }
        }
    }

    showDeleteDialog?.let { lecture ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("حذف جلسه") },
            text = { Text("آیا جلسه «" + lecture.title + "» حذف شود؟") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteLecture(lecture.id) { showDeleteDialog = null }
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("انصراف", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
}
