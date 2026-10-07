package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
                title = {
                    Column {
                        Text(className, fontWeight = FontWeight.Bold)
                        Text("درس‌های این کلاس", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "بازگشت")
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
                    Icon(Icons.Default.Add, "درس جدید")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (courses.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("هنوز درسی برای این کلاس ثبت نشده است.", fontWeight = FontWeight.Bold)
                            if (userRole == UserRole.TEACHER) {
                                Text(
                                    "برای ایجاد اولین درس، روی دکمه + پایین صفحه بزنید.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            items(courses, key = { it.id }) { course ->
                Card(Modifier.fillMaxWidth().clickable { onOpenCourse(course.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.MenuBook,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(course.name, fontWeight = FontWeight.Bold)
                            if (course.teacherName.isNotBlank()) {
                                Text(
                                    "استاد: " + course.teacherName,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (course.description.isNotBlank()) {
                                Text(course.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (userRole == UserRole.TEACHER) {
                            IconButton(onClick = {
                                editingCourse = course
                                showDialog = true
                            }) {
                                Icon(Icons.Default.Edit, "ویرایش درس")
                            }
                            IconButton(onClick = { showDeleteDialog = course }) {
                                Icon(Icons.Default.Delete, "حذف درس")
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
            onSave = { name, teacher, description ->
                viewModel.saveCourse(
                    CourseEntity(
                        id = editingCourse?.id ?: 0L,
                        classId = classId,
                        name = name.trim(),
                        teacherName = teacher.trim(),
                        description = description.trim(),
                        createdAtMillis = editingCourse?.createdAtMillis ?: System.currentTimeMillis()
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
            text = {
                Text("آیا درس «" + course.name + "» حذف شود؟")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCourse(course)
                    showDeleteDialog = null
                }) {
                    Text("حذف")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("انصراف")
                }
            }
        )
    }
}

@Composable
private fun CourseEditorDialog(
    course: CourseEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var name by remember(course?.id) { mutableStateOf(course?.name ?: "") }
    var teacher by remember(course?.id) { mutableStateOf(course?.teacherName ?: "") }
    var description by remember(course?.id) { mutableStateOf(course?.description ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (course == null) "ایجاد درس جدید" else "ویرایش درس") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("نام درس") },
                    placeholder = { Text("مثلاً: اصول فقه") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = teacher,
                    onValueChange = { teacher = it },
                    label = { Text("نام استاد") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("توضیحات") },
                    minLines = 2
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(name, teacher, description)
            }) {
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
            (course?.name?.isNotBlank() == true &&
                it.courseName.equals(course?.name, ignoreCase = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(course?.name ?: "درس", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "بازگشت")
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
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("جلسات", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (lectures.isEmpty()) {
                item {
                    Text(
                        "هنوز جلسه‌ای به این درس متصل نشده است.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(lectures, key = { it.id }) { lecture ->
                Card(Modifier.fillMaxWidth().clickable { onOpenSession(lecture.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(lecture.title, fontWeight = FontWeight.Bold)
                        Text(PersianDateUtils.format(lecture.dateMillis), style = MaterialTheme.typography.bodySmall)
                        if (lecture.tags.isNotBlank()) {
                            Text(
                                lecture.tags,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
