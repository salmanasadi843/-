package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import kotlin.math.max
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditLectureScreen(
    lectureId: Long?,
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onSaved: (Long) -> Unit
) {
    val context = LocalContext.current

    val title by viewModel.formTitle.collectAsState()
    val professor by viewModel.formProfessor.collectAsState()
    val tags by viewModel.formTags.collectAsState()
    val transcript by viewModel.formTranscript.collectAsState()
    val audioPath by viewModel.formAudioPath.collectAsState()
    val audioUrl by viewModel.formAudioUrl.collectAsState()
    val audioDurationMs by viewModel.formAudioDurationMs.collectAsState()

    val isRecording by viewModel.audioRecorder.isRecording.collectAsState()
    val recordingDurationMs by viewModel.audioRecorder.recordingDurationMs.collectAsState()
    val amplitude by viewModel.audioRecorder.amplitude.collectAsState()

    val isPlaying by viewModel.audioPlayer.isPlaying.collectAsState()
    val activePath by viewModel.audioPlayer.activeFilePath.collectAsState()

    val isAiLoading by viewModel.isAiLoading.collectAsState()
    val aiOperationTitle by viewModel.aiOperationTitle.collectAsState()
    val aiError by viewModel.aiError.collectAsState()
    val transcriptionProgress by viewModel.transcriptionProgress.collectAsState()

    // File picker launcher for audio files
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importAudioUri(it)

            Toast.makeText(
                context,
                "فایل صوتی بارگذاری شد",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Permission launcher for microphone recording
    val micPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->

            if (isGranted) {

                viewModel.audioRecorder.startRecording()

            } else {

                Toast.makeText(
                    context,
                    "دسترسی میکروفون برای ضبط صدا لازم است",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    Scaffold(
        topBar = {

            TopAppBar(

                title = {

                    Text(
                        text =
                            if (lectureId == null)
                                "ثبت جلسه جدید کلاس"
                            else
                                "ویرایش جلسه کلاس",
                        style =
                            MaterialTheme.typography.titleMedium,
                        fontWeight =
                            FontWeight.Bold
                    )
                },

                navigationIcon = {

                    IconButton(
                        onClick = onNavigateBack
                    ) {

                        Icon(
                            imageVector =
                                Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription =
                                "انصراف"
                        )
                    }
                },

                actions = {

                    Button(

                        onClick = {

                            if (title.isBlank()) {

                                Toast.makeText(
                                    context,
                                    "لطفاً عنوان جلسه را وارد کنید",
                                    Toast.LENGTH_SHORT
                                ).show()

                            } else {

                                viewModel.saveCurrentForm(
                                    lectureId
                                ) { savedId ->

                                    Toast.makeText(
                                        context,
                                        "اطلاعات جلسه با موفقیت ذخیره شد",
                                        Toast.LENGTH_SHORT
                                    ).show()

                                    onSaved(savedId)
                                }
                            }
                        },

                        shape =
                            RoundedCornerShape(12.dp),

                        modifier =
                            Modifier
                                .padding(end = 8.dp)
                                .testTag(
                                    "save_lecture_button"
                                )

                    ) {

                        Icon(
                            imageVector =
                                Icons.Default.Check,
                            contentDescription =
                                null,
                            modifier =
                                Modifier.size(16.dp)
                        )

                        Spacer(
                            modifier =
                                Modifier.width(4.dp)
                        )

                        Text("ذخیره")
                    }
                },

                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor =
                            MaterialTheme
                                .colorScheme
                                .surface
                    )
            )
        }

    ) { innerPadding ->

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(
                        MaterialTheme
                            .colorScheme
                            .background
                    )
                    .verticalScroll(
                        rememberScrollState()
                    )
                    .padding(16.dp),

            verticalArrangement =
                Arrangement.spacedBy(16.dp)
        ) {

            // -------------------------------------------------
            // Section 1
            // -------------------------------------------------

            ElevatedCard(

                shape =
                    RoundedCornerShape(18.dp),

                colors =
                    CardDefaults.elevatedCardColors(
                        containerColor =
                            MaterialTheme
                                .colorScheme
                                .surface
                    )

            ) {

                Column(

                    modifier =
                        Modifier.padding(16.dp),

                    verticalArrangement =
                        Arrangement.spacedBy(12.dp)
                ) {

                    Text(
                        text =
                            "۱. عنوان‌دهی و مشخصات جلسه",

                        style =
                            MaterialTheme
                                .typography
                                .titleSmall,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            MaterialTheme
                                .colorScheme
                                .primary
                    )

                    OutlinedTextField(

                        value = title,

                        onValueChange = {
                            viewModel.formTitle.value = it
                        },

                        label = {
                            Text(
                                "عنوان جلسه (الزامی)*"
                            )
                        },

                        placeholder = {
                            Text(
                                "مثال: جلسه ۴ - شبکه‌های عصبی عمیق"
                            )
                        },

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(
                                    "lecture_title_input"
                                ),

                        shape =
                            RoundedCornerShape(12.dp),

                        singleLine = true
                    )

                    Row(

                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(

                            value = professor,

                            onValueChange = {
                                viewModel.formProfessor.value = it
                            },

                            label = {
                                Text("نام استاد")
                            },

                            placeholder = {
                                Text("مثال: دکتر مهدوی")
                            },

                            modifier =
                                Modifier
                                    .weight(1f)
                                    .testTag(
                                        "lecture_professor_input"
                                    ),

                            shape =
                                RoundedCornerShape(12.dp),

                            singleLine = true
                        )
                    }

                    OutlinedTextField(

                        value = tags,

                        onValueChange = {
                            viewModel.formTags.value = it
                        },

                        label = {
                            Text(
                                "کلیدواژه‌های مباحث (با ویرگول جدا کنید)"
                            )
                        },

                        placeholder = {
                            Text(
                                "مثلاً: توحید, واجب, برهان, فصل سوم"
                            )
                        },

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(
                                    "lecture_tags_input"
                                ),

                        shape =
                            RoundedCornerShape(12.dp),

                        singleLine = true
                    )
                }
            }

            // -------------------------------------------------
            // Section 2 - Audio
            // -------------------------------------------------

            ElevatedCard(

                shape =
                    RoundedCornerShape(18.dp),

                colors =
                    CardDefaults.elevatedCardColors(
                        containerColor =
                            MaterialTheme
                                .colorScheme
                                .surface
                    )
            ) {

                Column(

                    modifier =
                        Modifier.padding(16.dp),

                    verticalArrangement =
                        Arrangement.spacedBy(12.dp)
                ) {

                    Text(

                        text =
                            "۲. صوت جلسه",

                        style =
                            MaterialTheme
                                .typography
                                .titleSmall,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            MaterialTheme
                                .colorScheme
                                .primary
                    )

                    Row(

                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {

                        FilledTonalButton(

                            onClick = {

                                audioPickerLauncher.launch(
                                    "audio/*"
                                )
                            },

                            modifier =
                                Modifier
                                    .weight(1f)
                                    .testTag(
                                        "upload_audio_button"
                                    ),

                            shape =
                                RoundedCornerShape(12.dp)
                        ) {

                            Icon(
                                Icons.Default.UploadFile,
                                contentDescription =
                                    null,
                                modifier =
                                    Modifier.size(18.dp)
                            )

                            Spacer(
                                modifier =
                                    Modifier.width(6.dp)
                            )

                            Text(
                                "بارگذاری صوت",
                                fontSize = 13.sp
                            )
                        }

                        Button(

                            onClick = {

                                if (isRecording) {

                                    viewModel
                                        .finishRecordingAndAttach()

                                    Toast.makeText(
                                        context,
                                        "صوت ضبط شد و به جلسه الصاق گردید",
                                        Toast.LENGTH_SHORT
                                    ).show()

                                } else {

                                    val hasMic =
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) ==
                                            PackageManager
                                                .PERMISSION_GRANTED

                                    if (hasMic) {

                                        viewModel
                                            .audioRecorder
                                            .startRecording()

                                    } else {

                                        micPermissionLauncher
                                            .launch(
                                                Manifest.permission.RECORD_AUDIO
                                            )
                                    }
                                }
                            },

                            colors =

                                if (isRecording)

                                    ButtonDefaults
                                        .buttonColors(
                                            containerColor =
                                                MaterialTheme
                                                    .colorScheme
                                                    .error
                                        )

                                else

                                    ButtonDefaults
                                        .buttonColors(
                                            containerColor =
                                                MaterialTheme
                                                    .colorScheme
                                                    .secondary
                                        ),

                            modifier =
                                Modifier
                                    .weight(1f)
                                    .testTag(
                                        "record_audio_button"
                                    ),

                            shape =
                                RoundedCornerShape(12.dp)

                        ) {

                            Icon(

                                imageVector =

                                    if (isRecording)
                                        Icons.Default.Stop
                                    else
                                        Icons.Default.Mic,

                                contentDescription =
                                    null,

                                modifier =
                                    Modifier.size(18.dp)
                            )

                            Spacer(
                                modifier =
                                    Modifier.width(6.dp)
                            )

                            Text(

                                if (isRecording)
                                    "توقف ضبط"
                                else
                                    "ضبط صدای کلاس",

                                fontSize = 13.sp
                            )
                        }
                    }

                    OutlinedTextField(
                        value = audioUrl,
                        onValueChange = { viewModel.formAudioUrl.value = it },
                        label = { Text("لینک صوت (اختیاری)") },
                        placeholder = { Text("https://...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("audio_url_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Recording banner

                    AnimatedVisibility(
                        visible = isRecording
                    ) {

                        Surface(

                            shape =
                                RoundedCornerShape(12.dp),

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .errorContainer
                                    .copy(alpha = 0.6f),

                            modifier =
                                Modifier.fillMaxWidth()
                        ) {

                            Row(

                                modifier =
                                    Modifier.padding(12.dp),

                                verticalAlignment =
                                    Alignment.CenterVertically,

                                horizontalArrangement =
                                    Arrangement.SpaceBetween
                            ) {

                                Row(

                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {

                                    Box(

                                        modifier =
                                            Modifier
                                                .size(14.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    MaterialTheme
                                                        .colorScheme
                                                        .error
                                                )
                                    )

                                    Spacer(
                                        modifier =
                                            Modifier.width(8.dp)
                                    )

                                    Text(

                                        text =
                                            "در حال ضبط صدای استاد: " +
                                                formatLectureDuration(
                                                    recordingDurationMs
                                                ),

                                        style =
                                            MaterialTheme
                                                .typography
                                                .bodyMedium,

                                        fontWeight =
                                            FontWeight.Bold,

                                        color =
                                            MaterialTheme
                                                .colorScheme
                                                .onErrorContainer
                                    )
                                }

                                Row(

                                    horizontalArrangement =
                                        Arrangement.spacedBy(2.dp),

                                    verticalAlignment =
                                        Alignment.CenterVertically
                                ) {

                                    repeat(5) { i ->

                                        val heightDp =

                                            (
                                                (
                                                    amplitude *
                                                        24.dp.value
                                                ) *
                                                    (
                                                        (i + 1) *
                                                            0.3f
                                                    )
                                            )
                                                .coerceIn(
                                                    4f,
                                                    24f
                                                )
                                                .dp

                                        Box(

                                            modifier =
                                                Modifier
                                                    .width(4.dp)
                                                    .height(heightDp)
                                                    .background(
                                                        MaterialTheme
                                                            .colorScheme
                                                            .error,

                                                        RoundedCornerShape(
                                                            2.dp
                                                        )
                                                    )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Attached Audio

                    if (!audioPath.isNullOrBlank()) {

                        Surface(

                            shape =
                                RoundedCornerShape(12.dp),

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .surfaceVariant,

                            modifier =
                                Modifier.fillMaxWidth()
                        ) {

                            Row(

                                modifier =
                                    Modifier.padding(12.dp),

                                verticalAlignment =
                                    Alignment.CenterVertically,

                                horizontalArrangement =
                                    Arrangement.SpaceBetween
                            ) {

                                Row(

                                    verticalAlignment =
                                        Alignment.CenterVertically,

                                    modifier =
                                        Modifier.weight(1f)
                                ) {

                                    Icon(

                                        imageVector =
                                            Icons.Default.AudioFile,

                                        contentDescription =
                                            null,

                                        tint =
                                            MaterialTheme
                                                .colorScheme
                                                .primary,

                                        modifier =
                                            Modifier.size(24.dp)
                                    )

                                    Spacer(
                                        modifier =
                                            Modifier.width(8.dp)
                                    )

                                    Column {

                                        Text(

                                            text =
                                                "صوت کلاس پیوست شده",

                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .labelMedium,

                                            fontWeight =
                                                FontWeight.Bold
                                        )

                                        Text(

                                            text =
                                                "مدت زمان: " +
                                                    formatLectureDuration(
                                                        audioDurationMs
                                                    ),

                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,

                                            color =
                                                MaterialTheme
                                                    .colorScheme
                                                    .onSurfaceVariant
                                        )
                                    }
                                }

                                Row {

                                    IconButton(

                                        onClick = {

                                            audioPath?.let { path ->

                                                viewModel
                                                    .audioPlayer
                                                    .togglePlayPause(
                                                        path
                                                    )
                                            }
                                        }
                                    ) {

                                        Icon(

                                            imageVector =

                                                if (
                                                    isPlaying &&
                                                    activePath ==
                                                        audioPath
                                                )

                                                    Icons.Default.Pause

                                                else

                                                    Icons.Default.PlayArrow,

                                            contentDescription =
                                                "پخش پیش‌نمایش",

                                            tint =
                                                MaterialTheme
                                                    .colorScheme
                                                    .primary
                                        )
                                    }

                                    IconButton(

                                        onClick = {

                                            viewModel
                                                .audioPlayer
                                                .stop()

                                            viewModel
                                                .formAudioPath
                                                .value = null

                                            viewModel
                                                .formAudioDurationMs
                                                .value = 0L
                                        }
                                    ) {

                                        Icon(

                                            imageVector =
                                                Icons.Default.Delete,

                                            contentDescription =
                                                "حذف صوت",

                                            tint =
                                                MaterialTheme
                                                    .colorScheme
                                                    .error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------
            // Section 3 - Transcription
            // -------------------------------------------------

            ElevatedCard(

                shape =
                    RoundedCornerShape(18.dp),

                colors =
                    CardDefaults.elevatedCardColors(
                        containerColor =
                            MaterialTheme
                                .colorScheme
                                .surface
                    )
            ) {

                Column(

                    modifier =
                        Modifier.padding(16.dp),

                    verticalArrangement =
                        Arrangement.spacedBy(12.dp)
                ) {

                    Row(

                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.SpaceBetween,

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Text(

                            text =
                                "۳. متن پیاده‌شده و تبدیل صوت به متن",

                            style =
                                MaterialTheme
                                    .typography
                                    .titleSmall,

                            fontWeight =
                                FontWeight.Bold,

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .primary
                        )

                        // دکمه تبدیل فایل صوتی موجود به متن
                        // -------------------------------------------------

                        OutlinedButton(

                            onClick = {

                                if (audioPath.isNullOrBlank()) {

                                    Toast.makeText(
                                        context,
                                        "ابتدا صوت کلاس را ضبط یا بارگذاری کنید",
                                        Toast.LENGTH_SHORT
                                    ).show()

                                } else {

                                    viewModel
                                        .transcribeCurrentAudio()
                                }
                            },

                            enabled =
                                !audioPath.isNullOrBlank() &&
                                    !isAiLoading,

                            shape =
                                RoundedCornerShape(12.dp),

                            modifier =
                                Modifier.testTag(
                                    "speech_to_text_button"
                                )
                        ) {

                            if (isAiLoading) {

                                CircularProgressIndicator(

                                    modifier =
                                        Modifier.size(16.dp),

                                    strokeWidth =
                                        2.dp
                                )

                            } else {

                                Icon(

                                    imageVector =
                                        Icons.Default.GraphicEq,

                                    contentDescription =
                                        null,

                                    modifier =
                                        Modifier.size(16.dp)
                                )
                            }

                            Spacer(
                                modifier =
                                    Modifier.width(6.dp)
                            )

                            Text(

                                text =

                                    if (isAiLoading)
                                        "در حال تبدیل..."
                                    else
                                        "تبدیل صوت به متن",

                                fontSize = 12.sp
                            )
                        }
                    }

                    // نمایش ریزمرحله‌های واقعی تبدیل صوت به متن

                    AnimatedVisibility(visible = isAiLoading) {
                        val progress = transcriptionProgress
                        val percent = progress?.percent ?: 0
                        val eta = progress?.etaMs
                        val elapsed = progress?.elapsedMs ?: 0L

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = progress?.stage ?: aiOperationTitle.ifBlank { "در حال پردازش فایل صوتی..." },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = if (percent > 0) "${percent}٪" else "…",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { (percent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                progress?.detail?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("زمان سپری‌شده: ${formatElapsed(elapsed)}", style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        if (eta != null && eta > 0) "زمان باقی‌مانده: ${formatElapsed(eta)}"
                                        else "زمان باقی‌مانده: در حال محاسبه",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }

                                if (progress != null && progress.totalBytes > 0L) {
                                    Text(
                                        "ارسال فایل: ${formatBytes(progress.uploadedBytes)} از ${formatBytes(progress.totalBytes)}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }

                                val stages = listOf(
                                    "بررسی فایل",
                                    "آماده‌سازی ارسال",
                                    "ارسال فایل",
                                    "فایل دریافت شد",
                                    "تبدیل گفتار به متن",
                                    "تکمیل متن"
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    stages.forEach { stage ->
                                        val active = progress?.stage?.contains(stage) == true
                                        Text(
                                            text = (if (active) "● " else "○ ") + stage,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // نمایش خطا

                    if (!aiError.isNullOrBlank()) {

                        Surface(

                            shape =
                                RoundedCornerShape(10.dp),

                            color =
                                MaterialTheme
                                    .colorScheme
                                    .errorContainer,

                            modifier =
                                Modifier.fillMaxWidth()
                        ) {

                            Text(

                                text =
                                    aiError ?: "",

                                modifier =
                                    Modifier.padding(12.dp),

                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,

                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onErrorContainer
                            )
                        }
                    }

                    // AI Polish Button

                    Row(

                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            Arrangement.End
                    ) {

                        FilledTonalButton(

                            onClick = {

                                viewModel
                                    .polishTranscriptInForm()
                            },

                            enabled =
                                !isAiLoading &&
                                    transcript.isNotBlank(),

                            shape =
                                RoundedCornerShape(10.dp),

                            modifier =
                                Modifier.testTag(
                                    "ai_polish_button"
                                )
                        ) {

                            if (isAiLoading) {

                                CircularProgressIndicator(

                                    modifier =
                                        Modifier.size(14.dp),

                                    strokeWidth =
                                        2.dp
                                )

                            } else {

                                Icon(

                                    Icons.Default.AutoAwesome,

                                    contentDescription =
                                        null,

                                    modifier =
                                        Modifier.size(14.dp)
                                )
                            }

                            Spacer(
                                modifier =
                                    Modifier.width(6.dp)
                            )

                            Text(

                                "اصلاح ساختار و نگارش با هوش مصنوعی ✨",

                                fontSize = 12.sp
                            )
                        }
                    }

                    // Transcript Text Field

                    OutlinedTextField(

                        value =
                            transcript,

                        onValueChange = {

                            viewModel
                                .formTranscript
                                .value = it
                        },

                        placeholder = {

                            Text(

                                text =
                                    "متن صحبت‌های استاد در اینجا نمایش داده شده و پیاده‌سازی می‌شود. همچنین می‌توانید متن را ویرایش یا به آن نکاتی اضافه کنید...",

                                style =
                                    MaterialTheme
                                        .typography
                                        .bodyMedium,

                                lineHeight =
                                    22.sp
                            )
                        },

                        modifier =

                            Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                                .testTag(
                                    "lecture_transcript_input"
                                ),

                        shape =
                            RoundedCornerShape(12.dp)
                    )

                    // Word count

                    val wordCount =
                        remember(transcript) {

                            transcript
                                .split(
                                    Regex("\\s+")
                                )
                                .filter {
                                    it.isNotBlank()
                                }
                                .size
                        }

                    Text(

                        text =
                            "تعداد کلمات: $wordCount کلمه",

                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,

                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )
        }
    }
}

// ---------------------------------------------------------
// تبدیل میلی‌ثانیه به زمان خوانا
// ---------------------------------------------------------

private fun formatElapsed(ms: Long): String {
    val totalSeconds = max(0L, ms / 1000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0) "${minutes}د ${seconds}ث" else "${seconds}ث"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "${bytes} بایت"
    val kb = bytes / 1024L
    if (kb < 1024L) return "${kb} کیلوبایت"
    return "${kb / 1024L} مگابایت"
}

private fun formatLectureDuration(
    millis: Long
): String {

    val totalSeconds =
        millis / 1000

    val hours =
        totalSeconds / 3600

    val minutes =
        (totalSeconds % 3600) / 60

    val seconds =
        totalSeconds % 60

    return if (hours > 0) {

        String.format(
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        )

    } else {

        String.format(
            "%02d:%02d",
            minutes,
            seconds
        )
    }
}
