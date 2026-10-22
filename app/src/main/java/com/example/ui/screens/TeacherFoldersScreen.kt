package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.ClassEntity
import com.example.data.local.AppDatabase
import kotlinx.coroutines.launch

/** Stable grouping key; a teacher UID is preferred over a display name. */
private fun normalizeTeacherLabel(value: String): String =
    value.trim().lowercase()
        .replace('ي', 'ی').replace('ك', 'ک')
        .replace(Regex("[\\u200c\\u200d]"), "")
        .replace(Regex("\\s+"), " ")

internal fun teacherFolderKey(
    clazz: com.example.data.local.ClassEntity,
    currentUid: String,
    currentTeacherName: String = ""
): String {
    // Explicit ownership always wins. Legacy rows without ownerUid must be grouped
    // by their teacher label, not automatically assigned to whichever account is open.
    if (clazz.ownerUid.isNotBlank()) return clazz.ownerUid
    val legacyTeacher = normalizeTeacherLabel(clazz.teacherName)
    val signedInTeacher = normalizeTeacherLabel(currentTeacherName)
    if (clazz.id > 0L && currentUid.isNotBlank() &&
        legacyTeacher.isNotBlank() && signedInTeacher.isNotBlank() &&
        legacyTeacher == signedInTeacher
    ) return currentUid
    return "name:" + legacyTeacher.ifBlank { "بدون-نام" }
}

private const val UNFILED_TEACHERS_KEY = "__unfiled_teachers__"

private data class TeacherFolderRow(
    val key: String,
    val title: String,
    val classes: List<ClassEntity>,
    val unfiled: Boolean = false
)

