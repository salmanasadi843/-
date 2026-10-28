package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.security.MessageDigest

private data class DiscussionPost(
    val id: String,
    val parentId: String,
    val body: String,
    val authorUid: String,
    val authorName: String,
    val authorRole: String,
    val createdAtMillis: Long,
    val edited: Boolean
)

internal fun discussionSpaceKey(ownerUid: String, classCreatedAtMillis: Long, className: String, teacherName: String, term: String): String {
    val raw = if (classCreatedAtMillis > 0L) ownerUid + "|created:" + classCreatedAtMillis else listOf(ownerUid, className, teacherName, term).joinToString("|") { it.trim() }
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun discussionSessionKey(title: String, dateMillis: Long): String {
    val raw = title + "|" + dateMillis
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return "session_" + digest.joinToString("") { "%02x".format(it) }
}

@Composable
fun DiscussionDialog(
    className: String,
    teacherName: String,
    term: String,
    ownerUid: String,
    classCreatedAtMillis: Long = 0L,
    isPublished: Boolean,
    userRole: UserRole,
    sessionTitle: String? = null,
    sessionDateMillis: Long? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uid = AuthPreferences.currentUid(context).orEmpty()
    val authorRole = if (AuthPreferences.isPrimaryTeacher(context)) "ADMIN" else userRole.name
    val authorName = AuthPreferences.currentName(context).ifBlank { if (authorRole == "ADMIN") "مدیر" else if (authorRole == "TEACHER") "استاد" else "دانشجو" }
    val spaceKey = remember(ownerUid, classCreatedAtMillis, className, teacherName, term) {
        discussionSpaceKey(ownerUid, classCreatedAtMillis, className, teacherName, term)
    }
    val activeSessionKey = remember(sessionTitle, sessionDateMillis) {
        if (sessionTitle == null) "__class__" else discussionSessionKey(sessionTitle, sessionDateMillis ?: 0L)
    }
    val isClassOwner = uid.isNotBlank() && uid == ownerUid
    val canParticipate = uid.isNotBlank() && (isPublished || isClassOwner || AuthPreferences.isPrimaryTeacher(context))
    val posts = remember(spaceKey, activeSessionKey) { mutableStateListOf<DiscussionPost>() }
    var draft by remember { mutableStateOf("") }
    var editingPost by remember { mutableStateOf<DiscussionPost?>(null) }
    var pendingDelete by remember { mutableStateOf<DiscussionPost?>(null) }
    var replyTo by remember { mutableStateOf<DiscussionPost?>(null) }
    var loading by remember { mutableStateOf(true) }
    var roomReady by remember(spaceKey) { mutableStateOf(false) }
    val canReply = canParticipate && roomReady
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val firestore = remember { FirebaseFirestore.getInstance() }
    val spaceRef = remember(spaceKey) { firestore.collection("discussion_spaces").document(spaceKey) }
    val postsRef = remember(spaceKey) { spaceRef.collection("posts") }

    LaunchedEffect(spaceKey, uid, ownerUid, className, teacherName, term, isPublished) {
        if (uid.isBlank()) {
            error = "برای استفاده از تالار گفتگو ابتدا وارد حساب کاربری شوید."
            loading = false
            return@LaunchedEffect
        }
        spaceRef.get()
            .addOnSuccessListener { snapshot ->
                val metadata = mapOf(
                    "ownerUid" to ownerUid,
                    "className" to className,
                    "teacherName" to teacherName,
                    "term" to term,
                    "isPublished" to isPublished
                )
                if (!snapshot.exists()) {
                    if (isClassOwner || AuthPreferences.isPrimaryTeacher(context)) {
                        spaceRef.set(metadata + ("createdAt" to FieldValue.serverTimestamp()))
                            .addOnSuccessListener { roomReady = true }
                            .addOnFailureListener { e -> error = e.localizedMessage ?: "ساخت تالار گفتگو انجام نشد." }
                    } else {
                        error = "تالار این کلاس هنوز فعال نشده است؛ استاد باید یک‌بار تالار گفتگو را باز کند."
                        roomReady = false
                        loading = false
                    }
                } else {
                    roomReady = true
                    if (isClassOwner || AuthPreferences.isPrimaryTeacher(context)) {
                        spaceRef.set(metadata, SetOptions.merge())
                            .addOnFailureListener { e -> error = e.localizedMessage ?: "به‌روزرسانی مشخصات تالار انجام نشد." }
                    }
                }
            }
            .addOnFailureListener { e ->
                error = e.localizedMessage ?: "دسترسی به تالار گفتگو ممکن نیست."
                roomReady = false
                loading = false
            }
    }

    DisposableEffect(spaceKey, activeSessionKey, uid, roomReady) {
        var registration: ListenerRegistration? = null
        if (uid.isNotBlank() && roomReady) {
            registration = postsRef.whereEqualTo("sessionKey", activeSessionKey)
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        error = e.localizedMessage ?: "دریافت گفتگوها ناموفق بود."
                        loading = false
                    } else {
                        posts.clear()
                        posts.addAll(snapshot?.documents.orEmpty().map { doc ->
                            DiscussionPost(
                                id = doc.id,
                                parentId = doc.getString("parentId").orEmpty(),
                                body = doc.getString("body").orEmpty(),
                                authorUid = doc.getString("authorUid").orEmpty(),
                                authorName = doc.getString("authorName").orEmpty(),
                                authorRole = doc.getString("authorRole").orEmpty(),
                                createdAtMillis = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
                                edited = doc.getBoolean("edited") == true
                            )
                        }.sortedBy { it.createdAtMillis })
                        loading = false
                    }
                }
        } else if (uid.isBlank()) {
            loading = false
        }
        onDispose { registration?.remove() }
    }


    fun canManagePost(post: DiscussionPost): Boolean {
        return uid == post.authorUid || uid == ownerUid || AuthPreferences.isPrimaryTeacher(context)
    }

    fun saveEditedPost() {
        val post = editingPost ?: return
        val text = draft.trim()
        if (text.isBlank() || sending || !canManagePost(post)) return
        sending = true
        error = null
        postsRef.document(post.id).update(
            mapOf(
                "body" to text,
                "edited" to true,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).addOnSuccessListener {
            draft = ""
            editingPost = null
            sending = false
        }.addOnFailureListener { e ->
            error = e.localizedMessage ?: "ویرایش پیام انجام نشد."
            sending = false
        }
    }

    fun deletePost(post: DiscussionPost) {
        if (!canManagePost(post) || sending) return
        sending = true
        error = null
        postsRef.document(post.id).delete()
            .addOnSuccessListener {
                if (replyTo?.id == post.id) replyTo = null
                if (editingPost?.id == post.id) {
                    editingPost = null
                    draft = ""
                }
                pendingDelete = null
                sending = false
            }
            .addOnFailureListener { e ->
                error = e.localizedMessage ?: "حذف پیام انجام نشد."
                pendingDelete = null
                sending = false
            }
    }

    fun sendPost() {
        val text = draft.trim()
        if (text.isBlank() || sending || uid.isBlank()) return
        if (replyTo != null && !canReply) {
            error = "برای پاسخ‌دادن باید به تالار منتشرشده یا تالار کلاس خود دسترسی داشته باشید."
            return
        }
        sending = true
        error = null
        postsRef.add(
            mapOf(
                "sessionKey" to activeSessionKey,
                "parentId" to (replyTo?.id ?: ""),
                "body" to text,
                "authorUid" to uid,
                "authorName" to authorName,
                "authorRole" to authorRole,
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).addOnSuccessListener {
            draft = ""
            replyTo = null
            sending = false
        }.addOnFailureListener { e ->
            error = e.localizedMessage ?: "ارسال پیام انجام نشد."
            sending = false
        }
    }

    val questions = posts.filter { it.parentId.isBlank() }
    val answersByQuestion = posts.filter { it.parentId.isNotBlank() }.groupBy { it.parentId }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { if (!sending) pendingDelete = null },
            title = { Text("حذف پیام؟") },
            text = { Text("این پیام حذف شود؟ این کار قابل بازگشت نیست.") },
            confirmButton = { TextButton(enabled = !sending, onClick = { pendingDelete?.let { deletePost(it) } }) { Text("حذف") } },
            dismissButton = { TextButton(enabled = !sending, onClick = { pendingDelete = null }) { Text("انصراف") } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Forum, contentDescription = null) },
        title = {
            Column {
                Text(if (sessionTitle == null) "تالار گفتگوی درس" else "اشکال و پاسخ جلسه")
                Text(
                    if (sessionTitle == null) className else sessionTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                } else if (questions.isEmpty()) {
                    Text(if (sessionTitle == null) "هنوز پرسشی برای این درس ثبت نشده است." else "هنوز اشکالی برای این جلسه ثبت نشده است.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(questions, key = { it.id }) { question ->
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(question.body, style = MaterialTheme.typography.bodyLarge)
                                if (question.edited) Text("ویرایش‌شده", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (canManagePost(question)) {
                                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                        TextButton(onClick = { editingPost = question; replyTo = null; draft = question.body }) { Text("ویرایش") }
                                        TextButton(onClick = { pendingDelete = question }) { Text("حذف") }
                                    }
                                }
                                Text(
                                    question.authorName + " • " + when (question.authorRole) { "TEACHER" -> "استاد"; "ADMIN" -> "مدیر"; else -> "دانشجو" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                (answersByQuestion[question.id] ?: emptyList()).forEach { answer ->
                                    Column(Modifier.fillMaxWidth().padding(start = 10.dp)) {
                                        Text("پاسخ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Text(answer.body, style = MaterialTheme.typography.bodyMedium)
                                        if (answer.edited) Text("ویرایش‌شده", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (canManagePost(answer)) {
                                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                                TextButton(onClick = { editingPost = answer; replyTo = null; draft = answer.body }) { Text("ویرایش") }
                                                TextButton(onClick = { pendingDelete = answer }) { Text("حذف") }
                                            }
                                        }
                                        Text(
                                            answer.authorName + " • " + when (answer.authorRole) { "TEACHER" -> "استاد"; "ADMIN" -> "مدیر"; else -> "دانشجو" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(enabled = canReply && replyTo?.id != question.id, onClick = { replyTo = question }) {
                                        Text("پاسخ")
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }

                if (!isPublished && !isClassOwner && !AuthPreferences.isPrimaryTeacher(context)) {
                    Text("این کلاس منتشر نشده است.", color = MaterialTheme.colorScheme.error)
                } else {
                    if (editingPost != null) {
                        Text("در حال ویرایش پیام", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { editingPost = null; draft = "" }) { Text("لغو ویرایش") }
                    } else if (replyTo != null) {
                        Text("در حال پاسخ به: " + (replyTo?.authorName ?: ""), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { replyTo = null }) { Text("لغو پاسخ") }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(when { editingPost != null -> "ویرایش پیام"; replyTo == null -> "ثبت پرسش یا اشکال"; else -> "متن پاسخ" }) },
                        minLines = 2,
                        maxLines = 4,
                        enabled = canParticipate && roomReady && (replyTo == null || canReply)
                    )
                    Button(
                        onClick = { if (editingPost != null) saveEditedPost() else sendPost() },
                        enabled = draft.isNotBlank() && !sending && canParticipate && roomReady && (editingPost == null || editingPost?.let { canManagePost(it) } == true) && (replyTo == null || canReply),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (sending) CircularProgressIndicator()
                        else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            Text(when { editingPost != null -> "ذخیرهٔ ویرایش"; replyTo == null -> "ثبت پرسش"; else -> "ارسال پاسخ" })
                        }
                    }
                }
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } }
    )
}
