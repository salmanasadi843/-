package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

    val results = remember(searchableLectures, query, matchMode, selectedProfessor, selectedCourse, selectedClassId) {
        searchableLectures.filter { lecture ->
            val content = normalizeSearchText(
                listOf(
                    lecture.title, lecture.courseName, lecture.professorName, lecture.tags,
                    lecture.transcript, lecture.originalTranscript,
                    lecture.aiSummary.orEmpty(), lecture.aiKeyPoints.orEmpty()
                ).joinToString(" ")
            )
            val textMatches = when {
                terms.isEmpty() -> true
                matchMode == "phrase" -> content.contains(normalizeSearchText(query).trim())
                else -> terms.all { content.contains(it) }
            }
            textMatches &&
                (selectedProfessor == "همه استادان" || lecture.professorName.trim() == selectedProfessor) &&
                (selectedCourse == "همه درس‌ها" || lecture.courseName.trim() == selectedCourse) &&
                (selectedClassId == null || lecture.classId == selectedClassId)
        }.sortedByDescending { it.dateMillis }
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
                onValueChange = { query = it; viewModel.advancedSearchQuery.value = it },
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
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("همه واژه‌ها") }
                SegmentedButton(
                    selected = matchMode == "phrase",
                    onClick = { matchMode = "phrase"; viewModel.advancedSearchMatchMode.value = "phrase" },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("عبارت دقیق") }
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
                Text("عبارت موردنظر را وارد کنید. جستجو در عنوان، استاد و درس، برچسب‌ها، متن اصلی، متن ویرایش‌شده، خلاصه و کلیدواژه‌ها انجام می‌شود.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    val excerpt = makeSearchExcerpt(lecture, terms)
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

private fun normalizeSearchText(value: String): String =
    value.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک').replace('ۀ', 'ه').replace('ة', 'ه')
        .replace('ؤ', 'و').replace('إ', 'ا').replace('أ', 'ا').replace('آ', 'ا')
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("[\\u200C\\u200F\\u202A-\\u202E]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private data class SearchExcerpt(
    val sourceLabel: String,
    val text: String
)

private fun makeSearchExcerpt(lecture: LectureEntity, terms: List<String>): SearchExcerpt {
    // Keep this order aligned with the requested source labels and show the first
    // field that actually contains a matching term, not merely the first non-empty field.
    val sources = listOf(
        "متن اصلی درس" to lecture.originalTranscript,
        "متن ویرایش‌شده" to lecture.transcript,
        "خلاصه هوشمند" to lecture.aiSummary.orEmpty(),
        "کلیدواژه‌ها" to lecture.aiKeyPoints.orEmpty(),
        "پرسش‌های هوشمند" to lecture.aiQuizJson.orEmpty(),
        "برچسب‌ها" to lecture.tags
    )
    val match = sources.firstOrNull { (label, candidate) ->
        candidate.isNotBlank() &&
            (terms.isEmpty() || terms.any { normalizeSearchText(candidate).contains(it) })
    } ?: sources.firstOrNull { it.second.isNotBlank() }
    val label = match?.first.orEmpty()
    val source = match?.second.orEmpty()
    if (source.length <= 240) return SearchExcerpt(label, source)
    val normalizedSource = normalizeSearchText(source)
    val index = terms.firstOrNull { it.isNotBlank() }?.let { normalizedSource.indexOf(it) } ?: -1
    val start = if (index > 70) index - 70 else 0
    return SearchExcerpt(
        label,
        (if (start > 0) "… " else "") + source.substring(start, minOf(source.length, start + 240)) +
            if (start + 240 < source.length) " …" else ""
    )
}

private fun highlightSearchExcerpt(excerpt: String, terms: List<String>): AnnotatedString = buildAnnotatedString {
    val patterns = terms.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
    if (excerpt.isBlank() || patterns.isEmpty()) {
        append(excerpt)
        return@buildAnnotatedString
    }
    val regex = runCatching {
        Regex(patterns.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    }.getOrNull()
    if (regex == null) {
        append(excerpt)
        return@buildAnnotatedString
    }
    var cursor = 0
    regex.findAll(excerpt).forEach { match ->
        if (match.range.first < cursor) return@forEach
        append(excerpt.substring(cursor, match.range.first))
        withStyle(
            SpanStyle(
                background = Color(0xFFFFD54F),
                color = Color(0xFF302400),
                fontWeight = FontWeight.Bold
            )
        ) {
            append(match.value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < excerpt.length) append(excerpt.substring(cursor))
}
