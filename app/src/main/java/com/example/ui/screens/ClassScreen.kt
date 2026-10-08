package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    val classes by viewModel.repository.allClasses.collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var term by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(PersianDateUtils.format(System.currentTimeMillis())) }

    fun resetForm() {
        name = ""
        teacher = ""
        term = ""
        dateText = PersianDateUtils.format(System.currentTimeMillis())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("کلاس‌های من", fontWeight = FontWeight.Bold)
                        Text(
                            if (userRole == UserRole.TEACHER) "مدیریت کلاس‌ها" else "کلاس‌های قابل مطالعه",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                            if (userRole == UserRole.TEACHER) "هنوز کلاسی ایجاد نشده است" else "هنوز کلاسی برای مطالعه وجود ندارد",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (userRole == UserRole.TEACHER) "اولین کلاس خود را ایجاد کنید." else "پس از اضافه شدن کلاس، جلسات اینجا نمایش داده می‌شوند.",
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CalendarToday, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                                    Spacer(Modifier.width(5.dp))
                                    Text(PersianDateUtils.format(item.classDateMillis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (item.term.isNotBlank()) {
                                        Spacer(Modifier.width(10.dp))
                                        Text(item.term, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("مشاهده جلسات  ›", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("کلاس جدید", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("نام کلاس") }, placeholder = { Text("مثلاً اصول فقه") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(teacher, { teacher = it }, label = { Text("نام استاد") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(dateText, { dateText = it }, label = { Text("تاریخ کلاس") }, placeholder = { Text("۱۴۰۵/۰۷/۱۶") }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.CalendarToday, null) })
                    OutlinedTextField(term, { term = it }, label = { Text("ترم / نیمسال (اختیاری)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        viewModel.saveClass(
                            ClassEntity(
                                name = name.trim(),
                                teacherName = teacher.trim(),
                                term = term.trim(),
                                classDateMillis = PersianDateUtils.parse(dateText, System.currentTimeMillis())
                            )
                        )
                        showAdd = false
                        resetForm()
                    }
                ) { Text("ایجاد", color = MaterialTheme.colorScheme.secondary) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("انصراف") } }
        )
    }
}
