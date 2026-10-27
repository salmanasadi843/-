package com.example.ui.screens

import androidx.activity.compose.BackHandler
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.example.data.local.LectureEntity
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSearchScreen(
    viewModel: MainViewModel,
    userRole: UserRole,
    onOpenLecture: (Long) -> Unit,
    onBack: () -> Unit
) {
    val exitSearch = {
        viewModel.clearSearchNavigation()
        onBack()
    }
    BackHandler(onBack = exitSearch)
    val allClasses by viewModel.repository.allClasses.collectAsState(initial = emptyList())
    val allLectures by viewModel.repository.allLectures.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val syncStatus by viewModel.studentMaterialsSyncStatus.collectAsState()
    val studentFeedReady = userRole != UserRole.STUDENT || (
        syncStatus != "مطالب آنلاین هنوز بررسی نشده‌اند." &&
            !syncStatus.contains("در حال دریافت") &&
            !syncStatus.contains("دریافت خودکار مطالب انجام نشد")
        )
    val visibleClasses = if (userRole == UserRole.STUDENT) {
        if (studentFeedReady) allClasses.filter { it.id < 0L && it.isPublished } else emptyList()
    } else allClasses
    val visibleClassIds = visibleClasses.map { it.id }.toSet()
    val searchableLectures = if (userRole == UserRole.STUDENT) {
        allLectures.filter { it.id < 0L && it.isPublished && it.classId in visibleClassIds }
    } else allLectures

    var query by remember { mutableStateOf(viewModel.advancedSearchQuery.value) }
    var matchMode by remember { mutableStateOf(viewModel.advancedSearchMatchMode.value) }
    val semanticTerms by viewModel.semanticSearchTerms.collectAsState()
    val expandingSemanticTerms by viewModel.isSemanticExpansionLoading.collectAsState()
    val researchReport by viewModel.researchReport.collectAsState()
    val researchLoading by viewModel.isResearchLoading.collectAsState()
    val researchError by viewModel.researchError.collectAsState()
    var researchType by remember { mutableStateOf("summary") }
    var showResearchPanel by remember { mutableStateOf(false) }
    var selectedProfessor by remember { mutableStateOf(viewModel.advancedSearchProfessor.value) }
    var selectedCourse by remember { mutableStateOf(viewModel.advancedSearchCourse.value) }
    var selectedClassId by remember { mutableStateOf(viewModel.advancedSearchClassId.value) }
    var openFilter by remember { mutableStateOf("") }
    // Restore the result-list viewport when returning from a lecture detail screen.
    val resultsListState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.advancedSearchScrollIndex.value,
        initialFirstVisibleItemScrollOffset = viewModel.advancedSearchScrollOffset.value
    )

    val professors = listOf("همه استادان") + searchableLectures.map { it.professorName.trim() }
        .filter { it.isNotBlank() }.distinct().sorted()
    val courses = listOf("همه درس‌ها") + searchableLectures.map { it.courseName.trim() }
        .filter { it.isNotBlank() }.distinct().sorted()
    val classes = visibleClasses.filter { classItem ->
        searchableLectures.any { it.classId == classItem.id }
    }.sortedBy { it.name }
    val terms = normalizeSearchText(query).split(Regex("\\s+")).filter { it.isNotBlank() }
    val expandedTerms = semanticTerms.map(::normalizeSearchText).filter { it.isNotBlank() }.distinct()

    val results = remember(searchableLectures, query, matchMode, expandedTerms, selectedProfessor, selectedCourse, selectedClassId) {
        searchableLectures.mapNotNull { lecture ->
            val title = normalizeSearchText(lecture.title)
            val metadata = normalizeSearchText(
                listOf(lecture.courseName, lecture.professorName, lecture.tags).joinToString(" ")
            )
            val original = normalizeSearchText(lecture.originalTranscript)
            val edited = normalizeSearchText(lecture.transcript)
            val summary = normalizeSearchText(lecture.aiSummary.orEmpty())
            val keyPoints = normalizeSearchText(lecture.aiKeyPoints.orEmpty())
            val quizQuestions = normalizeSearchText(lecture.aiQuizJson.orEmpty())
            val content = listOf(title, metadata, original, edited, summary, keyPoints, quizQuestions).joinToString(" ")
            val textMatches = when {
                terms.isEmpty() -> true
                matchMode == "phrase" -> content.contains(normalizeSearchText(query).trim())
                matchMode == "semantic" && expandedTerms.isNotEmpty() -> expandedTerms.any { term ->
                    semanticVariants(term).any { variant -> content.contains(variant) }
                }
                else -> terms.all { term ->
                    semanticVariants(term).any { variant -> content.contains(variant) }
                }
            }
            val filtersMatch =
                (selectedProfessor == "همه استادان" || lecture.professorName.trim() == selectedProfessor) &&
                (selectedCourse == "همه درس‌ها" || lecture.courseName.trim() == selectedCourse) &&
                (selectedClassId == null || lecture.classId == selectedClassId)
            if (!textMatches || !filtersMatch) {
                null
            } else {
                // Hybrid ranking: exact phrase, title, metadata, edited/original text,
                // summary and key points. Semantic variants broaden recall but exact
                // matches always receive the strongest score.
                val normalizedQuery = normalizeSearchText(query).trim()
                var score = 0
                if (normalizedQuery.isNotBlank() && title.contains(normalizedQuery)) score += 100
                if (normalizedQuery.isNotBlank() && content.contains(normalizedQuery)) score += 50
                terms.forEach { term ->
                    if (title.contains(term)) score += 18
                    if (metadata.contains(term)) score += 8
                    if (edited.contains(term)) score += 6
                    if (original.contains(term)) score += 5
                    if (summary.contains(term)) score += 7
                    if (keyPoints.contains(term)) score += 4
                    if (quizQuestions.contains(term)) score += 4
                    val variants = semanticVariants(term).filter { it != term }
                    if (variants.any { title.contains(it) }) score += 10
                    if (variants.any { content.contains(it) }) score += 3
                }
                lecture to score
            }
        }.sortedWith(
            compareByDescending<Pair<LectureEntity, Int>> { it.second }
                .thenByDescending { it.first.dateMillis }
        ).map { it.first }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("جستجوی پیشرفته", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = exitSearch) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.advancedSearchQuery.value = it
                    viewModel.clearSemanticSearchTerms()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
                label = { Text("واژه، عبارت یا موضوع درس") },
                placeholder = { Text("مثلاً: عقل عملی، خبر واحد، حسن و قبح") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = matchMode == "all",
                    onClick = { matchMode = "all"; viewModel.advancedSearchMatchMode.value = "all" },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                ) { Text("همه واژه‌ها") }
                SegmentedButton(
                    selected = matchMode == "phrase",
                    onClick = { matchMode = "phrase"; viewModel.advancedSearchMatchMode.value = "phrase" },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                ) { Text("عبارت دقیق") }
                SegmentedButton(
                    selected = matchMode == "semantic",
                    onClick = {
                        matchMode = "semantic"
                        viewModel.advancedSearchMatchMode.value = "semantic"
                        if (semanticTerms.isEmpty() && query.isNotBlank()) viewModel.expandSemanticSearchQuery(query)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                ) { Text("معنایی") }
            }
            if (matchMode == "semantic") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { viewModel.expandSemanticSearchQuery(query) },
                        enabled = query.isNotBlank() && !expandingSemanticTerms
                    ) { Text(if (expandingSemanticTerms) "در حال گسترش مفاهیم…" else "گسترش مفهومی با AI") }
                    Text(
                        if (semanticTerms.isEmpty()) "پس از گسترش، مفاهیم مرتبط به‌صورت محلی جست‌وجو می‌شوند."
                        else "${semanticTerms.size} عبارت مفهومی برای جست‌وجو آماده است.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            OutlinedButton(
                onClick = { showResearchPanel = !showResearchPanel },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (showResearchPanel) "بستن دستیار پژوهش" else "دستیار پژوهش: جمع‌بندی و دسته‌بندی موضوع") }
            if (showResearchPanel) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("خروجی پژوهشی از ${results.size} جلسه بازیابی‌شده", fontWeight = FontWeight.Bold)
                        Text("فقط منابعی که در نتایج قابل‌دسترسی شما هستند برای تحلیل فرستاده می‌شوند. برای تولید گزارش، متن منتخب به Gemini و در صورت خطا به Groq ارسال می‌شود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf("summary" to "جمع‌بندی", "categorize" to "دسته‌بندی", "compare" to "مقایسه", "article" to "طرح مقاله").forEachIndexed { index, item ->
                                SegmentedButton(
                                    selected = researchType == item.first,
                                    onClick = { researchType = item.first },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 4)
                                ) { Text(item.second) }
                            }
                        }
                        Button(
                            onClick = {
                                val sourceTerms = (if (matchMode == "semantic" && expandedTerms.isNotEmpty()) expandedTerms else terms)
                                    .filter { it.length >= 2 }.distinct()
                                fun relevantText(source: String, limit: Int): String {
                                    if (source.isBlank()) return ""
                                    val paragraphs = source.split(Regex("\\n\\s*\\n|(?<=[.!؟])\\s+"))
                                    val relevant = paragraphs.filter { paragraph ->
                                        val normalizedParagraph = normalizeSearchText(paragraph)
                                        sourceTerms.any { term ->
                                            normalizedParagraph.contains(term) ||
                                                term.split(Regex("\\s+")).filter { it.length >= 3 }.let { words ->
                                                    words.size >= 2 && words.all { normalizedParagraph.contains(it) }
                                                }
                                        }
                                    }
                                    return (if (relevant.isNotEmpty()) relevant.joinToString("\n") else source).take(limit)
                                }
                                val sourceText = results.take(12).mapIndexed { index, lecture ->
                                    val id = "S${index + 1}"
                                    val body = listOf(
                                        "متن اصلی درس: " + relevantText(lecture.originalTranscript, 1400),
                                        "متن ویرایش‌شده: " + relevantText(lecture.transcript, 1400),
                                        "خلاصه هوشمند: " + relevantText(lecture.aiSummary.orEmpty(), 700),
                                        "کلیدواژه‌ها: " + relevantText(lecture.aiKeyPoints.orEmpty(), 400)
                                    ).filter { it.substringAfter(": ").isNotBlank() }.joinToString("\n")
                                    "[$id] عنوان جلسه: ${lecture.title}\nاستاد: ${lecture.professorName}\nدرس: ${lecture.courseName}\n$body"
                                }.joinToString("\n\n")
                                viewModel.generateResearchReport(query, researchType, sourceText)
                            },
                            enabled = query.isNotBlank() && results.isNotEmpty() && !researchLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (researchLoading) "در حال تهیه گزارش مستند…" else "تولید خروجی مستند از نتایج") }
                        if (researchError != null) Text(researchError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (researchLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (researchReport.isNotBlank()) {
                            HorizontalDivider()
                            // Keep long AI reports inside a bounded, independently scrollable area.
                            // SelectionContainer allows selecting/copying any part of the generated text.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = researchReport,
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("گزارش پژوهشی درس‌یار", researchReport))
                                }) { Text("کپی گزارش") }
                                TextButton(onClick = {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "گزارش پژوهشی درس‌یار")
                                        putExtra(Intent.EXTRA_TEXT, researchReport)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "اشتراک‌گذاری گزارش"))
                                }) { Text("ارسال / اشتراک‌گذاری") }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    TextButton(onClick = { openFilter = if (openFilter == "professor") "" else "professor" }) {
                        Text(if (selectedProfessor == "همه استادان") "استاد: همه" else selectedProfessor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = openFilter == "professor", onDismissRequest = { openFilter = "" }) {
                        professors.forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { selectedProfessor = name; viewModel.advancedSearchProfessor.value = name; openFilter = "" })
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    TextButton(onClick = { openFilter = if (openFilter == "course") "" else "course" }) {
                        Text(if (selectedCourse == "همه درس‌ها") "درس: همه" else selectedCourse, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = openFilter == "course", onDismissRequest = { openFilter = "" }) {
                        courses.forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { selectedCourse = name; viewModel.advancedSearchCourse.value = name; openFilter = "" })
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    TextButton(onClick = { openFilter = if (openFilter == "class") "" else "class" }) {
                        Text(classes.firstOrNull { it.id == selectedClassId }?.name ?: "کلاس: همه", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = openFilter == "class", onDismissRequest = { openFilter = "" }) {
                        DropdownMenuItem(text = { Text("همه کلاس‌ها") }, onClick = { selectedClassId = null; viewModel.advancedSearchClassId.value = null; openFilter = "" })
                        classes.forEach { item ->
                            DropdownMenuItem(text = { Text(item.name) }, onClick = { selectedClassId = item.id; viewModel.advancedSearchClassId.value = item.id; openFilter = "" })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${results.size} نتیجه", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    query = ""
                    matchMode = "all"
                    selectedProfessor = "همه استادان"
                    selectedCourse = "همه درس‌ها"
                    selectedClassId = null
                    viewModel.advancedSearchQuery.value = ""
                    viewModel.advancedSearchMatchMode.value = "all"
                    viewModel.advancedSearchProfessor.value = "همه استادان"
                    viewModel.advancedSearchCourse.value = "همه درس‌ها"
                    viewModel.advancedSearchClassId.value = null
                }) { Text("پاک‌کردن فیلترها") }
            }
            if (userRole == UserRole.STUDENT && !studentFeedReady) {
                Text("برای جلوگیری از نمایش مطالبی که انتشارشان لغو شده، تا پایان بررسی همگام‌سازی جستجو موقتاً خالی است.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (query.isBlank()) {
                Text("عبارت موردنظر را وارد کنید. جستجو در عنوان، استاد و درس، برچسب‌ها، متن اصلی، متن ویرایش‌شده، خلاصه، کلیدواژه‌ها و پرسش‌های هوشمند انجام می‌شود.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (results.isEmpty()) {
                Text("نتیجه‌ای پیدا نشد. عبارت یا فیلترها را تغییر دهید.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(
                state = resultsListState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { it.id }) { lecture ->
                    val className = classes.firstOrNull { it.id == lecture.classId }?.name
                    val highlightTerms = when {
                        matchMode == "semantic" && expandedTerms.isNotEmpty() -> expandedTerms
                        matchMode == "phrase" -> (listOf(normalizeSearchText(query).trim()) + terms).filter { it.isNotBlank() }.distinct()
                        else -> terms
                    }
                    val excerpt = makeSearchExcerpt(lecture, highlightTerms, query, matchMode)
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            viewModel.saveAdvancedSearchScrollPosition(
                                resultsListState.firstVisibleItemIndex,
                                resultsListState.firstVisibleItemScrollOffset
                            )
                            viewModel.beginSearchNavigation(results.map { it.id }, lecture.id)
                            onOpenLecture(lecture.id)
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(lecture.title.ifBlank { "جلسه بدون عنوان" }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(
                                    lecture.professorName.takeIf { it.isNotBlank() }?.let { "استاد: $it" },
                                    lecture.courseName.takeIf { it.isNotBlank() }?.let { "درس: $it" },
                                    className?.let { "کلاس: $it" }
                                ).joinToString("  •  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (excerpt.text.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        "منبع: " + excerpt.sourceLabel,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(highlightSearchExcerpt(excerpt.text, terms), maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("برای باز کردن جلسه لمس کنید  ←", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

// Lightweight Persian/Islamic-studies query expansion. This improves recall
// locally without uploading private lecture text to an external service. It is a
// first semantic-search layer, not a substitute for embedding-based retrieval.
private val searchConceptGroups: List<Set<String>> = listOf(
    setOf("خبر واحد", "خبرواحد", "روایت واحد", "روایت منفرد", "خبر ظنی"),
    setOf("عقل عملی", "عقلانیت عملی", "حکم عملی"),
    setOf("حسن و قبح", "حسن وقبح", "خوبی و بدی", "تحسین و تقبیح"),
    setOf("اجتهاد", "استنباط", "فقاهت"),
    setOf("قطع", "یقین", "علم قطعی"),
    setOf("ظن", "گمان", "غیرقطعی"),
    setOf("متن اصلی", "متن خام", "متن اولیه"),
    setOf("خلاصه", "چکیده", "جمع‌بندی")
)

private fun semanticVariants(term: String): Set<String> {
    val normalized = normalizeSearchText(term)
    val variants = linkedSetOf(normalized)
    searchConceptGroups.forEach { group ->
        val normalizedGroup = group.map(::normalizeSearchText)
        if (normalizedGroup.any { it == normalized || it.contains(normalized) || normalized.contains(it) }) {
            variants.addAll(normalizedGroup)
        }
    }
    return variants
}

private fun normalizeSearchText(value: String): String =
    value.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک').replace('ۀ', 'ه').replace('ة', 'ه')
        .replace('ؤ', 'و').replace('إ', 'ا').replace('أ', 'ا').replace('آ', 'ا')
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("[\\u200C\\u200F\\u202A-\\u202E]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun normalizeSearchTextWithOffsets(source: String): Pair<String, List<Int>> {
    val normalized = StringBuilder()
    val offsets = mutableListOf<Int>()
    var pendingSpaceOffset: Int? = null

    source.forEachIndexed { index, original ->
        val char = when (original) {
            'ي' -> 'ی'
            'ك' -> 'ک'
            'ۀ', 'ة' -> 'ه'
            'ؤ' -> 'و'
            'إ', 'أ', 'آ' -> 'ا'
            else -> original.lowercaseChar()
        }
        if (char in '\u064B'..'\u065F' || char == '\u0670' ||
            char == '\u200F' || char in '\u202A'..'\u202E'
        ) return@forEachIndexed

        if (char == '\u200C' || char.isWhitespace()) {
            if (normalized.isNotEmpty() && pendingSpaceOffset == null) pendingSpaceOffset = index
            return@forEachIndexed
        }
        if (pendingSpaceOffset != null && normalized.isNotEmpty()) {
            normalized.append(' ')
            offsets += pendingSpaceOffset!!
        }
        pendingSpaceOffset = null
        normalized.append(char)
        offsets += index
    }
    return normalized.toString().trimEnd() to offsets
}

private data class SearchExcerpt(
    val sourceLabel: String,
    val text: String
)

private fun makeSearchExcerpt(
    lecture: LectureEntity,
    terms: List<String>,
    query: String,
    matchMode: String
): SearchExcerpt {
    val sources = listOf(
        "متن اصلی درس" to lecture.originalTranscript,
        "متن ویرایش‌شده" to lecture.transcript,
        "خلاصه هوشمند" to lecture.aiSummary.orEmpty(),
        "کلیدواژه‌ها" to lecture.aiKeyPoints.orEmpty(),
        "پرسش‌های هوشمند" to lecture.aiQuizJson.orEmpty(),
        "برچسب‌ها" to lecture.tags
    )
    val normalizedQuery = normalizeSearchText(query).trim()
    val normalizedTerms = terms.map(::normalizeSearchText).filter { it.isNotBlank() }.distinct()

    fun matchingTerm(candidate: String): String? {
        val normalizedCandidate = normalizeSearchText(candidate)
        return when {
            matchMode == "phrase" && normalizedQuery.isNotBlank() &&
                normalizedCandidate.contains(normalizedQuery) -> normalizedQuery
            else -> normalizedTerms.sortedByDescending { it.length }
                .firstOrNull { normalizedCandidate.contains(it) }
        }
    }

    val match = sources.firstOrNull { (_, candidate) ->
        candidate.isNotBlank() && matchingTerm(candidate) != null
    } ?: sources.firstOrNull { it.second.isNotBlank() }
    val label = match?.first.orEmpty()
    val source = match?.second.orEmpty()
    if (source.length <= 240) return SearchExcerpt(label, source)

    val (normalizedSource, offsets) = normalizeSearchTextWithOffsets(source)
    val matchedTerm = matchingTerm(source)
    val normalizedIndex = matchedTerm?.let { normalizedSource.indexOf(it) } ?: -1
    val rawIndex = if (normalizedIndex >= 0) offsets.getOrNull(normalizedIndex) ?: 0 else 0
    val start = (rawIndex - 70).coerceAtLeast(0)
    val end = minOf(source.length, start + 240)
    return SearchExcerpt(
        label,
        (if (start > 0) "… " else "") + source.substring(start, end) +
            if (end < source.length) " …" else ""
    )
}

private fun highlightSearchExcerpt(excerpt: String, terms: List<String>): AnnotatedString {
    val (normalizedExcerpt, offsets) = normalizeSearchTextWithOffsets(excerpt)
    val patterns = terms.map(::normalizeSearchText)
        .filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
    if (excerpt.isBlank() || patterns.isEmpty() || normalizedExcerpt.isBlank()) {
        return AnnotatedString(excerpt)
    }

    val normalizedRanges = mutableListOf<IntRange>()
    patterns.forEach { term ->
        var from = 0
        while (from < normalizedExcerpt.length) {
            val index = normalizedExcerpt.indexOf(term, startIndex = from)
            if (index < 0) break
            normalizedRanges += index until (index + term.length)
            from = index + term.length.coerceAtLeast(1)
        }
    }
    val sourceRanges = normalizedRanges.mapNotNull { range ->
        val start = offsets.getOrNull(range.first) ?: return@mapNotNull null
        val end = (offsets.getOrNull(range.last) ?: start) + 1
        if (start < end) start until end else null
    }.sortedBy { it.first }.fold(mutableListOf<IntRange>()) { acc, range ->
        val previous = acc.lastOrNull()
        if (previous != null && range.first <= previous.last + 1) {
            acc[acc.lastIndex] = previous.first..maxOf(previous.last, range.last)
        } else {
            acc += range
        }
        acc
    }

    return buildAnnotatedString {
        var cursor = 0
        sourceRanges.forEach { range ->
            if (range.first < cursor) return@forEach
            append(excerpt.substring(cursor, range.first))
            withStyle(
                SpanStyle(
                    background = Color(0xFFFFD54F),
                    color = Color(0xFF302400),
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(excerpt.substring(range.first, range.last + 1))
            }
            cursor = range.last + 1
        }
        if (cursor < excerpt.length) append(excerpt.substring(cursor))
    }
}
