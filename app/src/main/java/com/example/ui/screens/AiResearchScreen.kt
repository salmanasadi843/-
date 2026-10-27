package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val allLectures by viewModel.repository.allLectures.collectAsState(initial = emptyList())
    val allClasses by viewModel.repository.allClasses.collectAsState(initial = emptyList())
    val query by viewModel.aiResearchQuery.collectAsState()
    val answer by viewModel.aiResearchAnswer.collectAsState()
    val error by viewModel.aiResearchError.collectAsState()
    val loading by viewModel.aiResearchLoading.collectAsState()
    val expandingSemanticTerms by viewModel.isSemanticExpansionLoading.collectAsState()
    val sourceIds by viewModel.aiResearchSourceIds.collectAsState()

    val studentClassIds = remember(allClasses) {
        allClasses.filter { it.id < 0L && it.isPublished }.map { it.id }.toSet()
    }
    val availableLectures = remember(allLectures, studentClassIds, userRole) {
        if (userRole == UserRole.STUDENT) {
            allLectures.filter { it.id < 0L && it.isPublished && it.classId in studentClassIds }
        } else allLectures
    }
    val sourceLectures = remember(allLectures, sourceIds) {
        sourceIds.mapNotNull { id -> allLectures.firstOrNull { it.id == id } }
    }

    fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک').replace('ۀ', 'ه').replace('ة', 'ه')
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("[\\u200C\\u200F\\u202A-\\u202E]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun runSearch(conceptTerms: List<String> = emptyList()) {
        if (query.isBlank() || loading) return
        val queryTerms = normalize(query).split(Regex("\\s+")).filter { it.length > 1 }.distinct()
        val terms = (queryTerms + conceptTerms.map(::normalize)).filter { it.length > 1 }.distinct()
        if (terms.isEmpty()) {
            viewModel.aiResearchError.value = "لطفاً عبارت دقیق‌تری وارد کنید."
            return
        }

        val ranked = availableLectures.map { lecture ->
            val titleAndMetadata = normalize(
                listOf(lecture.title, lecture.courseName, lecture.professorName, lecture.tags).joinToString(" ")
            )
            val original = normalize(lecture.originalTranscript)
            val edited = normalize(lecture.transcript)
            val summary = normalize(lecture.aiSummary.orEmpty())
            val keyPoints = normalize(lecture.aiKeyPoints.orEmpty())
            val quiz = normalize(lecture.aiQuizJson.orEmpty())
            val allContent = listOf(titleAndMetadata, original, edited, summary, keyPoints, quiz).joinToString(" ")
            val score = terms.sumOf { term ->
                (if (titleAndMetadata.contains(term)) 5 else 0) +
                    (if (edited.contains(term)) 3 else 0) +
                    (if (original.contains(term)) 2 else 0) +
                    (if (summary.contains(term)) 4 else 0) +
                    (if (keyPoints.contains(term) || quiz.contains(term)) 2 else 0)
            }
            val allTermsMatch = queryTerms.all { allContent.contains(it) }
            lecture to (score + if (allTermsMatch) 2 else 0)
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<LectureEntity, Int>> { it.second }.thenByDescending { it.first.dateMillis })
            .take(8)

        if (ranked.isEmpty()) {
            viewModel.aiResearchSourceIds.value = emptyList()
            viewModel.aiResearchAnswer.value = ""
            viewModel.aiResearchError.value = "در محتوای قابل‌دسترسی جلسه‌ای با این عبارت پیدا نشد. عبارت دیگری امتحان کنید."
            return
        }

        val selected = ranked.map { it.first }
        viewModel.aiResearchSourceIds.value = selected.map { it.id }
        val sourceContext = selected.mapIndexed { index, lecture ->
            val id = "[S${index + 1}]"
            buildString {
                append(id).append('\n')
                append("عنوان جلسه: ").append(lecture.title).append('\n')
                append("درس: ").append(lecture.courseName).append(" | استاد: ").append(lecture.professorName).append('\n')
                if (lecture.originalTranscript.isNotBlank()) {
                    append("متن اصلی: ").append(lecture.originalTranscript.take(1600)).append('\n')
                }
                if (lecture.transcript.isNotBlank()) {
                    append("متن ویرایش‌شده: ").append(lecture.transcript.take(1600)).append('\n')
                }
                if (!lecture.aiSummary.isNullOrBlank()) {
                    append("خلاصه هوشمند: ").append(lecture.aiSummary.take(1000)).append('\n')
                }
                if (!lecture.aiKeyPoints.isNullOrBlank()) {
                    append("کلیدواژه‌ها: ").append(lecture.aiKeyPoints.take(600)).append('\n')
                }
            }
        }.joinToString("\n\n---\n\n").take(19000)

        viewModel.aiResearchLoading.value = true
        viewModel.aiResearchError.value = null
        viewModel.aiResearchAnswer.value = ""
        viewModel.runAiResearch(query.trim(), sourceContext) { result ->
            viewModel.aiResearchLoading.value = false
            result.onSuccess { viewModel.aiResearchAnswer.value = it }
                .onFailure {
                    viewModel.aiResearchError.value =
                        it.localizedMessage ?: "جستجوی هوشمند ناموفق بود؛ اتصال و کلید Gemini یا Groq را بررسی کنید."
                }
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
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "پرسش را به زبان طبیعی بنویسید. پاسخ بر اساس جلسه‌های قابل‌دسترسی ساخته می‌شود و باید شناسهٔ منبع داشته باشد.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = query,
                onValueChange = {
                    viewModel.aiResearchQuery.value = it
                    viewModel.aiResearchError.value = null
                    viewModel.aiResearchAnswer.value = ""
                    viewModel.aiResearchSourceIds.value = emptyList()
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                label = { Text("موضوع یا پرسش پژوهشی") },
                placeholder = { Text("مثلاً دیدگاه استادان درباره حجیت خبر واحد چه تفاوتی دارد؟") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            Button(
                onClick = {
                    viewModel.aiResearchError.value = null
                    viewModel.aiResearchAnswer.value = ""
                    viewModel.aiResearchSourceIds.value = emptyList()
                    viewModel.expandSemanticSearchQuery(query) { expandedTerms -> runSearch(expandedTerms) }
                },
                enabled = query.isNotBlank() && !loading && !expandingSemanticTerms,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading || expandingSemanticTerms) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        expandingSemanticTerms -> "در حال گسترش مفاهیم…"
                        loading -> "در حال بررسی جلسه‌ها…"
                        else -> "گسترش معنایی و تحلیل با هوش مصنوعی"
                    }
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (answer.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("پاسخ پژوهشی", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("پاسخ پژوهشی درس‌یار", answer))
                            Toast.makeText(context, "پاسخ کپی شد", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = "کپی پاسخ") }
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, answer)
                            }
                            context.startActivity(Intent.createChooser(send, "اشتراک‌گذاری پاسخ پژوهشی"))
                        }) { Icon(Icons.Default.Share, contentDescription = "اشتراک‌گذاری پاسخ") }
                    }
                }
                Surface(
                    Modifier.weight(1f).fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            answer,
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Text("جلسه‌های منبع", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                LazyColumn(
                    Modifier.heightIn(max = 132.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(sourceLectures, key = { it.id }) { lecture ->
                        val sourceIndex = sourceLectures.indexOfFirst { it.id == lecture.id } + 1
                        Card(Modifier.fillMaxWidth().clickable { onOpenLecture(lecture.id) }) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("[S$sourceIndex] ${lecture.title}", fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Text(
                                    "${lecture.professorName} • ${lecture.courseName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            } else {
                if (sourceLectures.isNotEmpty()) {
                    Text("جلسه‌های مرتبط", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(sourceLectures, key = { it.id }) { lecture ->
                            val sourceIndex = sourceLectures.indexOfFirst { it.id == lecture.id } + 1
                            Card(Modifier.fillMaxWidth().clickable { onOpenLecture(lecture.id) }) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("[S$sourceIndex] ${lecture.title}", fontWeight = FontWeight.SemiBold)
                                    Text("${lecture.professorName} • ${lecture.courseName}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        "این جستجو متن اصلی، متن ویرایش‌شده، خلاصه و کلیدواژه‌های جلسه‌های قابل‌دسترسی را بررسی می‌کند. برای ادعاهای اصلی، شناسهٔ منبع نمایش داده می‌شود.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
