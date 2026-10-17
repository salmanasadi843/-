package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.automirrored.filled.Logout
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.data.local.DatabaseBackup
import com.example.data.local.CloudMaterialsSync
import com.example.data.api.GeminiApiService
import com.example.data.api.GroqApiService
import com.example.data.api.SpeechmaticsApiService
import com.example.ui.screens.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val backupScope = rememberCoroutineScope()
    var backupBusy by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var backupError by remember { mutableStateOf(false) }
    var cloudBusy by remember { mutableStateOf(false) }
    var cloudStatus by remember { mutableStateOf<String?>(null) }
    var cloudError by remember { mutableStateOf(false) }
    var teacherField by remember { mutableStateOf("") }
    var teacherInstitution by remember { mutableStateOf("") }
    var teacherRequestBusy by remember { mutableStateOf(false) }
    var teacherRequestStatus by remember { mutableStateOf<String?>(null) }
    var teacherRequestError by remember { mutableStateOf(false) }
    var showPublishConfirm by remember { mutableStateOf(false) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) backupScope.launch {
            backupBusy = true; backupStatus = null
            val result = withContext(Dispatchers.IO) { runCatching { DatabaseBackup.export(context, uri) } }
            backupBusy = false
            backupError = result.isFailure
            backupStatus = if (result.isSuccess) "پشتیبان با موفقیت ذخیره شد. فایل را در فضای امن یا فضای ابری نگه دارید." else "ساخت پشتیبان ناموفق بود: ${result.exceptionOrNull()?.localizedMessage}"
        }
    }
    val runCloudSync = {
        backupScope.launch {
            cloudBusy = true
            cloudStatus = null
            val result = withContext(Dispatchers.IO) { runCatching { CloudMaterialsSync.sync(context) } }
            cloudBusy = false
            cloudError = result.isFailure
            cloudStatus = result.getOrElse { "همگام‌سازی آنلاین ناموفق بود: ${it.localizedMessage}" }
        }
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) backupScope.launch {
            backupBusy = true; backupStatus = null
            val result = withContext(Dispatchers.IO) { runCatching { DatabaseBackup.import(context, uri) } }
            backupBusy = false
            backupError = result.isFailure
            backupStatus = result.getOrElse { "بازیابی ناموفق بود: ${it.localizedMessage}" }
        }
    }

    var groqKey by remember { mutableStateOf(GroqApiService.getSavedApiKey(context)) }
    var speechmaticsKey by remember { mutableStateOf(SpeechmaticsApiService.getSavedApiKey(context)) }

    var apiKey by remember {
        mutableStateOf(
            GeminiApiService.getSavedApiKey(context)
        )
    }

    var showKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    if (showPublishConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPublishConfirm = false },
            title = { Text("انتشار آنلاین مطالب") },
            text = { Text("متن، خلاصه، کلیدواژه‌ها، اطلاعات کلاس و لینک صوت همه جلسات این حساب برای کاربران واردشده به درس‌یار قابل مشاهده خواهد شد. فایل صوتی اصلی ارسال نمی‌شود. آیا ادامه می‌دهید؟") },
            confirmButton = {
                Button(onClick = {
                    showPublishConfirm = false
                    runCloudSync()
                }) { Text("انتشار و ادامه") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPublishConfirm = false }) { Text("انصراف") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تنظیمات") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
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
            if (AuthPreferences.isPrimaryTeacher(context)) {\n                AdminTeacherManagementCard()\n            }\n\n            val cloudSyncStatus by viewModel.cloudSyncStatus.collectAsState()
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.NetworkCheck, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("وضعیت پشتیبان‌گیری ابری", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(cloudSyncStatus, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { viewModel.startCloudSync() },
                        enabled = !cloudBusy
                    ) {
                        Text("بررسی و تلاش دوباره")
                    }
                    Text(
                        "موفقیت این بخش یعنی برنامه توانسته عملیات ابری را انجام دهد؛ برای تأیید نهایی ذخیره داده‌ها می‌توان بعداً فایربیس را هم بررسی کرد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (AuthPreferences.currentRole(context) == UserRole.STUDENT && !AuthPreferences.isPrimaryTeacher(context)) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("درخواست تأیید به‌عنوان استاد", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "اگر مدرس هستید، مشخصات کوتاه خود را ارسال کنید. درخواست برای بررسی مدیر ثبت می‌شود؛ ارسال درخواست به‌تنهایی نقش استاد را فعال نمی‌کند.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = teacherField,
                            onValueChange = { teacherField = it; teacherRequestStatus = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("رشته یا حوزه تدریس") },
                            placeholder = { Text("مثلاً اصول فقه، زبان انگلیسی") },
                            singleLine = true,
                            enabled = !teacherRequestBusy
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = teacherInstitution,
                            onValueChange = { teacherInstitution = it; teacherRequestStatus = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("نام مؤسسه یا محل تدریس (اختیاری)") },
                            singleLine = true,
                            enabled = !teacherRequestBusy
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val user = FirebaseAuth.getInstance().currentUser
                                if (user == null) {
                                    teacherRequestError = true
                                    teacherRequestStatus = "برای ارسال درخواست ابتدا وارد حساب شوید."
                                } else if (teacherField.isBlank()) {
                                    teacherRequestError = true
                                    teacherRequestStatus = "حوزه تدریس را وارد کنید."
                                } else {
                                    teacherRequestBusy = true
                                    teacherRequestStatus = null
                                    val request = hashMapOf<String, Any>(
                                        "uid" to user.uid,
                                        "email" to (user.email ?: ""),
                                        "name" to AuthPreferences.currentName(context),
                                        "teachingField" to teacherField.trim(),
                                        "institution" to teacherInstitution.trim(),
                                        "status" to "PENDING",
                                        "createdAt" to FieldValue.serverTimestamp(),
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    )
                                    FirebaseFirestore.getInstance()
                                        .collection("teacher_requests")
                                        .document(user.uid)
                                        .set(request)
                                        .addOnCompleteListener { task ->
                                            teacherRequestBusy = false
                                            teacherRequestError = !task.isSuccessful
                                            teacherRequestStatus = if (task.isSuccessful) {
                                                "درخواست ثبت شد و در انتظار بررسی مدیر است."
                                            } else {
                                                "ثبت درخواست ناموفق بود: ${task.exception?.localizedMessage ?: "خطای نامشخص"}"
                                            }
                                        }
                                }
                            },
                            enabled = !teacherRequestBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (teacherRequestBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("در حال ارسال...")
                            } else {
                                Text("ارسال درخواست تأیید")
                            }
                        }
                        if (teacherRequestStatus != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                teacherRequestStatus!!,
                                color = if (teacherRequestError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

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
                    Text("Groq با مدل Whisper برای تبدیل صوت به متن استفاده می‌شود و در صورت ناموفق بودن Gemini، جایگزین آن خواهد شد.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = groqKey, onValueChange = { groqKey = it; status = null }, modifier = Modifier.fillMaxWidth(), label = { Text("Groq API Key") }, placeholder = { Text("کلید Groq را وارد کنید") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) })
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { GroqApiService.saveApiKey(context, groqKey); status = "کلید Groq با موفقیت ذخیره شد."; statusIsError = false }, enabled = groqKey.isNotBlank()) { Text("ذخیره Groq") }
                        OutlinedButton(onClick = { GroqApiService.deleteSavedApiKey(context); groqKey = ""; status = "کلید Groq حذف شد."; statusIsError = false }) { Text("حذف") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (groqKey.isBlank()) {
                                status = "ابتدا کلید Groq را وارد کنید."
                                statusIsError = true
                                return@OutlinedButton
                            }
                            GroqApiService.saveApiKey(context, groqKey)
                            isTesting = true
                            status = null
                            viewModel.testGroqConnection {
                                isTesting = false
                                if (it == null) {
                                    status = "✓ اتصال Groq برقرار است؛ اصلاح متن با Groq انجام می‌شود."
                                    statusIsError = false
                                } else {
                                    status = it
                                    statusIsError = true
                                }
                            }
                        },
                        enabled = !isTesting
                    ) {
                        if (isTesting) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.NetworkCheck, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("تست اتصال Groq")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("اصلاح متن: ابتدا Gemini ← در صورت خطا → Groq", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Speechmatics — تبدیل صوت به متن", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Speechmatics جایگزین نهایی تبدیل صوت به متن است؛ ابتدا Gemini و سپس Groq امتحان می‌شوند.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = speechmaticsKey,
                        onValueChange = { speechmaticsKey = it; status = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Speechmatics API Key") },
                        placeholder = { Text("کلید Speechmatics را وارد کنید") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                SpeechmaticsApiService.saveApiKey(context, speechmaticsKey)
                                status = "کلید Speechmatics با موفقیت ذخیره شد."
                                statusIsError = false
                            },
                            enabled = speechmaticsKey.isNotBlank()
                        ) { Text("ذخیره Speechmatics") }
                        OutlinedButton(
                            onClick = {
                                SpeechmaticsApiService.deleteSavedApiKey(context)
                                speechmaticsKey = ""
                                status = "کلید Speechmatics حذف شد."
                                statusIsError = false
                            }
                        ) { Text("حذف") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "اولویت تبدیل صوت: ۱) Gemini  ۲) Groq  ۳) Speechmatics",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("دسترسی آنلاین مطالب", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (AuthPreferences.currentRole(context) == UserRole.TEACHER)
                            "با همگام‌سازی، متن جزوه، خلاصه، کلیدواژه، مشخصات کلاس و لینک صوت جلسات این حساب برای کاربران واردشده به درس‌یار قابل دریافت می‌شود. فایل صوتی اصلی بارگذاری نمی‌شود."
                        else
                            "با به‌روزرسانی مطالب آنلاین، جزوه‌ها، خلاصه‌ها، کلیدواژه‌ها و لینک‌های صوت منتشرشده توسط استادها روی این دستگاه دریافت می‌شود. برای این کار باید اینترنت متصل باشد.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (AuthPreferences.currentRole(context) == UserRole.TEACHER) showPublishConfirm = true
                            else runCloudSync()
                        },
                        enabled = !cloudBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (cloudBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.NetworkCheck, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (cloudBusy) "در حال همگام‌سازی..." else if (AuthPreferences.currentRole(context) == UserRole.TEACHER) "انتشار و همگام‌سازی مطالب استاد" else "دریافت مطالب آنلاین")
                    }
                    if (cloudStatus != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            cloudStatus!!,
                            color = if (cloudError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "نکته: این گزینه با فایل پشتیبان فرق دارد؛ همگام‌سازی آنلاین به اتصال و تنظیم صحیح دسترسی‌های Firebase نیاز دارد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("پشتیبان‌گیری و بازیابی", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "برای جلوگیری از پاک شدن جزوه‌ها با حذف برنامه، یک فایل پشتیبان بسازید و آن را در فضای ابری یا حافظه‌ای امن نگه دارید. بازیابی، اطلاعات فعلی را با اطلاعات فایل پشتیبان جایگزین می‌کند.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { createBackup.launch("darsyar-backup.json") },
                        enabled = !backupBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("ساخت فایل پشتیبان")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { restoreBackup.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                        enabled = !backupBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("بازیابی از فایل پشتیبان")
                    }
                    if (backupBusy) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("در حال پردازش فایل...")
                        }
                    }
                    if (backupStatus != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            backupStatus!!,
                            color = if (backupError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
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
                    Text("حساب کاربری", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "برای تغییر حساب یا ورود با نقش کاربری دیگر، ابتدا از حساب فعلی خارج شوید.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onLogout,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("خروج از حساب")
                    }
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

