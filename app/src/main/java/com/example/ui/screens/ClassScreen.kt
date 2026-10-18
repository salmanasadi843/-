package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.local.ClassEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassScreen(
    viewModel: MainViewModel,
    userRole: UserRole,
    onOpenClass: (Long) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val canManageClasses = userRole == UserRole.TEACHER || AuthPreferences.isPrimaryTeacher(context)
    val allClasses by viewModel.repository.allClasses.collectAsState(initial = emptyList())
    // Never expose teacher-local or unpublished classes in student mode.
    val studentSyncStatus by viewModel.studentMaterialsSyncStatus.collectAsState()
    val studentFeedReady = canManageClasses || (
        studentSyncStatus != "مطالب آنلاین هنوز بررسی نشده‌اند." &&
        !studentSyncStatus.contains("در حال دریافت") &&
        !studentSyncStatus.contains("دریافت خودکار مطالب انجام نشد")
    )
    val classes = if (!canManageClasses) {
        if (studentFeedReady) allClasses.filter { it.id < 0L && it.isPublished }
        else emptyList()
    } else allClasses
    // Refresh the published feed whenever a student opens the class list so revoked visibility is applied.
    LaunchedEffect(userRole) { if (userRole == UserRole.STUDENT) viewModel.startStudentMaterialsSync() }
    var showAdd by remember { mutableStateOf(false) }
    var classToDelete by remember { mutableStateOf<ClassEntity?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var term by remember { mutableStateOf("") }
    var editingClass by remember { mutableStateOf<ClassEntity?>(null) }

    fun resetForm() {
        name = ""
        teacher = ""
        term = ""
        editingClass = null
        saveError = null
        isSaving = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("کلاس‌های من", fontWeight = FontWeight.Bold)
                        Text(
                            if (canManageClasses) "مدیریت کلاس‌ها" else "کلاس‌های قابل مطالعه",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
        },
        floatingActionButton = {
            if (canManageClasses) {
                ExtendedFloatingActionButton(
                    onClick = { resetForm(); showAdd = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("کلاس جدید") },
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
    ) { padding ->
        if (classes.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.School, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (canManageClasses) "هنوز کلاسی ایجاد نشده است" else "هنوز کلاسی برای مطالعه وجود ندارد",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (canManageClasses) "اولین کلاس خود را ایجاد کنید." else "پس از اضافه شدن کلاس، جلسات اینجا نمایش داده می‌شوند.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(classes.size.toString() + " کلاس", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(classes, key = { it.id }) { item ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpenClass(item.id) },
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(52.dp), RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.School, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (item.teacherName.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("استاد: " + item.teacherName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(7.dp))
                                if (item.term.isNotBlank()) {
                                    Text(item.term, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("مشاهده جلسات  ›", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
                            if (canManageClasses) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(onClick = {
                                    editingClass = item
                                    name = item.name
                                    teacher = item.teacherName
                                    term = item.term
                                    showAdd = true
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "ویرایش کلاس", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { classToDelete = item }) {
                                    Icon(Icons.Default.Delete, contentDescription = "حذف کلاس", tint = MaterialTheme.colorScheme.error)
                                }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    classToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { classToDelete = null },
            title = { Text("حذف کلاس") },
            text = { Text("کلاس «${item.name}» حذف شود؟ این کار آن را از فهرست کلاس‌های استاد حذف می‌کند.") },
            confirmButton = {
                TextButton(onClick = {
                    classToDelete = null
                    viewModel.deleteClass(item)
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { classToDelete = null }) { Text("انصراف") } }
        )
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(if (editingClass == null) "کلاس جدید" else "ویرایش مشخصات کلاس", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("نام کلاس") }, placeholder = { Text("مثلاً اصول فقه") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(teacher, { teacher = it }, label = { Text("نام استاد") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(term, { term = it }, label = { Text("ترم / نیمسال (اختیاری)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (saveError != null) {
                        Text(saveError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !isSaving,
                    onClick = {
                        saveError = null
                        isSaving = true
                        viewModel.saveClass(
                            ClassEntity(
                                id = editingClass?.id ?: 0L,
                                name = name.trim(),
                                teacherName = teacher.trim(),
                                term = term.trim(),
                                description = editingClass?.description.orEmpty(),
                                classDateMillis = editingClass?.classDateMillis ?: System.currentTimeMillis(),
                                createdAtMillis = editingClass?.createdAtMillis ?: System.currentTimeMillis()
                            ),
                            onSaved = {
                                isSaving = false
                                showAdd = false
                                resetForm()
                            },
                            onFailure = { message ->
                                isSaving = false
                                saveError = message
                            }
                        )
                    }
                ) { Text(if (isSaving) "در حال ذخیره..." else if (editingClass == null) "ایجاد" else "ذخیره تغییرات", color = MaterialTheme.colorScheme.secondary) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("انصراف") } }
        )
    }
}
