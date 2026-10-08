package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.CourseEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseScreen(
    classId: Long,
    viewModel: MainViewModel,
    userRole: UserRole,
    onOpenCourse: (Long) -> Unit,
    onBack: () -> Unit
) {
    val courses by viewModel.repository.coursesForClass(classId).collectAsState(initial = emptyList())
    var className by remember { mutableStateOf("کلاس") }
    var showDialog by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<CourseEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf<CourseEntity?>(null) }

    LaunchedEffect(classId) {
        className = viewModel.repository.getClass(classId)?.name ?: "کلاس"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(className, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
        },
        floatingActionButton = {
            if (userRole == UserRole.TEACHER) {
                FloatingActionButton(onClick = {
                    editingCourse = null
                    showDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = "بحث جدید")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (courses.isEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("هنوز بحثی برای این کلاس ثبت نشده است.", fontWeight = FontWeight.Bold)
                            if (userRole == UserRole.TEACHER) {
                                Text(
                                    "برای افزودن بحث، روی دکمه + پایین صفحه بزنید.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            items(courses, key = { it.id }) { course ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenCourse(course.id) }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(46.dp),
                            shape = RoundedCornerShape(13.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(25.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(course.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "تاریخ: " + PersianDateUtils.format(course.createdAtMillis),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (userRole == UserRole.TEACHER) {
                            IconButton(onClick = {
                                editingCourse = course
                                showDialog = true
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "ویرایش بحث")
                            }
                            IconButton(onClick = { showDeleteDialog = course }) {
                                Icon(Icons.Default.Delete, contentDescription = "حذف بحث")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        CourseEditorDialog(
            course = editingCourse,
            onDismiss = {
                showDialog = false
                editingCourse = null
            },
            onSave = { title, dateText ->
                val fallbackDate = editingCourse?.createdAtMillis ?: System.currentTimeMillis()
                val dateMillis = PersianDateUtils.parse(dateText, fallbackDate)
                viewModel.saveCourse(
                    CourseEntity(
                        id = editingCourse?.id ?: 0L,
                        classId = classId,
                        name = title.trim(),
                        teacherName = editingCourse?.teacherName ?: "",
                        description = "",
                        createdAtMillis = dateMillis
                    )
                )
                showDialog = false
                editingCourse = null
            }
        )
    }

    showDeleteDialog?.let { course ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("حذف درس") },
            text = { Text("آیا درس «" + course.name + "» حذف شود؟") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCourse(course)
                    showDeleteDialog = null
                }) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("انصراف") }
            }
        )
    }
}

@Composable
private fun CourseEditorDialog(
    course: CourseEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember(course?.id) { mutableStateOf(course?.name ?: "") }
    var dateText by remember(course?.id) {
        mutableStateOf(PersianDateUtils.format(course?.createdAtMillis ?: System.currentTimeMillis()))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (course == null) "بحث جدید" else "ویرایش بحث") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("عنوان درس") },
                    placeholder = { Text("مثلاً: حجیت خبر واحد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("تاریخ") },
                    placeholder = { Text("مثلاً: ۱۴۰۵/۰۷/۱۵") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onSave(title, dateText) }) {
                Text("ذخیره")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    courseId: Long,
    viewModel: MainViewModel,
    onOpenSession: (Long) -> Unit,
    onNewSession: () -> Unit,
    onBack: () -> Unit
) {
    val allLectures by viewModel.repository.allLectures.collectAsState(initial = emptyList())
    var course by remember { mutableStateOf<CourseEntity?>(null) }

    LaunchedEffect(courseId) {
        course = viewModel.repository.getCourse(courseId)
    }

    val lectures = allLectures.filter {
        it.courseId == courseId ||
            (course?.name?.isNotBlank() == true && it.courseName.equals(course?.name, ignoreCase = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(course?.name ?: "بحث", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت")
                    }
                },
                actions = {
                    if (viewModel.userRole.value == UserRole.TEACHER) {
                        TextButton(onClick = onNewSession) { Text("جلسه جدید") }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (lectures.isEmpty()) {
                item {
                    Text("هنوز جلسه‌ای برای این بحث ثبت نشده است.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(lectures, key = { it.id }) { lecture ->
                Card(Modifier.fillMaxWidth().clickable { onOpenSession(lecture.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(lecture.title, fontWeight = FontWeight.Bold)
                        Text("تاریخ: " + PersianDateUtils.format(lecture.dateMillis), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
