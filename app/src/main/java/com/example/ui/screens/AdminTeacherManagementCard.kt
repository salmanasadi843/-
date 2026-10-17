package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon

@Composable
fun AdminTeacherManagementCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var requests by remember { mutableStateOf<List<TeacherRequestRecord>>(emptyList()) }
    var teachers by remember { mutableStateOf<List<TeacherAccountRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var actionUid by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var teacherToRevoke by remember { mutableStateOf<TeacherAccountRecord?>(null) }

    fun refresh() {
        loading = true
        statusMessage = null
        AuthPreferences.loadTeacherRequests(context) { result ->
            result.onSuccess { requests = it }
                .onFailure {
                    statusMessage = "دریافت درخواست‌ها ناموفق بود: ${it.localizedMessage ?: "خطای نامشخص"}"
                    statusError = true
                }
            AuthPreferences.loadTeacherAccounts(context) { accountsResult ->
                accountsResult.onSuccess { teachers = it }
                    .onFailure {
                        statusMessage = "دریافت فهرست استادان ناموفق بود: ${it.localizedMessage ?: "خطای نامشخص"}"
                        statusError = true
                    }
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (teacherToRevoke != null) {
        val teacher = teacherToRevoke!!
        AlertDialog(
            onDismissRequest = { teacherToRevoke = null },
            title = { Text("لغو مجوز استاد") },
            text = {
                Text(
                    "مجوز استاد «${teacher.teacherName}» لغو و نقش حساب او به دانشجو تغییر می‌کند. " +
                        "این اقدام به‌تنهایی جایگزین اعمال قوانین دسترسی انتشار در Firebase نیست."
                )
            },
            confirmButton = {
                Button(
                    enabled = actionUid == null,
                    onClick = {
                        actionUid = teacher.uid
                        teacherToRevoke = null
                        AuthPreferences.setTeacherRole(
                            context = context,
                            uid = teacher.uid,
                            teacherName = teacher.teacherName,
                            email = teacher.email,
                            enabled = false
                        ) { result ->
                            actionUid = null
                            statusError = result.isFailure
                            statusMessage = result.fold(
                                onSuccess = { "نقش استاد لغو شد. برای اعمال کامل محدودیت روی مطالب منتشرشده، قوانین امن Firebase نیز باید منتشر و آزمایش شوند." },
                                onFailure = { "لغو مجوز انجام نشد: ${it.localizedMessage ?: "خطای نامشخص"}" }
                            )
                            refresh()
                        }
                    }
                ) { Text("تأیید لغو") }
            },
            dismissButton = {
                TextButton(onClick = { teacherToRevoke = null }) { Text("انصراف") }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("مدیریت استادان", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "این بخش فقط برای حساب اصلی مدیر نمایش داده می‌شود. درخواست‌ها را بررسی کنید و مجوز حساب استادان را مدیریت کنید.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { refresh() },
                enabled = !loading && actionUid == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                else Text("به‌روزرسانی فهرست")
            }

            if (requests.isEmpty() && !loading) {
                Text("درخواست استادی ثبت‌شده‌ای پیدا نشد.", style = MaterialTheme.typography.bodySmall)
            }

            requests.forEach { request ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(request.teacherName.ifBlank { "نام استاد ثبت نشده" }, style = MaterialTheme.typography.titleMedium)
                        Text("درس درخواستی: ${request.teachingField.ifBlank { "ثبت نشده" }}")
                        if (request.institutionName.isNotBlank()) Text("مؤسسه: ${request.institutionName}")
                        Text("ایمیل: ${request.email.ifBlank { "ثبت نشده" }}")
                        Text(
                            "وضعیت: " + when (request.status) {
                                "PENDING" -> "در انتظار بررسی"
                                "APPROVED" -> "تأیید شده"
                                "REJECTED" -> "رد شده"
                                "REVOKED" -> "مجوز لغو شده"
                                else -> request.status
                            },
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (request.status == "PENDING") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = actionUid == null,
                                    onClick = {
                                        actionUid = request.uid
                                        AuthPreferences.reviewTeacherRequest(
                                            context = context,
                                            uid = request.uid,
                                            teacherName = request.teacherName,
                                            email = request.email,
                                            approve = true
                                        ) { result ->
                                            actionUid = null
                                            statusError = result.isFailure
                                            statusMessage = result.fold(
                                                onSuccess = { "درخواست ${request.teacherName} تأیید شد." },
                                                onFailure = { "تأیید درخواست انجام نشد: ${it.localizedMessage ?: "خطای نامشخص"}" }
                                            )
                                            refresh()
                                        }
                                    }
                                ) { Text("تأیید استاد") }
                                OutlinedButton(
                                    enabled = actionUid == null,
                                    onClick = {
                                        actionUid = request.uid
                                        AuthPreferences.reviewTeacherRequest(
                                            context = context,
                                            uid = request.uid,
                                            teacherName = request.teacherName,
                                            email = request.email,
                                            approve = false
                                        ) { result ->
                                            actionUid = null
                                            statusError = result.isFailure
                                            statusMessage = result.fold(
                                                onSuccess = { "درخواست ${request.teacherName} رد شد." },
                                                onFailure = { "رد درخواست انجام نشد: ${it.localizedMessage ?: "خطای نامشخص"}" }
                                            )
                                            refresh()
                                        }
                                    }
                                ) { Text("رد درخواست") }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("مدیریت نقش حساب‌ها", style = MaterialTheme.typography.titleMedium)
            val otherTeachers = teachers.filter { it.uid != AuthPreferences.currentUid(context) }
            if (otherTeachers.isEmpty() && !loading) {
                Text("حساب استاد دیگری ثبت نشده است.", style = MaterialTheme.typography.bodySmall)
            }
            otherTeachers.forEach { teacher ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(teacher.teacherName, style = MaterialTheme.typography.bodyMedium)
                        Text(teacher.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (teacher.role == UserRole.TEACHER.name) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(
                                enabled = actionUid == null,
                                onClick = {
                                    actionUid = teacher.uid
                                    AuthPreferences.setTeacherRole(context, teacher.uid, teacher.teacherName, teacher.email, true) { result ->
                                        actionUid = null
                                        statusError = result.isFailure
                                        statusMessage = result.fold(
                                            onSuccess = { "مجوز استادی برای ${teacher.teacherName} دوباره فعال شد." },
                                            onFailure = { "فعال‌سازی مجدد انجام نشد: ${it.localizedMessage ?: "خطای نامشخص"}" }
                                        )
                                        refresh()
                                    }
                                }
                            ) { Text("فعال‌سازی مجدد") }
                            OutlinedButton(
                                enabled = actionUid == null,
                                onClick = { teacherToRevoke = teacher }
                            ) { Text("لغو مجوز") }
                        }
                    } else {
                        OutlinedButton(
                            enabled = actionUid == null,
                            onClick = {
                                actionUid = teacher.uid
                                AuthPreferences.setTeacherRole(context, teacher.uid, teacher.teacherName, teacher.email, true) { result ->
                                    actionUid = null
                                    statusError = result.isFailure
                                    statusMessage = result.fold(
                                        onSuccess = { "مجوز استادی برای ${teacher.teacherName} فعال شد." },
                                        onFailure = { "اعطای مجوز انجام نشد: ${it.localizedMessage ?: "خطای نامشخص"}" }
                                    )
                                    refresh()
                                }
                            }
                        ) { Text("تأیید استاد") }
                    }
                }
            }
            if (statusMessage != null) {
                Text(
                    statusMessage!!,
                    color = if (statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
