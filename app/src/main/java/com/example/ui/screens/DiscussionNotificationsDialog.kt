package com.example.ui.screens

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

private data class DiscussionNotificationItem(
    val id: String,
    val title: String,
    val body: String,
    val className: String,
    val sessionTitle: String,
    val actorName: String,
    val createdAt: String
)

@Composable
fun DiscussionNotificationsDialog(onDismiss: () -> Unit) {
    val notifications = remember { mutableStateListOf<DiscussionNotificationItem>() }
    val firestore = remember { FirebaseFirestore.getInstance() }

    DisposableEffect(Unit) {
        val registration: ListenerRegistration = firestore.collection("discussion_notifications")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) {
                    notifications.clear()
                    notifications.addAll(snapshot.documents.map { doc ->
                        val millis = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                        val date = if (millis > 0L) java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis)) else "همین الان"
                        DiscussionNotificationItem(
                            id = doc.id,
                            title = doc.getString("title").orEmpty().ifBlank { "اعلان گفت‌وگو" },
                            body = doc.getString("body").orEmpty(),
                            className = doc.getString("className").orEmpty(),
                            sessionTitle = doc.getString("sessionTitle").orEmpty(),
                            actorName = doc.getString("actorName").orEmpty(),
                            createdAt = date
                        )
                    })
                }
            }
        onDispose { registration.remove() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اعلان‌های پرسش و پاسخ") },
        text = {
            if (notifications.isEmpty()) {
                Text("هنوز اعلانی ثبت نشده است.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notifications, key = { it.id }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Text(item.className + if (item.sessionTitle.isNotBlank() && item.sessionTitle != item.className) " — " + item.sessionTitle else "")
                                if (item.actorName.isNotBlank()) Text("ثبت‌کننده: " + item.actorName, style = MaterialTheme.typography.bodySmall)
                                Text(item.body, style = MaterialTheme.typography.bodyMedium)
                                Text(item.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") }
        }
    )
}