private fun teacherFolderPrefs(context: Context, uid: String) =
    context.getSharedPreferences("teacher_folders_local_$uid", Context.MODE_PRIVATE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherFoldersScreen(
    viewModel: MainViewModel,
    userRole: UserRole,
    onOpenTeacher: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uid = AuthPreferences.currentUid(context).orEmpty().ifBlank { "local" }
    val prefs = remember(uid) { teacherFolderPrefs(context, uid) }
    val allClasses by viewModel.repository.allClasses.collectAsState(initial = emptyList())
    val syncStatus by viewModel.studentMaterialsSyncStatus.collectAsState()
    val isAdmin = AuthPreferences.isPrimaryTeacher(context)
    val canManage = userRole == UserRole.TEACHER || isAdmin
    val currentTeacherName = AuthPreferences.currentName(context)
    val studentReady = canManage || (
        syncStatus != "مطالب آنلاین هنوز بررسی نشده‌اند." &&
        !syncStatus.contains("در حال دریافت") &&
        !syncStatus.contains("دریافت خودکار مطالب انجام نشد")
    )
    LaunchedEffect(userRole) {
        if (userRole == UserRole.STUDENT) viewModel.startStudentMaterialsSync()
        else viewModel.syncPublishedMaterialsForTeacher()
    }

    var hiddenKeys by remember(uid) { mutableStateOf(prefs.getStringSet("hidden_keys", emptySet())?.toSet().orEmpty()) }
    var deletedKeys by remember(uid) { mutableStateOf(prefs.getStringSet("deleted_keys", emptySet())?.toSet().orEmpty()) }
    var showHidden by remember { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf<TeacherFolderRow?>(null) }
    val scope = rememberCoroutineScope()

    val visibleClasses = remember(allClasses, canManage, studentReady, uid, currentTeacherName) {
        if (!canManage) {
            if (studentReady) allClasses.filter { it.id < 0L && it.isPublished }
            else emptyList()
        } else {
            val owned = allClasses.filter { it.id > 0L && (it.ownerUid == uid || (it.ownerUid.isBlank() && teacherFolderKey(it, uid, currentTeacherName) == uid)) }
            val foreignPositive = allClasses.filter { it.id > 0L && ((it.ownerUid.isNotBlank() && it.ownerUid != uid) || (it.ownerUid.isBlank() && teacherFolderKey(it, uid, currentTeacherName) != uid)) }
            val shared = allClasses.filter { it.id < 0L && it.isPublished }.distinctBy {
                listOf(it.name, it.teacherName, it.term).joinToString("|") { value ->
                    value.trim().lowercase().replace('ي', 'ی').replace('ك', 'ک')
                        .replace(Regex("[\\u200c\\u200d]"), "").replace(Regex("\\s+"), " ")
                }
            }
            owned + foreignPositive + shared
        }
    }

    val rows = remember(visibleClasses, deletedKeys, hiddenKeys, showHidden, uid, currentTeacherName, canManage) {
        val groups = visibleClasses.groupBy { teacherFolderKey(it, uid, currentTeacherName) }
        val teacherRows = groups.entries
            .filter { it.key !in deletedKeys }
            .map { (key, classes) ->
                TeacherFolderRow(
                    key = key,
                    title = classes.firstOrNull { it.teacherName.isNotBlank() }?.teacherName
                        ?: if (key == uid) "کلاس‌های من" else key.removePrefix("name:"),
                    classes = classes
                )
            }
            .toMutableList()

        // Always provide the signed-in teacher a route to create their first class.
        // This is a virtual folder, so it cannot be deleted like a real teacher group.
        if (canManage && uid.isNotBlank() && teacherRows.none { it.key == uid }) {
            teacherRows.add(
                TeacherFolderRow(
                    key = uid,
                    title = AuthPreferences.currentName(context).ifBlank { "کلاس‌های من" },
                    classes = emptyList()
                )
            )
        }
        val visibleTeacherRows = teacherRows
            .filter { showHidden || it.key !in hiddenKeys }
            .sortedBy { it.title.lowercase() }
        val unfiledClasses = visibleClasses.filter { teacherFolderKey(it, uid, currentTeacherName) in deletedKeys }
        val result = visibleTeacherRows.toMutableList()
        if (unfiledClasses.isNotEmpty()) {
            result.add(0, TeacherFolderRow(UNFILED_TEACHERS_KEY, "بدون پوشه", unfiledClasses, true))
        }
        result
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("پوشه‌های استادان", fontWeight = FontWeight.Bold)
                        Text(
                            if (canManage) "کلاس‌های خود و مطالب منتشرشده" else "استادان و کلاس‌های منتشرشده",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
                actions = {
                    if (canManage) {
                        TextButton(onClick = {
                            onOpenTeacher(uid, AuthPreferences.currentName(context).ifBlank { "کلاس‌های من" })
                        }) {
                            Text("کلاس جدید +")
                        }
                    }
                    TextButton(onClick = { showHidden = !showHidden }) {
                        Text(if (showHidden) "پنهان‌ها" else "نمایش پنهان‌ها")
                    }
                }
            )
        }
    ) { padding ->
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (!studentReady && !canManage) "در حال دریافت فهرست استادان…" else "هنوز پوشه‌ای برای نمایش وجود ندارد.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(rows, key = { it.key }) { row ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenTeacher(row.key, row.title) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${row.classes.size} کلاس",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!row.unfiled && (isAdmin || row.key == uid)) {
                                IconButton(onClick = {
                                    if (row.key in hiddenKeys) {
                                        hiddenKeys = hiddenKeys - row.key
                                    } else {
                                        hiddenKeys = hiddenKeys + row.key
                                    }
                                    prefs.edit().putStringSet("hidden_keys", hiddenKeys).apply()
                                }) {
                                    Icon(
                                        if (row.key in hiddenKeys) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (row.key in hiddenKeys) "نمایش پوشه" else "پنهان‌کردن پوشه"
                                    )
                                }
                                if (row.key != uid) {
                                    IconButton(onClick = { folderToDelete = row }) {
                                        Icon(Icons.Default.Delete, contentDescription = "حذف پوشه")
                                    }
                                }
                            }
                        }
                    }
                }
                if (deletedKeys.isNotEmpty()) {
                    item {
                        OutlinedButton(
                            onClick = {
                                deletedKeys = emptySet()
                                prefs.edit().putStringSet("deleted_keys", emptySet()).apply()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("بازگردانی پوشه‌های حذف‌شده") }
                    }
                }
            }
        }
    }

    folderToDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("حذف پوشهٔ «${row.title}»؟") },
            text = {
                Text("فقط پوشه حذف می‌شود؛ کلاس‌ها، جلسات، متن‌ها و فایل‌های صوتی باقی می‌مانند و در «بدون پوشه» نمایش داده می‌شوند. این کار انتشار کلاس را تغییر نمی‌دهد.")
            },
            confirmButton = {
                TextButton(onClick = {
                    // A folder deletion only changes placement; it never deletes classes or sessions.
                    scope.launch {
                        val classDao = AppDatabase.getDatabase(context).classDao()
                        row.classes
                            .filter { it.id > 0L && (isAdmin || it.ownerUid == uid || (it.ownerUid.isBlank() && teacherFolderKey(it, uid, currentTeacherName) == uid)) }
                            .forEach { clazz -> classDao.update(clazz.copy(folderId = null)) }
                    }
                    deletedKeys = deletedKeys + row.key
                    hiddenKeys = hiddenKeys - row.key
                    prefs.edit()
                        .putStringSet("deleted_keys", deletedKeys)
                        .putStringSet("hidden_keys", hiddenKeys)
                        .apply()
                    folderToDelete = null
                }) { Text("حذف پوشه") }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text("انصراف") } }
        )
    }
}
