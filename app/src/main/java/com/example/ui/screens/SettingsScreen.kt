package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.api.GeminiApiService
import com.example.data.api.GroqApiService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    var groqKey by remember { mutableStateOf(GroqApiService.getSavedApiKey(context)) }

    var apiKey by remember {
        mutableStateOf(
            GeminiApiService.getSavedApiKey(context)
        )
    }

    var showKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تنظیمات") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "بازگشت"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "هوش مصنوعی",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                "تنظیم سرویس‌های هوش مصنوعی استادیار",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Google Gemini",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "کلید API خودتان را وارد کنید. کلید به‌صورت رمزنگاری‌شده روی همین دستگاه ذخیره می‌شود.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            status = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("کلید را اینجا وارد کنید") },
                        singleLine = true,
                        visualTransformation =
                            if (showKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { showKey = !showKey }
                            ) {
                                Icon(
                                    if (showKey)
                                        Icons.Default.VisibilityOff
                                    else
                                        Icons.Default.Visibility,
                                    contentDescription =
                                        if (showKey) "مخفی کردن" else "نمایش کلید"
                                )
                            }
                        }
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                GeminiApiService.saveApiKey(context, apiKey)
                                status = "کلید با موفقیت ذخیره شد."
                                statusIsError = false
                            },
                            enabled = apiKey.isNotBlank()
                        ) {
                            Text("ذخیره کلید")
                        }

                        OutlinedButton(
                            onClick = {
                                GeminiApiService.deleteSavedApiKey(context)
                                apiKey = ""
                                status = "کلید حذف شد."
                                statusIsError = false
                            }
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("حذف")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            if (apiKey.isBlank()) {
                                status = "ابتدا کلید Gemini را وارد کنید."
                                statusIsError = true
                                return@OutlinedButton
                            }

                            GeminiApiService.saveApiKey(context, apiKey)
                            isTesting = true
                            status = null

                            viewModel.testGeminiConnection {
                                isTesting = false

                                if (it == null) {
                                    status = "✓ اتصال Gemini برقرار است."
                                    statusIsError = false
                                } else {
                                    status = it
                                    statusIsError = true
                                }
                            }
                        },
                        enabled = !isTesting && apiKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("در حال آزمایش اتصال...")
                        } else {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("آزمایش اتصال")
                        }
                    }

                    if (status != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            status!!,
                            color =
                                if (statusIsError)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("GROQ — تبدیل صوت به متن", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("Groq با مدل Whisper برای تبدیل سریع فایل صوتی به متن فارسی استفاده می‌شود و در صورت خطای Gemini می‌تواند موتور دوم باشد.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = groqKey, onValueChange = { groqKey = it; status = null }, modifier = Modifier.fillMaxWidth(), label = { Text("Groq API Key") }, placeholder = { Text("کلید Groq را وارد کنید") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) })
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { GroqApiService.saveApiKey(context, groqKey); status = "کلید Groq با موفقیت ذخیره شد."; statusIsError = false }, enabled = groqKey.isNotBlank()) { Text("ذخیره Groq") }
                        OutlinedButton(onClick = { GroqApiService.deleteSavedApiKey(context); groqKey = ""; status = "کلید Groq حذف شد."; statusIsError = false }) { Text("حذف") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("اولویت تبدیل صوت: Gemini ← در صورت خطا → Groq", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "کلید واردشده داخل APK ثابت قرار نمی‌گیرد و برای هر نصب می‌تواند جداگانه تنظیم شود.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
