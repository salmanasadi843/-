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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.example.data.local.AppDatabase
import com.example.data.local.TeacherFolderEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassScreen(
    viewModel: MainViewModel,
    userRole: UserRole,
    teacherKey: String? = null,
    teacherLabel: String? = null,
    onOpenClass: (Long) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val ownerUid = AuthPreferences.currentUid(context).orEmpty()
    val canManageClasses = userRole == UserRole.TEACHER || AuthPreferences.isPrimaryTeacher(context)
    val allClasses by viewModel.repository.allClasses.collectAsState(initial = emptyList())
    // Never expose teacher-local or unpublished classes in student mode.
    val studentSyncStatus by viewModel.studentMaterialsSyncStatus.collectAsState()
    val studentFeedReady = canManageClasses || (
        studentSyncStatus != "مطالب آنلاین هنوز بررسی نشده‌اند." &&
        !studentSyncStatus.contains("در حال دریافت") &&
        !studentSyncStatus.contains("دریافت خودکار مطالب انجام نشد")
    )
    // Keep the student list unchanged (its published-feed deduplication is already verified).
    // In teacher mode, collapse duplicate imported cards by their visible class identity,
    // while never collapsing teacher-owned positive-ID classes.
    val allVisibleClasses = if (!canManageClasses) {
        if (studentFeedReady) allClasses.filter { it.id < 0L && it.isPublished }
        else emptyList()
    } else {
        val ownedClasses = allClasses.filter {
            it.id > 0L && (it.ownerUid == ownerUid || it.ownerUid.isBlank())
        }
        val foreignPositiveClasses = allClasses.filter {
            it.id > 0L && it.ownerUid.isNotBlank() && it.ownerUid != ownerUid
        }
        val sharedClasses = allClasses.filter { it.id < 0L && it.isPublished }
            .distinctBy {
                listOf(it.name, it.teacherName, it.term)
                    .joinToString("|") { value ->
                        value.trim().lowercase()
                            .replace('ي', 'ی')
                            .replace('ك', 'ک')
                            .replace(Regex("[\\u200c\\u200d]"), "")
                            .replace(Regex("\\s+"), " ")
                    }
            }
        ownedClasses + foreignPositiveClasses + sharedClasses
    }
    val folderPrefs = remember(ownerUid) {
        context.getSharedPreferences("teacher_folders_local_${ownerUid.ifBlank { "local" }}", android.content.Context.MODE_PRIVATE)
    }
    val deletedTeacherKeys = remember(teacherKey, ownerUid) {
        folderPrefs.getStringSet("deleted_keys", emptySet())?.toSet().orEmpty()
    }
    val classes = remember(allVisibleClasses, teacherKey, ownerUid, deletedTeacherKeys) {
        when (teacherKey) {
            null -> allVisibleClasses
            "__unfiled_teachers__" -> allVisibleClasses.filter { teacherFolderKey(it, ownerUid.ifBlank { "local" }) in deletedTeacherKeys }
            else -> allVisibleClasses.filter { teacherFolderKey(it, ownerUid.ifBlank { "local" }) == teacherKey }
        }
    }
    // Refresh the published feed whenever a student opens the class list so revoked visibility is applied.
    LaunchedEffect(userRole) {
        when (userRole) {
            UserRole.STUDENT -> viewModel.startStudentMaterialsSync()
            UserRole.TEACHER -> viewModel.syncPublishedMaterialsForTeacher()
        }
    }
    val folderDao = remember(ownerUid) { AppDatabase.getDatabase(context).teacherFolderDao() }
    val classDao = remember { AppDatabase.getDatabase(context).classDao() }
    val folders by remember(ownerUid) { folderDao.observeFolders(ownerUid) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selectedFolderId by remember { mutableStateOf<Long?>(null) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var classToMove by remember { mutableStateOf<ClassEntity?>(null) }
    var selectedClassIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBulkMove by remember { mutableStateOf(false) }
    var classToClaim by remember { mutableStateOf<ClassEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var classToDelete by remember { mutableStateOf<ClassEntity?>(null) }
    var folderToDelete by remember { mutableStateOf<TeacherFolderEntity?>(null) }
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

    fun folderPath(folder: TeacherFolderEntity): String {
        val parent = folders.firstOrNull { it.id == folder.parentFolderId }
        return if (parent == null) folder.name else folderPath(parent) + " / " + folder.name
    }

    val visibleOwnedClasses = classes.filter {
        it.id > 0L &&
            (it.ownerUid == ownerUid || it.ownerUid.isBlank()) &&
            it.folderId == selectedFolderId
    }
    // Shared/foreign classes are kept in a separate read-only section at the root,
    // never mixed into the teacher's own folders.
    val visibleOtherClasses = if (selectedFolderId == null && canManageClasses) {
        classes.filter {
            (it.id > 0L && it.ownerUid.isNotBlank() && it.ownerUid != ownerUid) || it.id < 0L
        }
    } else emptyList()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(teacherLabel ?: "کلاس‌های من", fontWeight = FontWeight.Bold)
                        Text(
                            if (canManageClasses) "مدیریت کلاس‌های استاد" else "کلاس‌های قابل مطالعه",
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
        if (classes.isEmpty() && (!canManageClasses || teacherKey != null)) {
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
                            if (canManageClasses && teacherKey == null) "هنوز کلاسی ایجاد نشده است" else "کلاسی در این پوشه وجود ندارد",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (canManageClasses && teacherKey == null) "اولین کلاس خود را ایجاد کنید." else "پس از انتشار یا اضافه‌شدن کلاس، اینجا نمایش داده می‌شود.",
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
                if (canManageClasses) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    folders.firstOrNull { it.id == selectedFolderId }?.name ?: "پوشهٔ من",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (selectedClassIds.isNotEmpty()) {
                                        TextButton(onClick = { showBulkMove = true }) {
                                            Text("انتقال ${selectedClassIds.size} کلاس")
                                        }
                                    }
                                    TextButton(onClick = { showCreateFolder = true }) { Text("پوشهٔ جدید +") }
                                }
                            }
                            if (selectedFolderId != null) {
                                TextButton(onClick = {
                                    selectedFolderId = folders.firstOrNull { it.id == selectedFolderId }?.parentFolderId
                                }) { Text("← بازگشت به پوشهٔ والد") }
                            }
                        }
                    }
                    items(folders.filter { it.parentFolderId == selectedFolderId }, key = { "folder-${it.id}" }) { folder ->
                        Card(
                            Modifier.fillMaxWidth().clickable { selectedFolderId = folder.id },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("📁", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.width(10.dp))
                                Text(folder.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                IconButton(onClick = { folderToDelete = folder }) {
                                    Icon(Icons.Default.Delete, contentDescription = "حذف پوشه", tint = MaterialTheme.colorScheme.error)
                                }
                                Text("باز کردن  ›", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    item {
                        Text(
                            classes.count { it.id > 0L && (it.ownerUid == ownerUid || it.ownerUid.isBlank()) && it.folderId == selectedFolderId }.toString() + " کلاس در این پوشه",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    item {
                        Text(classes.size.toString() + " کلاس", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(
                    if (canManageClasses) visibleOwnedClasses else classes,
                    key = { it.id }
                ) { item ->
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
                                if (canManageClasses && item.id < 0L) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "مطالب منتشرشده از استاد دیگر • فقط مشاهده",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("مشاهده جلسات  ›", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
                            if (canManageClasses && item.id > 0L && item.ownerUid == ownerUid && ownerUid.isNotBlank()) {
                                Checkbox(
                                    checked = item.id in selectedClassIds,
                                    onCheckedChange = { checked ->
                                        selectedClassIds = if (checked) selectedClassIds + item.id
                                            else selectedClassIds - item.id
                                    }
                                )
                            }
                            if (canManageClasses && item.id > 0L) {
                                when {
                                    item.ownerUid == ownerUid && ownerUid.isNotBlank() -> {
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
                                            IconButton(onClick = { classToMove = item }) {
                                                Icon(Icons.Default.School, contentDescription = "انتقال به پوشه", tint = MaterialTheme.colorScheme.secondary)
                                            }
                                            IconButton(onClick = {
                                                scope.launch {
                                                    if (item.ownerUid == ownerUid && ownerUid.isNotBlank()) {
                                                        classDao.update(item.copy(isPublished = !item.isPublished))
                                                    }
                                                }
                                            }) {
                                                Icon(
                                                    if (item.isPublished) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                    contentDescription = if (item.isPublished) "پنهان‌کردن کلاس از دانشجو" else "نمایش دوباره کلاس برای دانشجو",
                                                    tint = if (item.isPublished) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            IconButton(onClick = { classToDelete = item }) {
                                                Icon(Icons.Default.Delete, contentDescription = "حذف کلاس", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                    item.ownerUid.isBlank() -> {
                                        IconButton(onClick = { classToClaim = item }) {
                                            Icon(Icons.Default.School, contentDescription = "تأیید مالکیت کلاس", tint = MaterialTheme.colorScheme.tertiary)
                                        }
                                    }
                                    else -> {
                                        Text("فقط مشاهده", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                    }
                                }
                            }
                        }
                    }
                }
                if (canManageClasses && visibleOtherClasses.isNotEmpty()) {
                    item {
                        Text(
                            "مطالب استادان دیگر (فقط مشاهده)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    items(visibleOtherClasses, key = { it.id }) { item ->
                        Card(
                            Modifier.fillMaxWidth().clickable { onOpenClass(item.id) },
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(1.dp)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.School, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    if (item.teacherName.isNotBlank()) Text("استاد: " + item.teacherName, style = MaterialTheme.typography.bodySmall)
                                    Text("فقط مشاهده", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text("پوشهٔ جدید") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("نام پوشه") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val nameToSave = newFolderName.trim()
                    if (nameToSave.isNotBlank() && ownerUid.isNotBlank()) {
                        scope.launch {
                            runCatching {
                                folderDao.insert(TeacherFolderEntity(ownerUid = ownerUid, name = nameToSave, parentFolderId = selectedFolderId))
                            }.onSuccess {
                                newFolderName = ""
                                showCreateFolder = false
                            }.onFailure {
                                saveError = "ساخت پوشه ناموفق بود؛ شاید پوشه‌ای با همین نام در این مسیر وجود دارد."
                            }
                        }
                    }
                }, enabled = newFolderName.isNotBlank() && ownerUid.isNotBlank()) { Text("ایجاد") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolder = false }) { Text("انصراف") } }
        )
    }

    classToClaim?.let { item ->
        AlertDialog(
            onDismissRequest = { classToClaim = null },
            title = { Text("تأیید مالکیت کلاس قدیمی") },
            text = {
                Text("شناسهٔ مالک برای کلاس «${item.name}» ثبت نشده است. فقط اگر این کلاس و جلساتش واقعاً متعلق به حساب شماست، مالکیت را تأیید کنید. هیچ جلسه یا متنی حذف نمی‌شود.")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ownerUid.isNotBlank()) {
                        scope.launch {
                            classDao.update(item.copy(ownerUid = ownerUid))
                            classToClaim = null
                        }
                    }
                }, enabled = ownerUid.isNotBlank()) { Text("این کلاس متعلق به من است") }
            },
            dismissButton = { TextButton(onClick = { classToClaim = null }) { Text("انصراف") } }
        )
    }

    classToMove?.let { item ->
        AlertDialog(
            onDismissRequest = { classToMove = null },
            title = { Text("انتقال کلاس به پوشه") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("کلاس «${item.name}» به کدام پوشه منتقل شود؟")
                    TextButton(onClick = {
                        if (item.ownerUid == ownerUid && ownerUid.isNotBlank()) {
                            scope.launch {
                                classDao.update(item.copy(folderId = null))
                                classToMove = null
                            }
                        }
                    }, enabled = item.ownerUid == ownerUid && ownerUid.isNotBlank()) { Text("پوشهٔ اصلی استاد") }
                    folders.forEach { folder ->
                        TextButton(onClick = {
                            if (item.ownerUid == ownerUid && ownerUid.isNotBlank()) {
                                scope.launch {
                                    classDao.update(item.copy(folderId = folder.id))
                                    selectedClassIds = selectedClassIds - item.id
                                    classToMove = null
                                }
                            }
                        }, enabled = item.ownerUid == ownerUid && ownerUid.isNotBlank()) {
                            Text("📁 ${folderPath(folder)}")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { classToMove = null }) { Text("بستن") } }
        )
    }

    if (showBulkMove) {
        AlertDialog(
            onDismissRequest = { showBulkMove = false },
            title = { Text("انتقال کلاس‌های انتخاب‌شده") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${selectedClassIds.size} کلاس انتخاب شده است. مقصد را انتخاب کنید:")
                    TextButton(onClick = {
                        scope.launch {
                            val selected = allClasses.filter {
                                it.id in selectedClassIds && it.id > 0L && it.ownerUid == ownerUid
                            }
                            selected.forEach { classDao.update(it.copy(folderId = null)) }
                            selectedClassIds = emptySet()
                            showBulkMove = false
                        }
                    }) { Text("پوشهٔ اصلی استاد (خارج از پوشه)") }
                    folders.forEach { folder ->
                        TextButton(onClick = {
                            scope.launch {
                                val selected = allClasses.filter {
                                    it.id in selectedClassIds && it.id > 0L && it.ownerUid == ownerUid
                                }
                                selected.forEach { classDao.update(it.copy(folderId = folder.id)) }
                                selectedClassIds = emptySet()
                                showBulkMove = false
                            }
                        }) { Text("📁 ${folderPath(folder)}") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showBulkMove = false }) { Text("انصراف") } }
        )
    }

    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("حذف پوشه") },
            text = {
                Text("پوشهٔ «${folder.name}» حذف شود؟ کلاس‌ها و زیرپوشه‌ها حذف نمی‌شوند؛ به پوشهٔ والد منتقل می‌شوند.")
            },
            confirmButton = {
                TextButton(onClick = {
                    folderToDelete = null
                    scope.launch {
                        val owned = folderDao.getOwnedFolder(folder.id, ownerUid)
                        if (owned != null && owned.ownerUid == ownerUid && ownerUid.isNotBlank()) {
                            allClasses.filter { it.folderId == folder.id && it.id > 0L && it.ownerUid == ownerUid }
                                .forEach { classDao.update(it.copy(folderId = folder.parentFolderId)) }
                            folders.filter { it.parentFolderId == folder.id }
                                .forEach { folderDao.update(it.copy(parentFolderId = folder.parentFolderId)) }
                            folderDao.delete(owned)
                            if (selectedFolderId == folder.id) selectedFolderId = folder.parentFolderId
                        }
                    }
                }) { Text("حذف پوشه", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text("انصراف") } }
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
                                teacherName = teacher.trim(),
                                term = term.trim(),
                                description = editingClass?.description.orEmpty(),
                                classDateMillis = editingClass?.classDateMillis ?: System.currentTimeMillis(),
                                createdAtMillis = editingClass?.createdAtMillis ?: System.currentTimeMillis(),
                                ownerUid = editingClass?.ownerUid ?: ownerUid,
                                folderId = editingClass?.folderId ?: selectedFolderId
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
