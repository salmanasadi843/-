package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.LectureEntity
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiResearchScreen(
    viewModel: MainViewModel,
    userRole: UserRole,
    onOpenLecture: (Long) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val allLectures by viewModel.repository.allLectures.collectAsState(initial = emptyList())
    val allClasses by viewModel.repository.allClasses.collectAsState(initial = emptyList())
    val studentClassIds = remember(allClasses) {
        allClasses.filter { it.id < 0L && it.isPublished }.map { it.id }.toSet()
    }
    val availableLectures = remember(allLectures, studentClassIds, userRole) {
        if (userRole == UserRole.STUDENT) {
            allLectures.filter { it.id < 0L && it.isPublished && (it.classId == null || it.classId in studentClassIds) }
        } else allLectures
    }
    var query by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var sourceLectures by remember { mutableStateOf<List<LectureEntity>>(emptyList()) }

    fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک')
        .replace(Regex("[\\u200c\\u200d]"), "")
        .replace(Regex("\\s+"), " ").trim()

    fun runSearch() {
        if (query.isBlank() || loading) return
        val terms = normalize(query).split(Regex("\\s+")).filter { it.length > 1 }.distinct()
        val ranked = availableLectures.map { lecture ->
            val title = normalize(listOf(lecture.title, lecture.courseName, lecture.professorName, lecture.tags).joinToString(" "))
            val body = normalize(listOf(lecture.transcript, lecture.originalTranscript, lecture.aiSummary.orEmpty(), lecture.aiKeyPoints.orEmpty()).joinToString(" "))
            val score = terms.sumOf { term ->
                (if (title.contains(term)) 5 else 0) + (if (body.contains(term)) 1 else 0)
            }
            lecture to score
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<LectureEntity, Int>> { it.second }.thenByDescending { it.first.dateMillis })
            .take(8)
        val selected = if (ranked.isNotEmpty()) ranked.map { it.first } else availableLectures.sortedByDescending { it.dateMillis }.take(4)
        if (selected.isEmpty()) {
            error = "هنوز جلسهٔ قابل‌جست‌وجویی در این حساب موجود نیست."
            answer = ""
            sourceLectures = emptyList()
            return
        }
        sourceLectures = selected
        val sourceContext = selected.mapIndexed { index, lecture ->
            val id = "[S${index + 1}]"
            val transcript = lecture.transcript.ifBlank { lecture.originalTranscript }
            val content = buildString {
                append("عنوان جلسه: ").append(lecture.title).append('\n')
                append("درس: ").append(lecture.courseName).append(" | استاد: ").append(lecture.professorName).append('\n')
                if (!lecture.aiSummary.isNullOrBlank()) append("خلاصه: ").append(lecture.aiSummary).append('\n')
                if (!lecture.aiKeyPoints.isNullOrBlank()) append("کلیدواژه‌ها: ").append(lecture.aiKeyPoints).append('\n')
                append("متن جلسه: ").append(transcript.take(2600))
            }
            "$id\n$content"
        }.joinToString("\n\n---\n\n").take(19000)
        loading = true
        error = null
        answer = ""
        viewModel.runAiResearch(query.trim(), sourceContext) { result ->
            loading = false
            result.onSuccess { answer = it }
                .onFailure { error = it.localizedMessage ?: "جستجوی هوشمند ناموفق بود؛ کلید Gemini یا Groq را در تنظیمات بررسی کنید." }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("جستجوی هوشمند", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("پرسش را به زبان طبیعی بنویسید؛ پاسخ با ارجاع به جلسه‌های ذخیره‌شده تولید می‌شود.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                label = { Text("پرسش پژوهشی") },
                placeholder = { Text("مثلاً دیدگاه استادان درباره خبر واحد چه تفاوتی دارد؟") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            Button(onClick = { runSearch() }, enabled = query.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "در حال بررسی جلسه‌ها…" else "جست‌وجو و تحلیل با هوش مصنوعی")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (answer.isNotBlank()) {
                Text("پاسخ پژوهشی", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(Modifier.weight(1f, fill = false).fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(answer, Modifier.padding(14.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (!loading && sourceLectures.isEmpty()) {
                Text("این بخش محتوای متن، متن خام، خلاصه و کلیدواژه‌های جلسات را بررسی می‌کند و برای ادعاهای مهم شناسهٔ منبع می‌آورد.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sourceLectures.isNotEmpty()) {
                Text("جلسه‌های بررسی‌شده", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sourceLectures, key = { it.id }) { lecture ->
                        val sourceIndex = sourceLectures.indexOf(lecture) + 1
                        Card(Modifier.fillMaxWidth().clickable { onOpenLecture(lecture.id) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text("[S$sourceIndex] ${lecture.title}", fontWeight = FontWeight.SemiBold)
                                Text("${lecture.professorName} • ${lecture.courseName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
