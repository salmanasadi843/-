package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch

private data class AdminTeacherRow(val uid: String, val name: String, val email: String)
private data class AdminRecordRow(
    val id: Long,
    val type: String,
    val title: String,
    val classId: Long?,
    val courseId: Long?,
    val published: Boolean
)
private data class AdminRecordDetails(val row: AdminRecordRow, val values: Map<String, Any?>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminManagementScreen(onBack: () -> Unit) {
    val functions = remember { FirebaseFunctions.getInstance("europe-west1") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var teachers by remember { mutableStateOf<List<AdminTeacherRow>>(emptyList()) }
    var records by remember { mutableStateOf<List<AdminRecordRow>>(emptyList()) }
    var recordsSchemaVersion by remember { mutableStateOf(2) }
    var recordsTruncated by remember { mutableStateOf(false) }
    var selectedTeacher by remember { mutableStateOf<AdminTeacherRow?>(null) }
    var selectedClass by remember { mutableStateOf<AdminRecordRow?>(null) }
    var selectedRecord by remember { mutableStateOf<AdminRecordDetails?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AdminRecordRow?>(null) }

    fun loadTeachers() {
        loading = true
        error = null
        functions.getHttpsCallable("adminListTeachers").call()
            .addOnSuccessListener { result ->
                val data = result.data as? Map<*, *>
                teachers = (data?.get("teachers") as? List<*>).orEmpty().mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    val uid = map["uid"] as? String ?: return@mapNotNull null
                    AdminTeacherRow(uid, map["name"] as? String ?: "", map["email"] as? String ?: "")
                }
                loading = false
            }
            .addOnFailureListener { exception ->
                error = exception.localizedMessage ?: "دریافت فهرست استادان ناموفق بود."
                loading = false
            }
    }

    fun loadRecords(teacher: AdminTeacherRow) {
        loading = true
        error = null
        records = emptyList()
        recordsSchemaVersion = 2
        recordsTruncated = false
        functions.getHttpsCallable("adminListTeacherRecords")
            .call(mapOf("targetTeacherUid" to teacher.uid))
            .addOnSuccessListener { result ->
                val data = result.data as? Map<*, *>
                recordsSchemaVersion = (data?.get("schemaVersion") as? Number)?.toInt() ?: 2
                recordsTruncated = data?.get("truncated") as? Boolean ?: false
                records = (data?.get("records") as? List<*>).orEmpty().mapNotNull { item ->
                    val map = item as? Map<*, *> ?: return@mapNotNull null
                    val id = (map["id"] as? Number)?.toLong() ?: return@mapNotNull null
                    val type = map["recordType"] as? String ?: return@mapNotNull null
                    AdminRecordRow(
                        id, type, map["title"] as? String ?: "",
                        (map["classId"] as? Number)?.toLong(),
                        (map["courseId"] as? Number)?.toLong(),
                        map["isPublished"] as? Boolean ?: false
                    )
                }.sortedWith(compareBy<AdminRecordRow>({ typeOrder(it.type) }, { it.title }))
                loading = false
            }
            .addOnFailureListener { exception ->
                error = exception.localizedMessage ?: "دریافت اطلاعات استاد ناموفق بود."
                loading = false
            }
    }

    fun openRecord(row: AdminRecordRow, teacher: AdminTeacherRow) {
        loading = true
        error = null
        functions.getHttpsCallable("adminGetTeacherRecord").call(
            mapOf("targetTeacherUid" to teacher.uid, "recordType" to row.type, "recordId" to row.id)
        ).addOnSuccessListener { result ->
            val data = result.data as? Map<*, *>
            val values = data?.get("record") as? Map<*, *> ?: emptyMap<Any, Any>()
            selectedRecord = AdminRecordDetails(row, values.entries.associate { it.key.toString() to it.value })
            loading = false
        }.addOnFailureListener { exception ->
            error = exception.localizedMessage ?: "دریافت جزئیات رکورد ناموفق بود."
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadTeachers() }

    val record = selectedRecord
    val teacher = selectedTeacher
    val editableFields = when (record?.row?.type) {
        "class" -> listOf("name", "teacherName", "description", "term")
        "course" -> listOf("name", "teacherName", "description")
        "lecture" -> listOf("title", "courseName", "professorName", "transcript", "originalTranscript", "aiSummary", "aiKeyPoints", "audioUrl", "tags")
        else -> emptyList()
    }
    val fieldLabels = mapOf(
        "name" to "نام کلاس/درس", "teacherName" to "نام استاد", "description" to "توضیحات",
        "term" to "ترم", "title" to "عنوان جلسه", "courseName" to "نام درس",
        "professorName" to "نام استاد", "transcript" to "متن جلسه",
        "originalTranscript" to "متن خام اولیه", "aiSummary" to "خلاصه هوشمند",
        "aiKeyPoints" to "کلیدواژه‌ها و نکات", "audioUrl" to "پیوند صوت", "tags" to "برچسب‌ها"
    )
    val fieldValues = remember(record?.row?.id, record?.row?.type) {
        mutableStateMapOf<String, String>().apply {
            editableFields.forEach { key -> put(key, record?.values?.get(key)?.toString().orEmpty()) }
        }
    }
    var published by remember(record?.row?.id) {
        mutableStateOf(record?.values?.get("isPublished") as? Boolean ?: false)
    }

    fun goBackOneLevel() {
        when {
            selectedRecord != null -> selectedRecord = null
            selectedClass != null -> selectedClass = null
            selectedTeacher != null -> { selectedTeacher = null; records = emptyList(); error = null }
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when {
                        record != null -> "ویرایش اطلاعات"
                        selectedClass != null -> "درس‌ها و جلسات " + selectedClass!!.title
                        teacher != null -> "کلاس‌های " + teacher.name.ifBlank { "استاد" }
                        else -> "مدیریت استادان"
                    })
                },
                navigationIcon = {
                    IconButton(onClick = { goBackOneLevel() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            when {
                record != null && teacher != null -> {
                    Text("نوع رکورد: " + recordTypeLabel(record.row.type), fontWeight = FontWeight.Bold)
                    Text("شناسه: " + record.row.id + "  |  استاد: " + teacher.name)
                    editableFields.forEach { key ->
                        OutlinedTextField(
                            value = fieldValues[key].orEmpty(),
                            onValueChange = { fieldValues[key] = it },
                            label = { Text(fieldLabels[key] ?: key) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = if (key in listOf("transcript", "originalTranscript", "description", "aiSummary", "aiKeyPoints")) 3 else 1,
                            readOnly = !supportsMutationForSchema(recordsSchemaVersion)
                        )
                    }
                    if (record.row.type == "class" || record.row.type == "lecture") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("انتشار برای دانشجویان", modifier = Modifier.weight(1f))
                            Switch(
                                checked = published,
                                onCheckedChange = { published = it },
                                enabled = supportsMutationForSchema(recordsSchemaVersion)
                            )
                        }
                    }
                    Button(
                        onClick = {
                            val patch = linkedMapOf<String, Any?>()
                            editableFields.forEach { key ->
                                val newValue = fieldValues[key].orEmpty()
                                val oldValue = record.values[key]?.toString().orEmpty()
                                if (newValue != oldValue) {
                                    patch[key] = if (key == "audioUrl" || key == "aiSummary" || key == "aiKeyPoints") {
                                        newValue.takeIf { it.isNotBlank() }
                                    } else newValue
                                }
                            }
                            if (record.row.type == "class" || record.row.type == "lecture") {
                                if (published != (record.values["isPublished"] as? Boolean ?: false)) patch["isPublished"] = published
                            }
                            if (patch.isEmpty()) {
                                scope.launch { snackbar.showSnackbar("تغییری برای ذخیره وجود ندارد.") }
                            } else {
                                saving = true
                                functions.getHttpsCallable("adminUpdateTeacherRecord").call(
                                    mapOf(
                                        "targetTeacherUid" to teacher.uid,
                                        "recordType" to record.row.type,
                                        "recordId" to record.row.id,
                                        "patch" to patch
                                    )
                                ).addOnSuccessListener {
                                    saving = false
                                    scope.launch {
                                        snackbar.showSnackbar("تغییرات ثبت شد. دستگاه استاد برای دریافت آن باید بازیابی ابری انجام دهد.")
                                    }
                                    selectedRecord = null
                                    loadRecords(teacher)
                                }.addOnFailureListener { exception ->
                                    saving = false
                                    error = exception.localizedMessage ?: "ذخیره تغییرات ناموفق بود."
                                }
                            }
                        },
                        enabled = !saving && !loading && supportsMutationForSchema(recordsSchemaVersion),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (saving) "در حال ذخیره..." else "ذخیره تغییرات") }
                    if (supportsMutationForSchema(recordsSchemaVersion)) {
                        OutlinedButton(
                            onClick = { deleteTarget = record.row },
                            enabled = !saving && !loading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("حذف این رکورد") }
                    } else {
                        Text(
                            "این پشتیبان از نسل‌بندی نسخهٔ ۳ استفاده می‌کند؛ مشاهده مجاز است اما ویرایش و حذف تا آماده‌شدن عملیات امن نسل جدید غیرفعال است.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "تغییرات در پشتیبان خصوصی استاد ثبت می‌شود. دستگاه استاد باید پس از اتصال به اینترنت بازیابی ابری را انجام دهد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                teacher != null && selectedClass != null -> {
                    Text("استاد: " + teacher.name, style = MaterialTheme.typography.bodySmall)
                    if (recordsTruncated) {
                        Text(
                            "فهرست بیش از ۱۵۰۰ رکورد دارد؛ ممکن است بخشی از جلسات این کلاس در فهرست نمایش داده نشود.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val sessions = records.filter { it.type == "lecture" && it.classId == selectedClass?.id }
                    val coursesInClass = records.filter { it.type == "course" && it.classId == selectedClass?.id }
                    if (sessions.isEmpty() && coursesInClass.isEmpty() && !loading) {
                        Text("در این کلاس هنوز جلسه یا اطلاعات درسی ثبت نشده است.")
                    }
                    if (sessions.isNotEmpty()) {
                        Text("جلسات کلاس", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        sessions.forEach { row ->
                            Card(Modifier.fillMaxWidth().clickable { openRecord(row, teacher) }) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(row.title.ifBlank { "جلسه بدون عنوان" }, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "جلسه  •  شناسه " + row.id +
                                            (row.courseId?.let { "  •  شناسه درس " + it } ?: ""),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(if (row.published) "منتشرشده" else "منتشرنشده", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (coursesInClass.isNotEmpty()) {
                        Text("اطلاعات درس‌های کلاس", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        coursesInClass.forEach { row ->
                            Card(Modifier.fillMaxWidth().clickable { openRecord(row, teacher) }) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(row.title.ifBlank { "درس بدون عنوان" }, fontWeight = FontWeight.SemiBold)
                                    Text("اطلاعات درس  •  شناسه " + row.id, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                teacher != null -> {
                    Text("حساب استاد: " + teacher.email, style = MaterialTheme.typography.bodySmall)
                    if (recordsTruncated) {
                        Text(
                            "فهرست بیش از ۱۵۰۰ رکورد دارد؛ این صفحه فقط ۱۵۰۰ مورد نخست را نشان می‌دهد.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val classes = records.filter { it.type == "class" }
                    if (classes.isEmpty() && !loading) Text("کلاسی برای این استاد پیدا نشد.")
                    classes.forEach { row ->
                        Card(Modifier.fillMaxWidth().clickable { selectedClass = row }) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.title.ifBlank { "کلاس بدون عنوان" }, fontWeight = FontWeight.SemiBold)
                                Text("کلاس  •  شناسه " + row.id, style = MaterialTheme.typography.bodySmall)
                                Text(if (row.published) "منتشرشده" else "منتشرنشده", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                else -> {
                    Text("فقط استادان دارای مجوز فعال در این فهرست نمایش داده می‌شوند.")
                    if (teachers.isEmpty() && !loading) Text("استاد فعالی پیدا نشد.")
                    teachers.forEach { item ->
                        Card(Modifier.fillMaxWidth().clickable {
                            selectedTeacher = item
                            loadRecords(item)
                        }) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.name.ifBlank { "استاد بدون نام" }, fontWeight = FontWeight.Bold)
                                Text(item.email, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedButton(onClick = { loadTeachers() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text("بازخوانی فهرست استادان")
                    }
                }
            }
        }
    }

    if (deleteTarget != null && teacher != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("تأیید حذف") },
            text = {
                Text(
                    if (deleteTarget?.type == "class") {
                        "با حذف این کلاس، تمام درس‌ها و جلسات وابسته به آن نیز حذف می‌شوند. این عملیات برگشت‌پذیر نیست. ادامه می‌دهید؟"
                    } else "این رکورد از پشتیبان خصوصی استاد حذف می‌شود. ادامه می‌دهید؟"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = deleteTarget ?: return@TextButton
                    deleteTarget = null
                    saving = true
                    functions.getHttpsCallable("adminDeleteTeacherRecord").call(
                        mapOf("targetTeacherUid" to teacher.uid, "recordType" to target.type, "recordId" to target.id)
                    ).addOnSuccessListener { result ->
                        saving = false
                        val count = (result.data as? Map<*, *>)?.get("deletedRecordCount") as? Number
                        scope.launch {
                            snackbar.showSnackbar(
                                if (target.type == "class") "کلاس و " + (count?.toInt() ?: 0) + " رکورد وابسته حذف شد."
                                else "رکورد حذف شد."
                            )
                        }
                        selectedRecord = null
                        loadRecords(teacher)
                    }.addOnFailureListener { exception ->
                        saving = false
                        error = exception.localizedMessage ?: "حذف ناموفق بود."
                    }
                }) { Text("حذف قطعی") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("انصراف") } }
        )
    }
}

private fun typeOrder(type: String): Int = when (type) {
    "class" -> 0
    "course" -> 1
    else -> 2
}

private fun recordTypeLabel(type: String): String = when (type) {
    "class" -> "کلاس"
    "course" -> "درس"
    "lecture" -> "جلسه"
    else -> "رکورد"
}

private fun supportsMutationForSchema(schemaVersion: Int): Boolean = schemaVersion == 2
