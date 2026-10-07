package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.LectureEntity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    userRole: UserRole,
    onNavigateToAdd: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToAiSummary: (Long) -> Unit
) {
    val lectures by viewModel.lectures.collectAsState()
    val allCourses by viewModel.repository.allCourses.collectAsState(initial = emptyList())
    val searchQuery by viewModel.searchQuery.collectAsState()

    val isPlaying by viewModel.audioPlayer.isPlaying.collectAsState()
    val activeAudioPath by viewModel.audioPlayer.activeFilePath.collectAsState()

    var selectedCourse by remember {
        mutableStateOf<String?>(null)
    }

    val visibleLectures = if (selectedCourse == null) {
        lectures
    } else {
        lectures.filter {
            it.courseName == selectedCourse
        }
    }

    val courseNames = remember(allCourses, lectures) {
        (
            allCourses.map { it.name } +
                lectures.map { it.courseName }
            )
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
    }

    val courseCounts = remember(lectures) {
        lectures
            .groupingBy { it.courseName }
            .eachCount()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (selectedCourse != null) {
                        IconButton(
                            onClick = {
                                selectedCourse = null
                            }
                        ) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "بازگشت"
                            )
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            selectedCourse ?: "استادیار",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )

                        Text(
                            when {
                                selectedCourse != null ->
                                    "جلسه‌های این درس"

                                userRole == UserRole.TEACHER ->
                                    "کلاس‌ها و جلسات"

                                else ->
                                    "انتخاب درس برای مطالعه"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            if (userRole == UserRole.TEACHER) {
                                "استاد"
                            } else {
                                "شاگرد"
                            },
                            modifier = Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 6.dp
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    IconButton(
                        onClick = {
                            viewModel.navigateTo(Screen.Settings)
                        }
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "تنظیمات"
                        )
                    }
                }
            )
        },

        floatingActionButton = {
            if (userRole == UserRole.TEACHER) {
                FloatingActionButton(
                    onClick = onNavigateToAdd
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "افزودن جلسه"
                    )
                }
            }
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {

            Spacer(
                modifier = Modifier.height(10.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    viewModel.setSearchQuery(it)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        "جستجو در کلاس، جلسه و متن جزوه"
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(
                            onClick = {
                                viewModel.setSearchQuery("")
                            }
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "پاک کردن"
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor =
                        MaterialTheme.colorScheme.outlineVariant,

                    focusedBorderColor =
                        MaterialTheme.colorScheme.primary
                )
            )

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            if (
                searchQuery.isBlank() &&
                selectedCourse == null
            ) {

                AiActionsCard(
                    onSummary = {
                        if (lectures.isNotEmpty()) {
                            onNavigateToAiSummary(
                                lectures.first().id
                            )
                        }
                    },
                    onNewLesson = onNavigateToAdd
                )

                Spacer(
                    modifier = Modifier.height(18.dp)
                )

                Text(
                    "کلاس‌ها و درس‌ها",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(
                    modifier = Modifier.height(5.dp)
                )

                Text(
                    "یک درس را انتخاب کنید تا جلسه‌های آن نمایش داده شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(
                    modifier = Modifier.height(12.dp)
                )

                if (courseNames.isEmpty()) {

                    EmptyState(
                        title = "هنوز درسی ثبت نشده است",
                        text =
                            if (userRole == UserRole.TEACHER) {
                                "از دکمه + یک جلسه جدید اضافه کنید."
                            } else {
                                "فعلاً جلسه‌ای برای مطالعه وجود ندارد."
                            }
                    )

                } else {

                    LazyColumn(
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp),

                        modifier = Modifier.fillMaxSize()
                    ) {

                        items(
                            items = courseNames,
                            key = { it }
                        ) { course ->

                            CourseCard(
                                courseName = course,

                                sessionCount =
                                    courseCounts[course] ?: 0,

                                professorName =
                                    lectures
                                        .firstOrNull {
                                            it.courseName == course
                                        }
                                        ?.professorName
                                        .orEmpty(),

                                onClick = {
                                    selectedCourse = course
                                }
                            )
                        }

                        item {
                            Spacer(
                                modifier = Modifier.height(80.dp)
                            )
                        }
                    }
                }

            } else {

                Text(
                    if (searchQuery.isNotBlank()) {
                        "نتایج جستجو"
                    } else {
                        "جلسه‌های ${selectedCourse.orEmpty()}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                if (visibleLectures.isEmpty()) {

                    EmptyState(
                        title = "جلسه‌ای پیدا نشد",
                        text =
                            if (userRole == UserRole.TEACHER) {
                                "می‌توانید جلسه جدیدی اضافه کنید."
                            } else {
                                "برای این درس هنوز جلسه‌ای ثبت نشده است."
                            }
                    )

                } else {

                    LazyColumn(
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp),

                        modifier = Modifier.fillMaxSize()
                    ) {

                        items(
                            items = visibleLectures,
                            key = { it.id }
                        ) { lecture ->

                            val playableAudio =
                                lecture.audioFilePath
                                    ?.takeIf {
                                        File(it).exists()
                                    }
                                    ?: lecture.audioUrl

                            val isCurrentAudioPlaying =
                                isPlaying &&
                                    activeAudioPath == playableAudio

                            SessionCard(
                                lecture = lecture,
                                playableAudio = playableAudio,
                                isPlaying = isCurrentAudioPlaying,

                                onClick = {
                                    onNavigateToDetail(
                                        lecture.id
                                    )
                                },

                                onPlay = {
                                    playableAudio?.let { audio ->
                                        viewModel.audioPlayer
                                            .togglePlayPause(audio)
                                    }
                                }
                            )
                        }

                        item {
                            Spacer(
                                modifier = Modifier.height(80.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun AiActionsCard(
    onSummary: () -> Unit,
    onNewLesson: () -> Unit
) {

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            1.dp
        )
    ) {

        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            FilledTonalButton(
                onClick = onSummary,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(13.dp)
            ) {

                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(
                    modifier = Modifier.width(5.dp)
                )

                Text("خلاصه هوشمند")
            }

            OutlinedButton(
                onClick = onNewLesson,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(13.dp)
            ) {

                Icon(
                    Icons.Default.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(
                    modifier = Modifier.width(5.dp)
                )

                Text("درس جدید")
            }
        }
    }
}


@Composable
private fun CourseCard(
    courseName: String,
    sessionCount: Int,
    professorName: String,
    onClick: () -> Unit
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),

        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        ),

        elevation = CardDefaults.cardElevation(
            1.dp
        )
    ) {

        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Surface(
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(13.dp),
                color =
                    MaterialTheme.colorScheme.primaryContainer
            ) {

                Box(
                    contentAlignment =
                        Alignment.Center
                ) {

                    Icon(
                        Icons.Default.School,
                        contentDescription = null,
                        tint =
                            MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(
                modifier = Modifier.width(12.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    courseName,
                    fontWeight = FontWeight.Bold,
                    style =
                        MaterialTheme.typography.titleSmall
                )

                if (professorName.isNotBlank()) {

                    Spacer(
                        modifier = Modifier.height(3.dp)
                    )

                    Text(
                        "استاد: $professorName",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color =
                    MaterialTheme.colorScheme.surfaceVariant
            ) {

                Text(
                    "$sessionCount جلسه",

                    modifier = Modifier.padding(
                        horizontal = 9.dp,
                        vertical = 6.dp
                    ),

                    style =
                        MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}


@Composable
private fun SessionCard(
    lecture: LectureEntity,
    playableAudio: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),

        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        ),

        elevation = CardDefaults.cardElevation(
            1.dp
        )
    ) {

        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Surface(
                modifier = Modifier.size(42.dp),

                shape = RoundedCornerShape(12.dp),

                color =
                    if (!playableAudio.isNullOrBlank()) {
                        MaterialTheme.colorScheme
                            .primaryContainer
                    } else {
                        MaterialTheme.colorScheme
                            .surfaceVariant
                    }
            ) {

                Box(
                    contentAlignment =
                        Alignment.Center
                ) {

                    IconButton(
                        onClick = onPlay,
                        enabled =
                            !playableAudio.isNullOrBlank()
                    ) {

                        Icon(
                            if (
                                !playableAudio.isNullOrBlank()
                            ) {
                                Icons.Default.PlayArrow
                            } else {
                                Icons.Default.GraphicEq
                            },

                            contentDescription = "پخش صوت",

                            tint =
                                MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier.width(12.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    lecture.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow =
                        TextOverflow.Ellipsis
                )

                Spacer(
                    modifier = Modifier.height(4.dp)
                )

                Text(
                    "${PersianDateUtils.format(lecture.dateMillis)}" +
                        if (
                            lecture.professorName.isNotBlank()
                        ) {
                            "  •  ${lecture.professorName}"
                        } else {
                            ""
                        },

                    style =
                        MaterialTheme.typography.bodySmall,

                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant
                )

                if (lecture.tags.isNotBlank()) {

                    Spacer(
                        modifier = Modifier.height(4.dp)
                    )

                    Text(
                        lecture.tags,

                        style =
                            MaterialTheme.typography.labelSmall,

                        color =
                            MaterialTheme.colorScheme.primary,

                        maxLines = 1,

                        overflow =
                            TextOverflow.Ellipsis
                    )
                }
            }

            if (isPlaying) {

                Text(
                    "در حال پخش",

                    style =
                        MaterialTheme.typography.labelSmall,

                    color =
                        MaterialTheme.colorScheme.primary,

                    fontWeight =
                        FontWeight.Bold
                )
            }
        }
    }
}


@Composable
private fun EmptyState(
    title: String,
    text: String
) {

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment =
            Alignment.Center
    ) {

        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally,

            modifier = Modifier.padding(32.dp)
        ) {

            Surface(
                modifier = Modifier.size(64.dp),

                shape = RoundedCornerShape(18.dp),

                color =
                    MaterialTheme.colorScheme
                        .primaryContainer
            ) {

                Box(
                    contentAlignment =
                        Alignment.Center
                ) {

                    Icon(
                        Icons.Default.School,
                        contentDescription = null,

                        tint =
                            MaterialTheme.colorScheme.primary,

                        modifier =
                            Modifier.size(30.dp)
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            Text(
                title,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            Text(
                text,

                style =
                    MaterialTheme.typography.bodySmall,

                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant
            )
        }
    }
}
