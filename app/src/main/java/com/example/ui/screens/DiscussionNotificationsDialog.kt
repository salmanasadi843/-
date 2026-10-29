package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

private data class DiscussionNotificationItem(
    val id: String,
    val actorUid: String,
    val title: String,
    val body: String,
    val className: String,
    val sessionTitle: String,
    val isClassDiscussion: Boolean,
    val ownerUid: String,
    val teacherName: String,
    val term: String,
    val classCreatedAtMillis: Long,
    val isPublished: Boolean,
    val sessionDateMillis: Long,
    val actorName: String,
    val createdAt: String
)

@Composable
fun DiscussionNotificationsDialog(userRole: UserRole, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uid = AuthPreferences.currentUid(context).orEmpty()
    val notifications = remember { mutableStateListOf<DiscussionNotificationItem>() }
    val firestore = remember { FirebaseFirestore.getInstance() }
    val preferences = remember(uid) {
        context.getSharedPreferences("discussion_notification_reads_$uid", Context.MODE_PRIVATE)
    }
    val readIds = remember(uid) {
        mutableStateListOf<String>().apply {
            addAll(preferences.getStringSet("read_ids", emptySet()).orEmpty())
        }
    }
    var selectedNotification by remember { mutableStateOf<DiscussionNotificationItem?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(uid) {
        var registration: ListenerRegistration? = null
        if (uid.isNotBlank()) {
            registration = firestore.collection("discussion_notifications")
                .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(100)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        loadError = error.localizedMessage ?: "دریافت اعلان‌ها ناموفق بود."
                    } else if (snapshot != null) {
                        loadError = null
                        // پاک‌سازی اعلان‌های قدیمی که پیام اصلی‌شان قبلاً حذف شده است.
                        snapshot.documents.forEach { doc ->
                            val sourceSpaceId = doc.getString("sourceSpaceId")
                            val sourcePostId = doc.getString("sourcePostId")
                            if (!sourceSpaceId.isNullOrBlank() && !sourcePostId.isNullOrBlank()) {
                                firestore.collection("discussion_spaces")
                                    .document(sourceSpaceId)
                                    .collection("posts")
                                    .document(sourcePostId)
                                    .get()
                                    .addOnSuccessListener { sourcePost ->
                                        if (!sourcePost.exists()) {
                                            doc.reference.delete()
                                                .addOnFailureListener { e ->
                                                    android.util.Log.w(
                                                        "DiscussionNotifications",
                                                        "Could not clean stale notification ${doc.id}",
                                                        e
                                                    )
                                                }
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        android.util.Log.w(
                                            "DiscussionNotifications",
                                            "Could not verify notification source ${doc.id}",
                                            e
                                        )
                                    }
                            }
                        }
                        notifications.clear()
                        notifications.addAll(snapshot.documents
                            .filter { it.getString("actorUid") != uid }
                            .map { doc ->
                                val millis = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                                val date = if (millis > 0L) java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis)) else "همین الان"
                                DiscussionNotificationItem(
                                    id = doc.id,
                                    actorUid = doc.getString("actorUid").orEmpty(),
                                    title = doc.getString("title").orEmpty().ifBlank { "اعلان گفت‌وگو" },
                                    body = doc.getString("body").orEmpty(),
                                    className = doc.getString("className").orEmpty(),
                                    sessionTitle = doc.getString("sessionTitle").orEmpty(),
                                    isClassDiscussion = doc.getBoolean("isClassDiscussion") ?: true,
                                    ownerUid = doc.getString("ownerUid").orEmpty(),
                                    teacherName = doc.getString("teacherName").orEmpty(),
                                    term = doc.getString("term").orEmpty(),
                                    classCreatedAtMillis = doc.getLong("classCreatedAtMillis") ?: 0L,
                                    isPublished = doc.getBoolean("isPublished") == true,
                                    sessionDateMillis = doc.getLong("sessionDateMillis") ?: 0L,
                                    actorName = doc.getString("actorName").orEmpty(),
                                    createdAt = date
                                )
                            })
                    }
                }
        } else {
            loadError = "برای دیدن اعلان‌ها وارد حساب کاربری شوید."
        }
        onDispose { registration?.remove() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اعلان‌های پرسش و پاسخ") },
        text = {
            when {
                loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
                notifications.isEmpty() -> Text("اعلان تازه‌ای وجود ندارد.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notifications, key = { it.id }) { item ->
                        val isRead = item.id in readIds
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (!isRead) {
                                    readIds.add(item.id)
                                    preferences.edit().putStringSet("read_ids", readIds.toSet()).apply()
                                }
                                selectedNotification = item
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isRead) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    item.title + if (isRead) " · خوانده‌شده" else " · جدید",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(item.className + if (!item.isClassDiscussion && item.sessionTitle.isNotBlank()) " — " + item.sessionTitle else "")
                                if (item.actorName.isNotBlank()) Text("ثبت‌کننده: " + item.actorName, style = MaterialTheme.typography.bodySmall)
                                Text(item.body, style = MaterialTheme.typography.bodyMedium)
                                Text(item.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("برای بازکردن گفت‌وگو لمس کنید", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } }
    )

    selectedNotification?.let { item ->
        DiscussionDialog(
            className = item.className,
            teacherName = item.teacherName,
            term = item.term,
            ownerUid = item.ownerUid,
            classCreatedAtMillis = item.classCreatedAtMillis,
            isPublished = item.isPublished,
            userRole = userRole,
            sessionTitle = if (item.isClassDiscussion) null else item.sessionTitle,
            sessionDateMillis = if (item.isClassDiscussion) null else item.sessionDateMillis,
            onDismiss = { selectedNotification = null }
        )
    }
}
