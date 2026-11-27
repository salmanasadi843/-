package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.api.BackendClassDto
import com.example.data.api.BackendSessionDto
import com.example.data.api.BackendSessionSummaryDto
import com.example.data.api.DarsyarReadOnlyApiFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Anonymous, read-only access to published public classes and sessions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicCatalogScreen(onBack: () -> Unit, onLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    var classes by remember { mutableStateOf<List<BackendClassDto>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<BackendSessionSummaryDto>>(emptyList()) }
    var selectedClass by remember { mutableStateOf<BackendClassDto?>(null) }
    var selectedSession by remember { mutableStateOf<BackendSessionDto?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadClasses() {
        if (!com.example.data.api.BackendEndpointConfig.isConfigured) {
            error = "فهرست عمومی هنوز به نشانی سرور متصل نشده است."
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                classes = withContext(Dispatchers.IO) { DarsyarReadOnlyApiFactory.create().loadPublicClasses() }
                selectedClass = null
                sessions = emptyList()
                selectedSession = null
            } catch (e: Exception) {
                error = e.localizedMessage ?: "دریافت کلاس‌های عمومی انجام نشد."
            } finally { loading = false }
        }
    }

    LaunchedEffect(Unit) { loadClasses() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedSession?.title ?: selectedClass?.title ?: "کلاس‌های عمومی درس‌یار") },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            selectedSession != null -> selectedSession = null
                            selectedClass != null -> { selectedClass = null; sessions = emptyList() }
                            else -> onBack()
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت") }
                },
                actions = { TextButton(onClick = onLogin) { Text("ورود") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val detail = selectedSession
            if (detail != null) {
                Text(detail.professorName, style = MaterialTheme.typography.labelLarge)
                Text(detail.title, style = MaterialTheme.typography.headlineSmall)
                if (detail.aiSummary.isNullOrBlank().not()) {
                    Text("خلاصه", style = MaterialTheme.typography.titleMedium)
                    Text(detail.aiSummary.orEmpty())
                }
                if (detail.aiKeyPoints.isNullOrBlank().not()) {
                    Text("نکات کلیدی", style = MaterialTheme.typography.titleMedium)
                    Text(detail.aiKeyPoints.orEmpty())
                }
                Text("متن جلسه", style = MaterialTheme.typography.titleMedium)
                Text(
                    "می‌توانید متن عمومی را انتخاب و کپی کنید؛ انتخاب متن فقط موقت است و در حساب ذخیره نمی‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainer {
                    Text(detail.transcript.ifBlank { "متنی برای این جلسه منتشر نشده است." })
                }
                Text(
                    "برای پرسیدن سؤال از هوش مصنوعی، ذخیره هایلایت، یادداشت و نشانک، ابتدا وارد حساب کاربری شوید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            } else if (selectedClass != null) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(selectedClass!!.description, style = MaterialTheme.typography.bodyMedium)
                        Text("جلسات منتشرشده", style = MaterialTheme.typography.titleMedium)
                    }
                    items(sessions, key = { it.id }) { session ->
                        Card(
                            Modifier.fillMaxWidth().clickable {
                                loading = true
                                error = null
                                scope.launch {
                                    try {
                                        selectedSession = withContext(Dispatchers.IO) {
                                            DarsyarReadOnlyApiFactory.create().loadPublicSessionDetail(selectedClass!!.id, session.id)
                                        }
                                    } catch (e: Exception) {
                                        error = e.localizedMessage ?: "دریافت جلسه انجام نشد."
                                    } finally { loading = false }
                                }
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(session.title, style = MaterialTheme.typography.titleMedium)
                                Text(session.professorName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (sessions.isEmpty() && !loading) item { Text("جلسه عمومی منتشرشده‌ای در این کلاس وجود ندارد.") }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(classes, key = { it.id }) { clazz ->
                        Card(
                            Modifier.fillMaxWidth().clickable {
                                selectedClass = clazz
                                loading = true
                                error = null
                                scope.launch {
                                    try {
                                        sessions = withContext(Dispatchers.IO) {
                                            DarsyarReadOnlyApiFactory.create().loadPublicSessions(clazz.id)
                                        }
                                    } catch (e: Exception) {
                                        error = e.localizedMessage ?: "دریافت جلسات انجام نشد."
                                    } finally { loading = false }
                                }
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(clazz.title, style = MaterialTheme.typography.titleMedium)
                                Text(clazz.description, style = MaterialTheme.typography.bodySmall)
                                Text("مشاهده جلسات", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    if (classes.isEmpty() && !loading) item { Text("در حال حاضر کلاس عمومی منتشرشده‌ای وجود ندارد.") }
                }
            }
        }
    }
}
