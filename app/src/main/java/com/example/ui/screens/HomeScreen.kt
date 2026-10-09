package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.data.local.ClassEntity
import com.example.data.local.LectureEntity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    userRole: UserRole,
    onNavigateToAdd: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToAiSummary: (Long) -> Unit
) {
    val classes by viewModel.repository.allClasses.collectAsState(initial = emptyList())
    val lectures by viewModel.repository.allLectures.collectAsState(initial = emptyList())
    val isTeacher = userRole == UserRole.TEACHER
    val isOnline = rememberNetworkConnection()
    var showBulkExportDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("درس‌یار", fontWeight = FontWeight.Bold)
                        Text(
                            if (isTeacher) "پنل استاد" else "فضای مطالعه",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier.size(7.dp).background(
                                    if (isOnline) Color(0xFF2E7D32) else Color(0xFFD97706),
                                    androidx.compose.foundation.shape.CircleShape
                                )
                            )
                            Text(
                                if (isOnline) "اینترنت متصل" else "آفلاین",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOnline) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                actions = {
                    if (isTeacher) IconButton(onClick = { showBulkExportDialog = true }) { Icon(Icons.Default.Share, contentDescription = "ارسال گروهی مطالب") }
                    IconButton(onClick = viewModel::openClasses) { Icon(Icons.Default.School, "کلاس‌ها") }
                    IconButton(onClick = { viewModel.navigateTo(Screen.Settings) }) { Icon(Icons.Default.Settings, "تنظیمات") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (isTeacher) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HomeActionCard(
                            Modifier.weight(1f),
                            Icons.Default.AutoAwesome,
                            "خلاصه هوشمند",
                            "خلاصه و کلیدواژه آخرین جلسه"
                        ) {
                            lectures.maxByOrNull { it.dateMillis }?.let { onNavigateToAiSummary(it.id) }
                        }
                        HomeActionCard(
                            Modifier.weight(1f),
                            Icons.Default.School,
                            "کلاس جدید",
                            "ایجاد کلاس و افزودن جلسات",
                            onNavigateToAdd
                        )
                    }
                }
            } else {
                item {
                    Card(Modifier.fillMaxWidth(), RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Column(Modifier.padding(22.dp)) {
                            Text("مطالعه را ادامه دهید", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.height(5.dp))
                            Text("کلاس را انتخاب کنید و وارد جلسه موردنظر شوید.", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f))
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = viewModel::openClasses, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)) {
                                Icon(Icons.Default.School, null)
                                Spacer(Modifier.width(7.dp))
                                Text("مشاهده کلاس‌ها")
                            }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("کلاس‌های من", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            if (isTeacher) "کلاس‌ها را مدیریت و جلسات را ثبت کنید." else "کلاس‌ها و جلسات برای مطالعه",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(classes.size.toString() + " کلاس", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                }
            }

            if (classes.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.School, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(10.dp))
                            Text(if (isTeacher) "کلاس خود را ایجاد کنید" else "هنوز کلاسی در دسترس نیست", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(5.dp))
                            Text(
                                if (isTeacher) "از کارت «کلاس جدید» شروع کنید." else "پس از اضافه شدن کلاس، جلسات اینجا قابل مشاهده خواهند بود.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(classes, key = { it.id }) { classItem ->
                    val classLectures = lectures.filter { it.classId == classItem.id }.sortedByDescending { it.dateMillis }
                    Card(
                        Modifier.fillMaxWidth().clickable { viewModel.openClass(classItem.id) },
                        RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(Modifier.size(48.dp), RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.School, null, tint = MaterialTheme.colorScheme.primary) }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(classItem.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    if (classItem.teacherName.isNotBlank()) {
                                        Text("استاد: " + classItem.teacherName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.weight(1f))
                                Text(classLectures.size.toString() + " جلسه", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(10.dp))
                            if (!isTeacher && classLectures.isNotEmpty()) {
                                val latest = classLectures.first()
                                OutlinedButton(onClick = { onNavigateToDetail(latest.id) }, Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.PlayArrow, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("ادامه مطالعه: " + latest.title)
                                }
                            } else {
                                Text("مشاهده جلسات  ›", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showBulkExportDialog && isTeacher) {
        BulkExportDialog(
            lectures = lectures,
            classes = classes,
            onDismiss = { showBulkExportDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkExportDialog(
    lectures: List<LectureEntity>,
    classes: List<ClassEntity>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var scope by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("all") }
    var selectedTarget by androidx.compose.runtime.remember(scope) { androidx.compose.runtime.mutableStateOf("") }
    val targets: List<Pair<String, String>> = when (scope) {
        "professor" -> lectures.map { it.professorName.trim() }.filter { it.isNotBlank() }.distinct().sorted().map { it to it }
        "course" -> lectures.map { it.courseName.trim() }.filter { it.isNotBlank() }.distinct().sorted().map { it to it }
        "class" -> classes.map { it.id.toString() to it.name }
        else -> emptyList()
    }
    val target = selectedTarget.takeIf { value -> targets.any { it.first == value } } ?: targets.firstOrNull()?.first.orEmpty()
    val selectedLectures = when (scope) {
        "professor" -> lectures.filter { it.professorName.trim() == target }
        "course" -> lectures.filter { it.courseName.trim() == target }
        "class" -> lectures.filter { it.classId?.toString() == target }
        else -> lectures
    }.sortedBy { it.dateMillis }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ارسال گروهی مطالب") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("محدوده مطالب را انتخاب کنید:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                listOf(
                    "all" to "همه مطالب ذخیره‌شده",
                    "professor" to "همه جلسات یک استاد",
                    "course" to "همه جلسات یک درس",
                    "class" to "همه جلسات یک کلاس"
                ).forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = scope == value, onClick = { scope = value; selectedTarget = "" })
                        Text(label, modifier = Modifier.clickable { scope = value; selectedTarget = "" })
                    }
                }
                if (scope != "all") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (scope) {
                            "professor" -> "استاد موردنظر:"
                            "course" -> "درس موردنظر:"
                            else -> "کلاس موردنظر:"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (targets.isEmpty()) {
                        Text("موردی برای انتخاب وجود ندارد.", color = MaterialTheme.colorScheme.error)
                    } else {
                        targets.forEach { (value, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                RadioButton(selected = target == value, onClick = { selectedTarget = value })
                                Text(label, modifier = Modifier.clickable { selectedTarget = value })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("تعداد جلسات انتخاب‌شده: ${selectedLectures.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("Word و PDF شامل عنوان، تاریخ، خلاصه، کلیدواژه‌ها و متن جزوه می‌شوند.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(enabled = selectedLectures.isNotEmpty(), onClick = {
                    runCatching { LectureExport.share(context, selectedLectures, LectureExportFormat.WORD, "مطالب درس‌یار") }
                        .onFailure { Toast.makeText(context, "خروجی Word ناموفق بود: ${it.localizedMessage}", Toast.LENGTH_LONG).show() }
                    if (selectedLectures.isNotEmpty()) onDismiss()
                }) { Text("Word") }
                TextButton(enabled = selectedLectures.isNotEmpty(), onClick = {
                    runCatching { LectureExport.share(context, selectedLectures, LectureExportFormat.PDF, "مطالب درس‌یار") }
                        .onFailure { Toast.makeText(context, "خروجی PDF ناموفق بود: ${it.localizedMessage}", Toast.LENGTH_LONG).show() }
                    if (selectedLectures.isNotEmpty()) onDismiss()
                }) { Text("PDF") }
                TextButton(enabled = selectedLectures.isNotEmpty(), onClick = {
                    runCatching { LectureExport.share(context, selectedLectures, LectureExportFormat.TEXT, "مطالب درس‌یار") }
                        .onFailure { Toast.makeText(context, "اشتراک‌گذاری ناموفق بود: ${it.localizedMessage}", Toast.LENGTH_LONG).show() }
                    if (selectedLectures.isNotEmpty()) onDismiss()
                }) { Text("متن") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
private fun rememberNetworkConnection(): Boolean {
    val context = LocalContext.current
    val connectivityManager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    fun connected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    val online = remember { mutableStateOf(connected()) }
    DisposableEffect(connectivityManager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online.value = connected()
            }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                online.value = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            override fun onLost(network: Network) {
                online.value = connected()
            }
        }
        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
        online.value = connected()
        onDispose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
    }
    return online.value
}

@Composable
private fun HomeActionCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier.clickable(onClick = onClick),
        RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(10.dp))
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
