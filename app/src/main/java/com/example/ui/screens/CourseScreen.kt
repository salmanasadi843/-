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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(className, fontWeight = FontWeight.Bold)
                        if (teacherName.isNotBlank()) {
                            Text("استاد: " + teacherName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت") }
                }
            )
        },
        floatingActionButton = {
            if (userRole == UserRole.TEACHER) {
                ExtendedFloatingActionButton(
                    onClick = onNewSession,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("جلسه جدید") },
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text("جلسات کلاس", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (sessions.isEmpty()) "هنوز جلسه‌ای ثبت نشده است." else sessions.size.toString() + " جلسه — جدیدترین جلسه در ابتدا",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (sessions.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CalendarToday, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(42.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(if (userRole == UserRole.TEACHER) "جلسه اول را ایجاد کنید" else "هنوز جلسه‌ای برای مطالعه وجود ندارد", fontWeight = FontWeight.Bold)
                            if (userRole == UserRole.TEACHER) {
                                Spacer(Modifier.height(5.dp))
                                Text("عنوان و تاریخ جلسه را ثبت کنید و سپس صوت و متن را اضافه کنید.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            items(sessions, key = { it.id }) { lecture ->
                Card(
                    Modifier.fillMaxWidth().clickable { onOpenSession(lecture.id) },
                    RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(1.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(50.dp), RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("جلسه", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(lecture.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(5.dp))
                            Text(PersianDateUtils.format(lecture.dateMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (lecture.audioFilePath != null || !lecture.audioUrl.isNullOrBlank()) {
                                Spacer(Modifier.height(5.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AudioFile, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("صوت موجود", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                TextButton(onClick = { viewModel.deleteLecture(lecture.id) { showDeleteDialog = null } }) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = null }) { Text("انصراف") } }
        )
    }
}
