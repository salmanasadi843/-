package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.Card
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
    val course by viewModel.formCourse.collectAsState()
    val professor by viewModel.formProfessor.collectAsState()
    val tags by viewModel.formTags.collectAsState()
    val transcript by viewModel.formTranscript.collectAsState()
    val audioPath by viewModel.formAudioPath.collectAsState()
    val audioDurationMs by viewModel.formAudioDurationMs.collectAsState()

    val isRecording by viewModel.audioRecorder.isRecording.collectAsState()
    val recordingDurationMs by viewModel.audioRecorder.recordingDurationMs.collectAsState()
    val amplitude by viewModel.audioRecorder.amplitude.collectAsState()

    val isListening by viewModel.speechRecognizer.isListening.collectAsState()
    val liveSpokenText by viewModel.speechRecognizer.liveSpokenText.collectAsState()
    val speechError by viewModel.speechRecognizer.errorMessage.collectAsState()
    val recognizedSegments by viewModel.speechRecognizer.recognizedSegments.collectAsState()

    val isPlaying by viewModel.audioPlayer.isPlaying.collectAsState()
    val activePath by viewModel.audioPlayer.activeFilePath.collectAsState()

    val isAiLoading by viewModel.isAiLoading.collectAsState()
    val aiOperationTitle by viewModel.aiOperationTitle.collectAsState()

    // File picker launcher for audio files
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importAudioUri(it)
            Toast.makeText(context, "فایل صوتی بارگذاری شد", Toast.LENGTH_SHORT).show()
        }
    }

    // Permission launcher for microphone recording
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.audioRecorder.startRecording()
        } else {
            Toast.makeText(context, "دسترسی میکروفون برای ضبط صدا لازم است", Toast.LENGTH_SHORT).show()
        }
    }

    // Permission launcher for SpeechRecognizer
    val speechPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.speechRecognizer.startListening("fa-IR")
        } else {
            Toast.makeText(context, "دسترسی میکروفون برای تبدیل گفتار به متن الزامی است", Toast.LENGTH_SHORT).show()
        }
    }

    // Append newly recognized speech segments to transcript automatically
    LaunchedEffect(recognizedSegments.size) {
        if (recognizedSegments.isNotEmpty()) {
            val latest = recognizedSegments.last()
            val current = viewModel.formTranscript.value
            viewModel.formTranscript.value = if (current.isBlank()) latest else "$current\n$latest"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (lectureId == null) "ثبت جلسه جدید کلاس" else "ویرایش جلسه کلاس",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "انصراف")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            if (title.isBlank()) {
                                Toast.makeText(context, "لطفاً عنوان جلسه را وارد کنید", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.saveCurrentForm(lectureId) { savedId ->
                                    Toast.makeText(context, "اطلاعات جلسه با موفقیت ذخیره شد", Toast.LENGTH_SHORT).show()
                                    onSaved(savedId)
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("save_lecture_button")
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ذخیره")
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Lecture Titling and Metadata
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "۱. عنوان‌دهی و مشخصات جلسه",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = title,
                        onValueChange = { viewModel.formTitle.value = it },
                        label = { Text("عنوان جلسه یا مبحث درس (الزامی)*") },
                        placeholder = { Text("مثال: جلسه ۴ - شبکه‌های عصبی عمیق") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lecture_title_input"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = course,
                            onValueChange = { viewModel.formCourse.value = it },
                            label = { Text("نام درس") },
                            placeholder = { Text("مثال: هوش مصنوعی") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lecture_course_input"),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = professor,
                            onValueChange = { viewModel.formProfessor.value = it },
                            label = { Text("نام استاد") },
                            placeholder = { Text("مثال: دکتر مهدوی") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("lecture_professor_input"),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )
                    }

                    OutlinedTextField(
                        value = tags,
                        onValueChange = { viewModel.formTags.value = it },
                        label = { Text("برچسب‌ها (با ویرگول جدا کنید)") },
                        placeholder = { Text("امتحان, میان‌ترم, فصل۳, فرمول") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lecture_tags_input"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }
            }

            // Section 2: Audio Upload & Recording
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "۲. صوت کلاس (بارگذاری یا ضبط زنده)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Buttons: Upload file OR Record
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { audioPickerLauncher.launch("audio/*") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("upload_audio_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("بارگذاری صوت", fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                if (isRecording) {
                                    viewModel.finishRecordingAndAttach()
                                    Toast.makeText(context, "صوت ضبط شد و به درس الصاق گردید", Toast.LENGTH_SHORT).show()
                                } else {
                                    val hasMic = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasMic) {
                                        viewModel.audioRecorder.startRecording()
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            colors = if (isRecording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("record_audio_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isRecording) "توقف ضبط" else "ضبط صدای کلاس", fontSize = 13.sp)
                        }
                    }

                    // Recording In-Progress Banner
                    AnimatedVisibility(visible = isRecording) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "در حال ضبط صدای استاد: ${formatDuration(recordingDurationMs)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }

                                // Amplitude indicator
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    repeat(5) { i ->
                                        val heightDp = ((amplitude * 24.dp.value) * ((i + 1) * 0.3f)).coerceIn(4f, 24f).dp
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .height(heightDp)
                                                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Attached Audio Preview
                    if (!audioPath.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AudioFile,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "صوت کلاس پیوست شده",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "مدت زمان: ${formatDuration(audioDurationMs)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row {
                                    IconButton(onClick = {
                                        audioPath?.let { path ->
                                            viewModel.audioPlayer.togglePlayPause(path)
                                        }
                                    }) {
                                        Icon(
                                            imageVector = if (isPlaying && activePath == audioPath) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "پخش پیش‌نمایش",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = {
                                        viewModel.audioPlayer.stop()
                                        viewModel.formAudioPath.value = null
                                        viewModel.formAudioDurationMs.value = 0L
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "حذف صوت",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 3: Speech to Text (تبدیل صوت به متن) & Transcript Editor
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "۳. متن پیاده‌شده و تبدیل صوت به متن",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Speech-to-Text Button
                        OutlinedButton(
                            onClick = {
                                if (isListening) {
                                    viewModel.speechRecognizer.stopListening()
                                } else {
                                    val hasMic = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasMic) {
                                        viewModel.speechRecognizer.startListening("fa-IR")
                                    } else {
                                        speechPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = if (isListening) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.testTag("speech_to_text_button")
                        ) {
                            Icon(
                                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.GraphicEq,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isListening) "توقف تبدیل گفتار" else "تبدیل صوت به متن 🎤", fontSize = 12.sp)
                        }
                    }

                    // Live Speech-to-text Indicator
                    AnimatedVisibility(visible = isListening) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "در حال شنیدن سخنان استاد به زبان فارسی...",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (liveSpokenText.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "«$liveSpokenText»",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    if (speechError != null) {
                        Text(
                            text = speechError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // AI Polish Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.polishTranscriptInForm() },
                            enabled = !isAiLoading && transcript.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("ai_polish_button")
                        ) {
                            if (isAiLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("اصلاح ساختار و نگارش با هوش مصنوعی ✨", fontSize = 12.sp)
                        }
                    }

                    // Transcript Text Field
                    OutlinedTextField(
                        value = transcript,
                        onValueChange = { viewModel.formTranscript.value = it },
                        placeholder = {
                            Text(
                                text = "متن صحبت‌های استاد در اینجا نمایش داده شده و پیاده‌سازی می‌شود. همچنین می‌توانید متن را ویرایش یا به آن نکاتی اضافه کنید...",
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 22.sp
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .testTag("lecture_transcript_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Word count indicator
                    val wordCount = remember(transcript) {
                        transcript.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                    }
                    Text(
                        text = "تعداد کلمات: $wordCount کلمه",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
