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
import androidx.compose.material.icons.filled.MoreVert
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
    val isPrimaryTeacher = userRole == UserRole.TEACHER && AuthPreferences.isPrimaryTeacher(context)
    val canManageClasses = userRole == UserRole.TEACHER
    val currentUid = AuthPreferences.currentUid(context).orEmpty()
    val currentTeacherName = AuthPreferences.currentName(context).trim()
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
    } else {
        // استادان عادی همه کلاس‌ها را می‌بینند؛ کنترل مجوز ویرایش در هر ردیف جداست.
        allClasses.filter { it.id != 0L }
    }
    fun canManageClass(item: ClassEntity): Boolean =
        canManageClasses && (
            isPrimaryTeacher ||
                (item.id > 0L && item.ownerUid.isNotBlank() && item.ownerUid == currentUid) ||
                (item.id > 0L && item.ownerUid.isBlank() &&
                    item.teacherName.trim().equals(currentTeacherName, ignoreCase = true))
        )
    // Refresh the published feed whenever a student opens the class list so revoked visibility is applied.
    LaunchedEffect(userRole) {
        if (userRole == UserRole.STUDENT) viewModel.startStudentMaterialsSync()
    }
    var showAdd by remember { mutableStateOf(false) }
    var classToDelete by remember { mutableStateOf<ClassEntity?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf(AuthPreferences.currentName(context)) }
    var term by remember { mutableStateOf("") }
    var editingClass by remember { mutableStateOf<ClassEntity?>(null) }
    var organizationRevision by remember { mutableIntStateOf(0) }
    var selectedFolder by remember { mutableStateOf("همه پوشه‌ها") }
    var showCreateFolder by remember { mutableStateOf(false) }
    var folderNameInput by remember { mutableStateOf("") }
    var moveClassTarget by remember { mutableStateOf<ClassEntity?>(null) }
    var folderMenuExpanded by remember { mutableStateOf(false) }
    var folderActionsExpanded by remember { mutableStateOf(false) }
    var showRenameFolder by remember { mutableStateOf(false) }
    var showDeleteFolder by remember { mutableStateOf(false) }
    var renameFolderInput by remember { mutableStateOf("") }
    val teacherFolders = remember(organizationRevision) { ClassOrganizationPrefs.folders(context) }
    val orderedClasses = remember(classes, organizationRevision) {
        val ids = ClassOrganizationPrefs.orderedClasses(context, classes.map { it.id })
        ids.mapNotNull { id -> classes.firstOrNull { it.id == id } }
    }
    val visibleClasses = remember(orderedClasses, selectedFolder, organizationRevision) {
        orderedClasses.filter { item ->
            selectedFolder == "همه پوشه‌ها" ||
                (selectedFolder == "بدون پوشه" && ClassOrganizationPrefs.folderForClass(context, item.id).isBlank()) ||
                ClassOrganizationPrefs.folderForClass(context, item.id) == selectedFolder
        }
    }

    fun resetForm() {
        name = ""
        teacher = AuthPreferences.currentName(context)
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
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { folderNameInput = ""; showCreateFolder = true }, enabled = canManageClasses) {
                                Text("＋ پوشه استاد")
                            }
                            Box {
                                OutlinedButton(onClick = { folderMenuExpanded = true }) { Text(selectedFolder) }
                                DropdownMenu(expanded = folderMenuExpanded, onDismissRequest = { folderMenuExpanded = false }) {
                                    DropdownMenuItem(text = { Text("همه پوشه‌ها") }, onClick = {
                                        selectedFolder = "همه پوشه‌ها"; folderMenuExpanded = false
                                    })
                                    DropdownMenuItem(text = { Text("بدون پوشه") }, onClick = {
                                        selectedFolder = "بدون پوشه"; folderMenuExpanded = false
                                    })
                                    teacherFolders.forEach { folder ->
                                        DropdownMenuItem(text = { Text(folder) }, onClick = {
                                            selectedFolder = folder; folderMenuExpanded = false
                                        })
                                    }
                                }
                            }
                            if (canManageClasses && selectedFolder != "همه پوشه‌ها" && selectedFolder != "بدون پوشه" && selectedFolder in teacherFolders) {
                                Box {
                                    IconButton(onClick = { folderActionsExpanded = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "مدیریت پوشه")
                                    }
                                    DropdownMenu(expanded = folderActionsExpanded, onDismissRequest = { folderActionsExpanded = false }) {
                                        DropdownMenuItem(text = { Text("تغییر نام پوشه") }, onClick = {
                                            renameFolderInput = selectedFolder
                                            showRenameFolder = true
                                            folderActionsExpanded = false
                                        })
                                        DropdownMenuItem(text = { Text("حذف پوشه") }, onClick = {
                                            showDeleteFolder = true
                                            folderActionsExpanded = false
                                        })
                                    }
                                }
                            }
                        }
                        Text("${visibleClasses.size} کلاس • برای جابه‌جایی از دکمه‌های هر کلاس استفاده کنید",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(visibleClasses, key = { it.id }) { item ->
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
                                val itemFolder = ClassOrganizationPrefs.folderForClass(context, item.id)
                                if (itemFolder.isNotBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text("پوشه: $itemFolder", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("مشاهده جلسات  ›", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
                            if (canManageClass(item)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row {
                                    TextButton(onClick = {
                                        ClassOrganizationPrefs.moveClass(context, visibleClasses.map { it.id }, item.id, -1)
                                        organizationRevision++
                                    }, enabled = visibleClasses.indexOfFirst { it.id == item.id } > 0) { Text("↑") }
                                    TextButton(onClick = {
                                        ClassOrganizationPrefs.moveClass(context, visibleClasses.map { it.id }, item.id, 1)
                                        organizationRevision++
                                    }, enabled = visibleClasses.indexOfFirst { it.id == item.id } < visibleClasses.lastIndex) { Text("↓") }
                                }
                                TextButton(onClick = { moveClassTarget = item }) { Text("پوشه") }
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

    if (showRenameFolder) {
        AlertDialog(
            onDismissRequest = { showRenameFolder = false },
            title = { Text("تغییر نام پوشه") },
            text = {
                OutlinedTextField(
                    value = renameFolderInput,
                    onValueChange = { renameFolderInput = it },
                    label = { Text("نام جدید پوشه") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameFolderInput.isNotBlank(),
                    onClick = {
                        val oldName = selectedFolder
                        if (ClassOrganizationPrefs.renameFolder(context, oldName, renameFolderInput)) {
                            selectedFolder = renameFolderInput.trim()
                            organizationRevision++
                            showRenameFolder = false
                        }
                    }
                ) { Text("ذخیره") }
            },
            dismissButton = { TextButton(onClick = { showRenameFolder = false }) { Text("انصراف") } }
        )
    }

    if (showDeleteFolder) {
        AlertDialog(
            onDismissRequest = { showDeleteFolder = false },
            title = { Text("حذف پوشه") },
            text = {
                Text("پوشه «$selectedFolder» حذف شود؟ کلاس‌های داخل آن حذف نمی‌شوند و به بخش «بدون پوشه» منتقل می‌شوند.")
            },
            confirmButton = {
                TextButton(onClick = {
                    ClassOrganizationPrefs.deleteFolder(context, selectedFolder)
                    selectedFolder = "همه پوشه‌ها"
                    organizationRevision++
                    showDeleteFolder = false
                }) { Text("حذف پوشه") }
            },
            dismissButton = { TextButton(onClick = { showDeleteFolder = false }) { Text("انصراف") } }
        )
    }

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text("ایجاد پوشه استاد") },
            text = {
                OutlinedTextField(
                    value = folderNameInput,
                    onValueChange = { folderNameInput = it },
                    label = { Text("نام پوشه") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ClassOrganizationPrefs.createFolder(context, folderNameInput)) {
                        selectedFolder = folderNameInput.trim()
                        organizationRevision++
                        showCreateFolder = false
                    }
                }, enabled = folderNameInput.isNotBlank() && teacherFolders.none { it.equals(folderNameInput.trim(), true) }) {
                    Text("ایجاد")
                }
            },
            dismissButton = { TextButton(onClick = { showCreateFolder = false }) { Text("انصراف") } }
        )
    }

    moveClassTarget?.let { item ->
        var targetFolder by remember(item.id, organizationRevision) {
            mutableStateOf(ClassOrganizationPrefs.folderForClass(context, item.id))
        }
        AlertDialog(
            onDismissRequest = { moveClassTarget = null },
            title = { Text("انتقال کلاس به پوشه") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = targetFolder.isBlank(), onClick = { targetFolder = "" })
                        Text("بدون پوشه")
                    }
                    teacherFolders.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = targetFolder == folder, onClick = { targetFolder = folder })
                            Text(folder)
                        }
                    }
                    if (teacherFolders.isEmpty()) Text("ابتدا یک پوشه بسازید.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ClassOrganizationPrefs.moveClassToFolder(context, item.id, targetFolder)
                    organizationRevision++
                    moveClassTarget = null
                }) { Text("انتقال") }
            },
            dismissButton = { TextButton(onClick = { moveClassTarget = null }) { Text("انصراف") } }
        )
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
                                teacherName = teacher.trim().ifBlank { currentTeacherName },
                                ownerUid = editingClass?.ownerUid?.ifBlank { currentUid } ?: currentUid,
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
