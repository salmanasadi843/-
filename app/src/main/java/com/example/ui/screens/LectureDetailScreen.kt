package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.LectureEntity
import java.io.File
import java.util.Locale
import com.example.ui.theme.TagPillBg
import com.example.ui.theme.TagPillText

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LectureDetailScreen(
    lectureId: Long,
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEdit: (Long) -> Unit,
    isTeacher: Boolean = true
) {
    val lecture by viewModel.selectedLecture.collectAsState()
    val context = LocalContext.current

    val isPlaying by viewModel.audioPlayer.isPlaying.collectAsState()
    val currentPos by viewModel.audioPlayer.currentPosition.collectAsState()
    val duration by viewModel.audioPlayer.duration.collectAsState()
    val playbackSpeed by viewModel.audioPlayer.playbackSpeed.collectAsState()
    val activePath by viewModel.audioPlayer.activeFilePath.collectAsState()

    val isAiLoading by viewModel.isAiLoading.collectAsState()
    val aiOperationTitle by viewModel.aiOperationTitle.collectAsState()
    val aiError by viewModel.aiError.collectAsState()

    val chatMessages by viewModel.chatMessages.collectAsState()

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var chatInputText by remember { mutableStateOf("") }

    if (lecture == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val currentLecture = lecture!!

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("حذف کلاس درس") },
            text = { Text("آیا مطمئن هستید که می‌خواهید جلسه «${currentLecture.title}» و فایل‌های مرتبط را حذف کنید؟") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteLecture(lectureId) {
                            onNavigateBack()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("حذف")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("انصراف")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentLecture.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        if (currentLecture.courseName.isNotBlank() || currentLecture.professorName.isNotBlank()) {
                            Text(
                                text = "${currentLecture.courseName} • ${currentLecture.professorName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("detail_back_button")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleFavorite(currentLecture) }) {
                        Icon(
                            imageVector = if (currentLecture.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = "نشان",
                            tint = if (currentLecture.isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isTeacher) { IconButton(onClick = { onNavigateToEdit(lectureId) }, modifier = Modifier.testTag("detail_edit_button")) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "ویرایش")
                        } }
                    IconButton(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                """
عنوان: ${currentLecture.title}
درس: ${currentLecture.courseName}
استاد: ${currentLecture.professorName}

خلاصه هوشمند:
${currentLecture.aiSummary ?: "ثبت نشده"}

متن جزوه:
${currentLecture.transcript}
                                """.trimIndent()
                            )
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "اشتراک‌گذاری جزوه کلاس"))
                    }) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "اشتراک‌گذاری")
                    }
                    if (isTeacher) {
                    IconButton(onClick = { showDeleteConfirmDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "حذف",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // فایل محلی اولویت دارد؛ در صورت نبودن آن، لینک صوت استفاده می‌شود.
            val playableAudio = currentLecture.audioFilePath?.takeIf { File(it).exists() } ?: currentLecture.audioUrl
            if (!playableAudio.isNullOrBlank()) {
                AudioPlayerCard(
                    filePath = playableAudio,
                    audioDurationMs = if (duration > 0 && activePath == playableAudio) duration.toLong() else currentLecture.audioDurationMs,
                    currentPositionMs = if (activePath == currentLecture.audioFilePath) currentPos else 0,
                    isPlaying = isPlaying && activePath == currentLecture.audioFilePath,
                    playbackSpeed = playbackSpeed,
                    onPlayToggle = {
                        viewModel.audioPlayer.togglePlayPause(playableAudio)
                    },
                    onSeekTo = { viewModel.audioPlayer.seekTo(it) },
                    onSkipBackward = { viewModel.audioPlayer.skipBackward(15000) },
                    onSkipForward = { viewModel.audioPlayer.skipForward(15000) },
                    onSpeedChange = { viewModel.audioPlayer.setSpeed(it) }
                )
            }

            // AI Loading Banner
            AnimatedVisibility(visible = isAiLoading) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = aiOperationTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // AI Error Banner
            if (aiError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = aiError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearAiError() }) {
                            Text("بستن", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            // محتوای مطالعه به صورت یک صفحه پیوسته نمایش داده می‌شود؛ بدون تب.
            TranscriptTab(
                lecture = currentLecture,
                onEditClick = { onNavigateToEdit(lectureId) },
                onCopyClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("جزوه کلاس", currentLecture.transcript)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "متن در حافظه کپی شد", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
fun AudioPlayerCard(
    filePath: String,
    audioDurationMs: Long,
    currentPositionMs: Int,
    isPlaying: Boolean,
    playbackSpeed: Float,
    onPlayToggle: () -> Unit,
    onSeekTo: (Int) -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onSpeedChange: (Float) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 5.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Scrubbing Slider
            val maxRange = if (audioDurationMs > 0) audioDurationMs.toFloat() else 100f
            val sliderValue = currentPositionMs.toFloat().coerceIn(0f, maxRange)

            Slider(
                value = sliderValue,
                onValueChange = { onSeekTo(it.toInt()) },
                valueRange = 0f..maxRange,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.secondary,
                    activeTrackColor = MaterialTheme.colorScheme.secondary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.28f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
            )

            // Current Time & Duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(currentPositionMs.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDuration(audioDurationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Player Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Speed Selector Chip
                Surface(
                    onClick = {
                        val nextSpeed = when (playbackSpeed) {
                            0.75f -> 1.0f
                            1.0f -> 1.25f
                            1.25f -> 1.5f
                            1.5f -> 2.0f
                            else -> 0.75f
                        }
                        onSpeedChange(nextSpeed)
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${playbackSpeed}x",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                // Middle Buttons: Rewind, Play/Pause, FastForward
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onSkipBackward, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.FastRewind,
                            contentDescription = "۱۵ ثانیه به عقب",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Surface(
                        onClick = onPlayToggle,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "توقف" else "پخش",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    IconButton(onClick = onSkipForward, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "۱۵ ثانیه به جلو",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Placeholder space for balanced layout
                Spacer(modifier = Modifier.width(48.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptTab(
    lecture: LectureEntity,
    onEditClick: () -> Unit,
    onCopyClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    val hasText = lecture.transcript.isNotBlank()
    val tags = lecture.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(
            text = PersianDateUtils.format(lecture.dateMillis),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = lecture.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            lineHeight = 32.sp
        )

        if (lecture.courseName.isNotBlank() || lecture.professorName.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = listOf(lecture.courseName, lecture.professorName)
                    .filter { it.isNotBlank() }
                    .joinToString("  •  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(18.dp))

        // متن اصلی همیشه بخش محوری مطالعه است و قبل از خلاصه و کلیدواژه‌ها می‌آید.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "متن جلسه",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCopyClick) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("کپی")
            }
            if (!hasText) {
                TextButton(onClick = onEditClick) { Text("افزودن متن") }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (!hasText) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.MenuBook,
                        null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "متن این جلسه هنوز آماده نشده است.",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "استاد می‌تواند صوت جلسه را به متن تبدیل و متن را ویرایش کند.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = lecture.transcript,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 32.sp),
                    textAlign = TextAlign.Start
                )
            }
        }

        // خلاصه دقیقاً بعد از متن اصلی قرار می‌گیرد.
        Spacer(Modifier.height(28.dp))
        Text(
            text = "خلاصه بحث",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Text(
                text = lecture.aiSummary?.takeIf { it.isNotBlank() }
                    ?: "هنوز خلاصه‌ای برای این جلسه تولید نشده است.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                color = if (lecture.aiSummary.isNullOrBlank())
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        // کلیدواژه‌ها بعد از خلاصه قرار می‌گیرند.
        Spacer(Modifier.height(24.dp))
        Text(
            text = "کلیدواژه‌ها و مباحث اصلی",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))

        if (tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                tags.forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = TagPillBg
                    ) {
                        Text(
                            tag,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        } else if (!lecture.aiKeyPoints.isNullOrBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = lecture.aiKeyPoints,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp)
                )
            }
        } else {
            Text(
                "هنوز کلیدواژه‌ای برای این جلسه ثبت نشده است.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!lecture.aiKeyPoints.isNullOrBlank() && tags.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "نکات کلیدی",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = lecture.aiKeyPoints,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp)
                )
            }
        }

        Spacer(Modifier.height(36.dp))
    }
}
}

@Composable
fun AiSummaryTab(
    lecture: LectureEntity,
    isAiLoading: Boolean,
    onGenerateSummary: () -> Unit,
    onExtractKeyPoints: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onGenerateSummary,
                enabled = !isAiLoading && lecture.transcript.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("خلاصه هوشمند", fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = onExtractKeyPoints,
                enabled = !isAiLoading && lecture.transcript.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("نکات و فرمول‌ها", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Executive Summary Card
        Text(
            text = "خلاصه مباحث جلسه",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (lecture.aiSummary.isNullOrBlank()) {
                    Text(
                        text = "هنوز خلاصه‌ای تولید نشده است. بر روی دکمه «خلاصه هوشمند» بالا کلیک کنید تا هوش مصنوعی بر اساس متن کلاس آن را تدوین نماید.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                } else {
                    Text(
                        text = lecture.aiSummary,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 26.sp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Key Points and Formulas Card
        Text(
            text = "فرمول‌ها و نکات کلیدی آزمون",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (lecture.aiKeyPoints.isNullOrBlank()) {
                    Text(
                        text = "نکات کلیدی و فرمول‌ها هنوز استخراج نشده‌اند. با کلیک بر روی دکمه «نکات و فرمول‌ها» گزیده‌ای از مباحث تستی و فرمول‌های مهم ایجاد خواهد شد.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                } else {
                    Text(
                        text = lecture.aiKeyPoints,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 26.sp)
                    )
                }
            }
        }
    }
}

@Composable
fun QuizTab(
    quiz: List<QuizQuestion>,
    selectedAnswers: Map<Int, Int>,
    isAiLoading: Boolean,
    onOptionSelected: (Int, Int) -> Unit,
    onGenerateQuiz: () -> Unit,
    onResetAnswers: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "آزمونک یادگیری (${quiz.size} سوال)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "میزان یادگیری خود از مباحث این جلسه را بسنجید",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onGenerateQuiz,
                enabled = !isAiLoading,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("تولید آزمونک جدید")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (quiz.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "هنوز کوئیزی برای این جلسه طراحی نشده است.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onGenerateQuiz, enabled = !isAiLoading) {
                        Text("طراحی آزمونک ۴ گزینه‌ای با هوش مصنوعی")
                    }
                }
            }
        } else {
            quiz.forEachIndexed { qIndex, questionItem ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "سوال ${qIndex + 1}: ${questionItem.question}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 22.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        val userSelected = selectedAnswers[qIndex]
                        val isAnswered = userSelected != null

                        questionItem.options.forEachIndexed { optIndex, optionText ->
                            val isCorrectOption = optIndex == questionItem.correctIndex
                            val isUserPicked = userSelected == optIndex

                            val bgColor = when {
                                !isAnswered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                isCorrectOption -> Color(0xFFDCFCE7)
                                isUserPicked && !isCorrectOption -> Color(0xFFFEE2E2)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            }

                            val borderColor = when {
                                isAnswered && isCorrectOption -> Color(0xFF16A34A)
                                isAnswered && isUserPicked && !isCorrectOption -> Color(0xFFDC2626)
                                else -> Color.Transparent
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                                    .clickable(enabled = !isAnswered) {
                                        onOptionSelected(qIndex, optIndex)
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = bgColor
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isUserPicked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${optIndex + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isUserPicked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = optionText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // Explanation after answering
                        if (isAnswered) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = if (userSelected == questionItem.correctIndex) "✅ پاسخ شما صحیح است!" else "❌ پاسخ نادرست. گزینه صحیح: گزینه ${questionItem.correctIndex + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (userSelected == questionItem.correctIndex) Color(0xFF15803D) else Color(0xFFB91C1C)
                                    )
                                    if (questionItem.explanation.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = questionItem.explanation,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onResetAnswers,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("شروع مجدد پاسخ‌دهی به آزمونک")
            }
        }
    }
}

@Composable
fun ChatWithLectureTab(
    messages: List<ChatMessage>,
    isAiLoading: Boolean,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onSendMessage: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages.size) { index ->
                val msg = messages[index]
                val isUser = msg.sender == MessageSender.USER

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        ),
                        color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        shadowElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (isUser) "شما" else "استادیار مجازی",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Chat Input Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    placeholder = { Text("سوال خود را درباره این جلسه بپرسید...", fontSize = 13.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field"),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 3
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onSendMessage,
                    enabled = !isAiLoading && inputText.isNotBlank(),
                    modifier = Modifier
                        .background(
                            if (!isAiLoading && inputText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                        .size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "ارسال سوال",
                        tint = if (!isAiLoading && inputText.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
