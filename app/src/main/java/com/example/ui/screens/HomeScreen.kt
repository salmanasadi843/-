package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BluePrimaryContainerLight
import com.example.ui.theme.BluePrimaryLight
import com.example.ui.theme.BackgroundLight
import com.example.ui.theme.OnBackgroundLight
import com.example.ui.theme.SurfaceLight
import com.example.ui.theme.PurplePrimaryContainerLight
import com.example.ui.theme.PurplePrimaryLight

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
    val lectures by viewModel.lectures.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val recentLectures = lectures.take(5)
    val isTeacher = userRole == UserRole.TEACHER

    val rolePrimary = if (isTeacher) PurplePrimaryLight else BluePrimaryLight
    val rolePrimaryContainer =
        if (isTeacher) PurplePrimaryContainerLight else BluePrimaryContainerLight

    val roleOnPrimaryContainer =
        if (isTeacher) com.example.ui.theme.PurpleOnPrimaryContainerLight
        else com.example.ui.theme.BlueOnPrimaryContainerLight

    val roleScheme = MaterialTheme.colorScheme.copy(
        primary = rolePrimary,
        primaryContainer = rolePrimaryContainer,
        onPrimaryContainer = roleOnPrimaryContainer,
        background = BackgroundLight,
        onBackground = OnBackgroundLight,
        surface = SurfaceLight
    )

    MaterialTheme(colorScheme = roleScheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("درس‌یار", fontWeight = FontWeight.Bold)
                            Text(
                                if (isTeacher) "فضای استاد" else "فضای شاگرد",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                if (isTeacher) "استاد" else "شاگرد",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        IconButton(onClick = viewModel::openClasses) {
                            Icon(Icons.Default.School, "کلاس‌ها")
                        }
                        IconButton(onClick = { viewModel.navigateTo(Screen.Settings) }) {
                            Icon(Icons.Default.Settings, "تنظیمات")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = if (isTeacher) onNavigateToAdd else viewModel::openClasses,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        if (isTeacher) Icons.Default.Add else Icons.Default.School,
                        if (isTeacher) "درس جدید" else "کلاس‌ها"
                    )
                }
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    if (isTeacher) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Card(
                                Modifier
                                    .weight(1f)
                                    .clickable {
                                        recentLectures.firstOrNull()?.let { lecture -> onNavigateToAiSummary(lecture.id) }
                                    },
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(Modifier.padding(18.dp)) {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text("خلاصه هوشمند", fontWeight = FontWeight.Bold)
                                    Text(
                                        "خلاصه و کلیدواژه جلسه",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Card(
                                Modifier
                                    .weight(1f)
                                    .clickable(onClick = onNavigateToAdd),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Column(Modifier.padding(18.dp)) {
                                    Icon(
                                        Icons.Default.AddCircle,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text("درس جدید", fontWeight = FontWeight.Bold)
                                    Text(
                                        "ثبت جلسه و بارگذاری صوت",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text(
                                    "مطالعه را ادامه دهید",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "جلسه‌ها، متن درس و خلاصه‌ها در یکجا",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = viewModel::openClasses,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.School, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("مشاهده کلاس‌ها")
                                }
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setSearchQuery,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("جستجو در کلاس، جلسه و متن درس") },
                        leadingIcon = { Icon(Icons.Default.Search, "جستجو") },
                        shape = RoundedCornerShape(14.dp)
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "کلاس‌های من",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            classes.size.toString() + " کلاس",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (classes.isEmpty()) {
                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.School,
                                    null,
                                    Modifier.size(42.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    if (isTeacher) "هنوز کلاسی ایجاد نشده است."
                                    else "هنوز کلاسی برای شما ثبت نشده است.",
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (isTeacher)
                                        "از بخش کلاس‌ها، کلاس جدید بسازید."
                                    else
                                        "بعد از اضافه شدن کلاس، درس‌ها و جلسات اینجا نمایش داده می‌شوند.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(classes, key = { it.id }) { classItem ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.openClass(classItem.id) },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    Modifier.size(46.dp),
                                    RoundedCornerShape(13.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.School,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(classItem.name, fontWeight = FontWeight.Bold)
                                    if (classItem.teacherName.isNotBlank()) {
                                        Text(
                                            "استاد: " + classItem.teacherName,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (classItem.term.isNotBlank()) {
                                        Text(
                                            classItem.term,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isTeacher) "کلاس‌ها و درس‌ها" else "کلاس‌های من",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "برای ورود به درس، کلاس را انتخاب کنید",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
}
