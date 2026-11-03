package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.api.BackendTeacherApplicationDto
import com.example.data.api.BackendTeacherApplicationWriteRequest
import com.example.data.api.DarsyarReadOnlyApiFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicitly backend-backed teacher application panel.
 * It never reads or writes the legacy Firestore teacher_requests collection.
 */
@androidx.compose.runtime.Composable
fun BackendTeacherApplicationsPanel(
    accountRoles: List<String>,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSuperAdmin = "super_admin" in accountRoles
    var teachingField by remember { mutableStateOf("") }
    var institutionName by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<BackendTeacherApplicationDto>>(emptyList()) }
    var currentApplication by remember { mutableStateOf<BackendTeacherApplicationDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    fun withToken(action: suspend (String) -> Unit) {
        if (busy) return
        busy = true
        AuthPreferences.getFirebaseIdToken(context) { result ->
            result.fold(
                onSuccess = { token ->
                    scope.launch {
                        try {
                            action(token)
                        } catch (error: Exception) {
                            onStatus(error.localizedMessage ?: "عملیات درخواست استاد ناموفق بود.")
                        } finally {
                            busy = false
                        }
                    }
                },
                onFailure = { error ->
                    busy = false
                    onStatus(error.localizedMessage ?: "دریافت توکن ورود ناموفق بود.")
                }
            )
        }
    }

    fun refreshPending() {
        if (!isSuperAdmin || busy) return
        withToken { token ->
            pending = withContext(Dispatchers.IO) {
                DarsyarReadOnlyApiFactory.create().loadPendingTeacherApplications(token)
            }
            loaded = true
            onStatus("درخواست‌های استاد از Backend دریافت شد.")
        }
    }

    LaunchedEffect(isSuperAdmin) {
        if (isSuperAdmin && !loaded) refreshPending()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("درخواست همکاری به‌عنوان استاد", style = MaterialTheme.typography.titleMedium)
            Text(
                "این بخش به سامانه جدید متصل است و درخواست‌های آن از صف قدیمی Firebase جدا هستند.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if ("teacher" in accountRoles) {
                Text(
                    "حساب شما در حال حاضر دسترسی استاد دارد؛ نیازی به ثبت درخواست جدید نیست.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                OutlinedTextField(
                    value = teachingField,
                    onValueChange = { teachingField = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("حوزه تدریس") },
                    enabled = !busy
                )
                OutlinedTextField(
                    value = institutionName,
                    onValueChange = { institutionName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("نام مؤسسه یا مرکز آموزشی (اختیاری)") },
                    enabled = !busy
                )
                Button(
                    onClick = {
                        withToken { token ->
                            val result = withContext(Dispatchers.IO) {
                                DarsyarReadOnlyApiFactory.create().submitTeacherApplication(
                                    token,
                                    BackendTeacherApplicationWriteRequest(
                                        teachingField = teachingField.trim().ifBlank { null },
                                        institutionName = institutionName.trim().ifBlank { null }
                                    )
                                )
                            }
                            currentApplication = result
                            onStatus("درخواست شما در سامانه جدید ثبت شد؛ وضعیت: ${result.approvalStatus}.")
                        }
                    },
                    enabled = !busy && (teachingField.isNotBlank() || institutionName.isNotBlank())
                ) {
                    if (busy) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    else Text("ثبت درخواست استاد")
                }
                currentApplication?.let {
                    Text("آخرین وضعیت درخواست: ${it.approvalStatus}", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (isSuperAdmin) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("درخواست‌های در انتظار بررسی", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { refreshPending() }, enabled = !busy) { Text("تازه‌سازی") }
                }
                if (loaded && pending.isEmpty()) {
                    Text("درخواستی در انتظار بررسی نیست.", style = MaterialTheme.typography.bodySmall)
                }
                pending.forEach { application ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(application.displayName.ifBlank { "کاربر بدون نام" }, style = MaterialTheme.typography.titleSmall)
                            application.email?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            application.teachingField?.takeIf { it.isNotBlank() }?.let { Text("حوزه: $it") }
                            application.institutionName?.takeIf { it.isNotBlank() }?.let { Text("مرکز: $it") }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(
                                    onClick = {
                                        withToken { token ->
                                            withContext(Dispatchers.IO) {
                                                DarsyarReadOnlyApiFactory.create().decideTeacherApplication(
                                                    token, application.userId, "rejected"
                                                )
                                            }
                                            pending = pending.filterNot { it.userId == application.userId }
                                            onStatus("درخواست ${application.displayName} رد شد.")
                                        }
                                    },
                                    enabled = !busy
                                ) { Text("رد") }
                                TextButton(
                                    onClick = {
                                        withToken { token ->
                                            withContext(Dispatchers.IO) {
                                                DarsyarReadOnlyApiFactory.create().decideTeacherApplication(
                                                    token, application.userId, "approved"
                                                )
                                            }
                                            pending = pending.filterNot { it.userId == application.userId }
                                            onStatus("درخواست ${application.displayName} تأیید شد.")
                                        }
                                    },
                                    enabled = !busy
                                ) { Text("تأیید") }
                            }
                        }
                    }
                }
            }
        }
    }
}
