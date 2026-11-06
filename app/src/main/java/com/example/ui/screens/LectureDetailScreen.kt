package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.automirrored.filled.MenuBook
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.LectureEntity
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import com.example.ui.theme.TagPillBg
import com.example.ui.theme.TagPillText

@Composable
private fun FormattedHandoutContent(
    content: String,
    bodyFontSize: Float,
    headingScale: Float,
    lineSpacingScale: Float,
    modifier: Modifier = Modifier
) {
    val numberPattern = Regex("""^([0-9۰-۹]+(?:[ـ.-][0-9۰-۹]+)*[.)]?)\s+(.+)$""")
    val letterPattern = Regex("""^([الفبپتثجچحخدذرزژسشصضطظعغفقکگلمنوهی])[).ـ]\s*(.+)$""")

    Column(modifier = modifier) {
        content.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                Spacer(Modifier.height(7.dp))
            } else {
                val markdownLevel = line.takeWhile { it == '#' }.length
                val cleanLine = line.dropWhile { it == '#' }.trim().removeSurrounding("**")
                val numbered = numberPattern.matchEntire(cleanLine)
                val lettered = letterPattern.matchEntire(cleanLine)

                when {
                    markdownLevel > 0 -> {
                        Text(
                            text = cleanLine,
                            color = Color.Black,
                            fontSize = (when (markdownLevel) {
                                1 -> 25f
                                2 -> 22f
                                3 -> 19f
                                else -> 17f
                            } * headingScale).sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 32.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 5.dp)
                        )
                    }
                    numbered != null -> {
                        val prefix = numbered.groupValues[1]
                        val level = prefix.trimEnd('.', ')').split(Regex("[ـ.-]")).size
                        Text(
                            text = cleanLine,
                            color = Color.Black,
                            fontSize = (when (level) {
                                1 -> 24f
                                2 -> 21f
                                3 -> 18f
                                else -> 16f
                            } * headingScale).sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 30.sp,
                            modifier = Modifier.padding(
                                top = if (level == 1) 12.dp else 8.dp,
                                bottom = 4.dp
                            )
                        )
                    }
                    lettered != null -> {
                        Text(
                            text = cleanLine,
                            color = Color.Black,
                            fontSize = (16f * headingScale).sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 27.sp,
                            modifier = Modifier.padding(top = 5.dp, bottom = 2.dp)
                        )
                    }
                    else -> {
                        Text(
                            text = cleanLine,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = bodyFontSize.sp,
                            lineHeight = (bodyFontSize * 1.7f * lineSpacingScale).sp
                        )
                    }
                }
            }
        }
    }
}

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
    isTeacher: Boolean = true,
    initialSearchQuery: String = "",
    initialSearchMatchMode: String = "all"
) {
    val lecture by viewModel.selectedLecture.collectAsState()
    val context = LocalContext.current
    val canManageLecture = isTeacher || AuthPreferences.isPrimaryTeacher(context)

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
    var showExportDialog by remember { mutableStateOf(false) }
    var exportOriginalTranscript by remember { mutableStateOf(false) }
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

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("خروجی و ارسال جزوه") },
            text = { Column { Text("قالب فایل و نسخه متن را انتخاب کنید."); Row { TextButton(onClick = { exportOriginalTranscript = true }) { Text(if (exportOriginalTranscript) "متن اصلی *" else "متن اصلی") }; TextButton(onClick = { exportOriginalTranscript = false }) { Text(if (!exportOriginalTranscript) "متن اصلاح‌شده *" else "متن اصلاح‌شده") } } } },
            confirmButton = {
                val lectureForExport = if (exportOriginalTranscript) currentLecture.copy(transcript = currentLecture.originalTranscript.ifBlank { currentLecture.transcript }) else currentLecture
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        showExportDialog = false
                        runCatching { LectureExport.share(context, listOf(lectureForExport), LectureExportFormat.WORD, currentLecture.title) }
                            .onFailure { android.widget.Toast.makeText(context, "ساخت فایل Word ناموفق بود: ${it.localizedMessage}", android.widget.Toast.LENGTH_LONG).show() }
                    }) { Text("Word") }
                    TextButton(onClick = {
                        showExportDialog = false
                        runCatching { LectureExport.share(context, listOf(lectureForExport), LectureExportFormat.PDF, currentLecture.title) }
                            .onFailure { android.widget.Toast.makeText(context, "ساخت PDF ناموفق بود: ${it.localizedMessage}", android.widget.Toast.LENGTH_LONG).show() }
                    }) { Text("PDF") }
                    TextButton(onClick = {
                        showExportDialog = false
                        runCatching { LectureExport.share(context, listOf(lectureForExport), LectureExportFormat.TEXT, currentLecture.title) }
                            .onFailure { android.widget.Toast.makeText(context, "اشتراک‌گذاری ناموفق بود: ${it.localizedMessage}", android.widget.Toast.LENGTH_LONG).show() }
                    }) { Text("متن") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("انصراف") }
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
                    if (canManageLecture) { IconButton(onClick = { onNavigateToEdit(lectureId) }, modifier = Modifier.testTag("detail_edit_button")) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "ویرایش")
                        } }
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(imageVector = Icons.Default.Description, contentDescription = "خروجی Word و PDF")
                    }
                    if (canManageLecture) {
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
            // پلیر و پیوند صوت داخل محتوای قابل پیمایش قرار می‌گیرند؛
            // دیگر یک نوار ثابت و بلند بالای متن نیستند.
            val playableAudio = currentLecture.audioFilePath?.takeIf { File(it).exists() } ?: currentLecture.audioUrl
            TranscriptTab(
                lecture = currentLecture,
                searchQuery = initialSearchQuery,
                searchMatchMode = initialSearchMatchMode,
                onEditClick = { onNavigateToEdit(lectureId) },
                onCopyClick = { textToCopy ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("جزوه کلاس", textToCopy)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "متن انتخاب‌شده کپی شد", Toast.LENGTH_SHORT).show()
                },
                audioContent = if (!playableAudio.isNullOrBlank()) {
                    {
                        AudioPlayerCard(
                            filePath = playableAudio,
                            audioDurationMs = if (duration > 0 && activePath == playableAudio) duration.toLong() else currentLecture.audioDurationMs,
                            currentPositionMs = if (activePath == playableAudio) currentPos else 0,
                            isPlaying = isPlaying && activePath == playableAudio,
                            playbackSpeed = playbackSpeed,
                            onPlayToggle = { viewModel.audioPlayer.togglePlayPause(playableAudio) },
                            onSeekTo = { viewModel.audioPlayer.seekTo(it) },
                            onSkipBackward = { viewModel.audioPlayer.skipBackward(15000) },
                            onSkipForward = { viewModel.audioPlayer.skipForward(15000) },
                            onSpeedChange = { viewModel.audioPlayer.setSpeed(it) }
                        )
                    }
                } else null,
                audioLinkContent = if (!currentLecture.audioUrl.isNullOrBlank()) {
                    {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentLecture.audioUrl)))
                                }.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "پیوند صوتی", tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("پیوند صوتی", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Text(currentLecture.audioUrl.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("باز کردن", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else null
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
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val maxRange = audioDurationMs.coerceAtLeast(1L).toFloat()
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
                modifier = Modifier.fillMaxWidth().height(32.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatDuration(currentPositionMs.toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = formatDuration(audioDurationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Keep all transport controls in one balanced row so none are pushed off-screen.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onSkipBackward,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FastRewind,
                        contentDescription = "۱۵ ثانیه به عقب",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(27.dp)
                    )
                }

                Surface(
                    onClick = onPlayToggle,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(60.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "مکث" else "پخش",
                            tint = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onSkipForward,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = "۱۵ ثانیه به جلو",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(27.dp)
                    )
                }

                Surface(
                    onClick = {
                        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                        val nextIndex = (speeds.indexOf(playbackSpeed).takeIf { it >= 0 } ?: 1) + 1
                        onSpeedChange(speeds[nextIndex % speeds.size])
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "سرعت پخش",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${playbackSpeed}x",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptTab(
    lecture: LectureEntity,
    searchQuery: String = "",
    searchMatchMode: String = "all",
    onEditClick: () -> Unit,
    onCopyClick: (String) -> Unit,
    audioContent: (@Composable () -> Unit)? = null,
    audioLinkContent: (@Composable () -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    var transcriptTopPx by remember(lecture.id) { mutableStateOf(0f) }
    var transcriptLayout by remember(lecture.id) { mutableStateOf<TextLayoutResult?>(null) }
    val originalText = lecture.originalTranscript.ifBlank { lecture.transcript }
    val correctedText = lecture.transcript
    var selectedTextTab by remember(lecture.id, searchQuery, searchMatchMode) {
        mutableStateOf(if (searchQuery.isNotBlank() && !containsSearchTerm(originalText, searchQuery, searchMatchMode) && containsSearchTerm(correctedText, searchQuery, searchMatchMode)) 1 else 0)
    }
    val displayedTranscript = if (selectedTextTab == 0) originalText else correctedText
    val highlightedTranscript = highlightSearchTerms(
        displayedTranscript,
        searchQuery,
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
        searchMatchMode
    )
    val hasText = originalText.isNotBlank() || correctedText.isNotBlank()

    LaunchedEffect(lecture.id, searchQuery, searchMatchMode, selectedTextTab, transcriptLayout, transcriptTopPx) {
        if (searchQuery.isNotBlank() && transcriptLayout != null) {
            val offset = findFirstSearchOffset(displayedTranscript, searchQuery, searchMatchMode)
            if (offset != null) {
                val line = transcriptLayout!!.getLineForOffset(offset.coerceIn(0, displayedTranscript.length))
                val target = (transcriptTopPx + transcriptLayout!!.getLineTop(line)).roundToInt()
                scrollState.animateScrollTo(target.coerceAtLeast(0))
            }
        }
    }
    val tags = lecture.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
    val context = LocalContext.current
    var showHandoutTypographySettings by remember(lecture.id) { mutableStateOf(false) }
    val handoutPrefs = remember(context) {
        context.getSharedPreferences("darsyar_handout_display", Context.MODE_PRIVATE)
    }
    var handoutBodyFontSize by remember(lecture.id) {
        mutableStateOf(handoutPrefs.getFloat("body_font_size", 17f).coerceIn(14f, 24f))
    }
    var handoutHeadingScale by remember(lecture.id) {
        mutableStateOf(handoutPrefs.getFloat("heading_scale", 1f).coerceIn(0.8f, 1.4f))
    }
    var handoutLineSpacing by remember(lecture.id) {
        mutableStateOf(handoutPrefs.getFloat("line_spacing", 1f).coerceIn(0.85f, 1.3f))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        audioContent?.invoke()
        audioLinkContent?.invoke()
        if (audioContent != null || audioLinkContent != null) Spacer(Modifier.height(8.dp))
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
            Text(
                text = "متن جلسه",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onCopyClick(displayedTranscript) }) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("کپی")
                }
                if (!hasText) {
                    TextButton(onClick = onEditClick) { Text("افزودن متن") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        TabRow(selectedTabIndex = selectedTextTab) {
            Tab(
                selected = selectedTextTab == 0,
                onClick = { selectedTextTab = 0 },
                text = { Text("متن اصلی") }
            )
            Tab(
                selected = selectedTextTab == 1,
                onClick = { selectedTextTab = 1 },
                text = { Text("متن اصلاح‌شده") }
            )
        }

        Spacer(Modifier.height(12.dp))
        if (searchQuery.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = "نتیجه جست‌وجو: $searchQuery",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.height(8.dp))
        }

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
                        Icons.AutoMirrored.Filled.MenuBook,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates -> transcriptTopPx = coordinates.positionInParent().y },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = highlightedTranscript,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                    onTextLayout = { transcriptLayout = it },
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 32.sp),
                    textAlign = TextAlign.Start
                )
            }
        }

        // خلاصه دقیقاً بعد از متن اصلی قرار می‌گیرد.
        Spacer(Modifier.height(28.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "خلاصه بحث",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = {
                showHandoutTypographySettings = !showHandoutTypographySettings
            }) {
                Text(if (showHandoutTypographySettings) "بستن تنظیمات قلم" else "تنظیم اندازه قلم")
            }
        }
        AnimatedVisibility(visible = showHandoutTypographySettings) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("اندازه متن: ${handoutBodyFontSize.toInt()}", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = handoutBodyFontSize,
                        onValueChange = {
                            handoutBodyFontSize = it
                            handoutPrefs.edit().putFloat("body_font_size", it).apply()
                        },
                        valueRange = 14f..24f
                    )
                    Text("اندازه عنوان‌ها: ${(handoutHeadingScale * 100).toInt()}٪", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = handoutHeadingScale,
                        onValueChange = {
                            handoutHeadingScale = it
                            handoutPrefs.edit().putFloat("heading_scale", it).apply()
                        },
                        valueRange = 0.8f..1.4f
                    )
                    Text("فاصله خطوط: ${(handoutLineSpacing * 100).toInt()}٪", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = handoutLineSpacing,
                        onValueChange = {
                            handoutLineSpacing = it
                            handoutPrefs.edit().putFloat("line_spacing", it).apply()
                        },
                        valueRange = 0.85f..1.3f
                    )
                    TextButton(onClick = {
                        handoutBodyFontSize = 17f
                        handoutHeadingScale = 1f
                        handoutLineSpacing = 1f
                        handoutPrefs.edit()
                            .putFloat("body_font_size", 17f)
                            .putFloat("heading_scale", 1f)
                            .putFloat("line_spacing", 1f)
                            .apply()
                    }) {
                        Text("بازگشت به اندازه پیش‌فرض")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            FormattedHandoutContent(
                content = lecture.aiSummary?.takeIf { it.isNotBlank() }
                    ?: "هنوز خلاصه‌ای برای این جلسه تولید نشده است.",
                bodyFontSize = handoutBodyFontSize,
                headingScale = handoutHeadingScale,
                lineSpacingScale = handoutLineSpacing,
                modifier = Modifier.padding(16.dp)
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


private fun normalizeSearchForHighlight(value: String): String =
    value.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک').replace('ۀ', 'ه').replace('ة', 'ه')
        .replace('ؤ', 'و').replace('إ', 'ا').replace('أ', 'ا').replace('آ', 'ا')
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("[\\u200C\\u200F\\u202A-\\u202E]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun normalizeSearchCharacterForHighlight(character: Char): String = when {
    character.isWhitespace() || character.code == 0x200C -> " "
    character.code in 0x064B..0x065F || character.code == 0x0670 ||
        character.code == 0x200F || character.code in 0x202A..0x202E -> ""
    character == 'ي' -> "ی"
    character == 'ك' -> "ک"
    character == 'ۀ' || character == 'ة' -> "ه"
    character == 'ؤ' -> "و"
    character == 'إ' || character == 'أ' || character == 'آ' -> "ا"
    else -> character.lowercaseChar().toString()
}

private fun appendNormalizedSearchCharacter(
    normalized: StringBuilder,
    originalIndexes: MutableList<Int>,
    value: String,
    originalIndex: Int
) {
    value.forEach { character ->
        if (character.isWhitespace()) {
            if (normalized.isNotEmpty() && normalized.last() != ' ') {
                normalized.append(' ')
                originalIndexes += originalIndex
            }
        } else {
            normalized.append(character)
            originalIndexes += originalIndex
        }
    }
}

private fun findFirstSearchOffset(content: String, query: String, matchMode: String = "all"): Int? {
    val normalized = StringBuilder()
    val originalIndexes = mutableListOf<Int>()
    content.forEachIndexed { index, char ->
        appendNormalizedSearchCharacter(
            normalized, originalIndexes, normalizeSearchCharacterForHighlight(char), index
        )
    }
    val normalizedQuery = normalizeSearchForHighlight(query).trim()
    if (normalizedQuery.length < 2) return null
    val terms = if (matchMode == "phrase") listOf(normalizedQuery) else normalizedQuery.split(Regex("\\s+"))
        .filter { it.length >= 2 }.distinct()
    val firstNormalizedIndex = terms.mapNotNull { term ->
        normalized.indexOf(term).takeIf { it >= 0 }
    }.minOrNull() ?: return null
    return originalIndexes.getOrNull(firstNormalizedIndex)
}

private fun containsSearchTerm(content: String, query: String, matchMode: String = "all"): Boolean {
    val normalized = normalizeSearchForHighlight(content)
    val normalizedQuery = normalizeSearchForHighlight(query).trim()
    return if (matchMode == "phrase") {
        normalizedQuery.isNotBlank() && normalized.contains(normalizedQuery)
    } else {
        normalizedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }.any(normalized::contains)
    }
}

private fun highlightSearchTerms(
    content: String,
    query: String,
    background: androidx.compose.ui.graphics.Color,
    foreground: androidx.compose.ui.graphics.Color,
    matchMode: String = "all"
) = buildAnnotatedString {
    if (query.isBlank() || content.isBlank()) {
        append(content)
        return@buildAnnotatedString
    }

    val normalized = StringBuilder()
    val originalIndexes = mutableListOf<Int>()
    content.forEachIndexed { index, char ->
        appendNormalizedSearchCharacter(
            normalized, originalIndexes, normalizeSearchCharacterForHighlight(char), index
        )
    }

    val normalizedQuery = normalizeSearchForHighlight(query).trim()
    val terms = if (matchMode == "phrase") listOf(normalizedQuery).filter { it.length >= 2 }
        else normalizedQuery.split(Regex("\\s+")).filter { it.length >= 2 }.distinct()
    val ranges = mutableListOf<IntRange>()
    terms.forEach { term ->
        var from = 0
        while (from <= normalized.length - term.length) {
            val found = normalized.indexOf(term, from)
            if (found < 0) break
            val start = originalIndexes.getOrNull(found)
            val end = originalIndexes.getOrNull(found + term.length - 1)?.plus(1)
            if (start != null && end != null && end > start) ranges += start until end
            from = found + term.length
        }
    }

    val merged = ranges.sortedBy { it.first }.fold(mutableListOf<IntRange>()) { acc, range ->
        val last = acc.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            acc[acc.lastIndex] = last.first..maxOf(last.last, range.last)
        } else acc += range
        acc
    }

    var cursor = 0
    merged.forEach { range ->
        if (range.first > cursor) append(content.substring(cursor, range.first))
        withStyle(SpanStyle(background = background, color = foreground)) {
            append(content.substring(range.first, range.last + 1))
        }
        cursor = range.last + 1
    }
    if (cursor < content.length) append(content.substring(cursor))
}

