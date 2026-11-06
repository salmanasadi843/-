package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.api.DarsyarCommunityApiFactory
import com.example.data.api.BackendHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets an authenticated student redeem a teacher-issued private-class code. */
@Composable
fun BackendPrivateClassJoinPanel(onJoined: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("عضویت در کلاس خصوصی", style = MaterialTheme.typography.titleMedium)
            Text(
                "کد ورود را که از استاد دریافت کرده‌اید وارد کنید. عضویت پس از تأیید سرور فعال می‌شود.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.take(64) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("کد ورود کلاس") },
                singleLine = true,
                enabled = !busy
            )
            Button(
                onClick = {
                    if (code.isBlank() || busy) return@Button
                    busy = true
                    message = null
                    AuthPreferences.getFirebaseIdToken(context) { tokenResult ->
                        tokenResult.fold(
                            onSuccess = { token ->
                                scope.launch {
                                    try {
                                        val membership = withContext(Dispatchers.IO) {
                                            DarsyarCommunityApiFactory.create().redeemAccessCode(token, code)
                                        }
                                        code = ""
                                        isError = false
                                        message = if (membership.alreadyActive) {
                                            "عضویت شما از قبل فعال بوده است."
                                        } else {
                                            "عضویت با موفقیت فعال شد؛ فهرست کلاس‌ها به‌روزرسانی می‌شود."
                                        }
                                        onJoined(membership.classId)
                                    } catch (e: Exception) {
                                        isError = true
                                        message = when ((e as? BackendHttpException)?.statusCode) {
                                            400 -> "کد ورود نامعتبر، منقضی یا غیرفعال است."
                                            403 -> "این عملیات فقط برای حساب دانشجویی مجاز است."
                                            else -> e.localizedMessage ?: "ثبت عضویت انجام نشد."
                                        }
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            onFailure = {
                                busy = false
                                isError = true
                                message = it.localizedMessage ?: "دریافت توکن ورود ناموفق بود."
                            }
                        )
                    }
                },
                enabled = !busy && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("ثبت کد و عضویت") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let {
                Text(
                    it,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
