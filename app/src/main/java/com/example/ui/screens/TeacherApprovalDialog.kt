package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

private const val DARSYAR_ADMIN_UID = "xgUdnaQ6X0hPobOUwx3SDI0BJVc2"

private data class TeacherRequestItem(
    val uid: String,
    val email: String,
    val createdAtLabel: String
)

@Composable
fun TeacherApprovalDialog(onDismiss: () -> Unit) {
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    var requests by remember { mutableStateOf<List<TeacherRequestItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var actionInProgress by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var revokeUid by remember { mutableStateOf("") }

    if (auth.currentUser?.uid != DARSYAR_ADMIN_UID) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("دسترسی غیرمجاز") },
            text = { Text("این بخش فقط برای مدیر اصلی درس‌یار فعال است.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } }
        )
        return
    }

    DisposableEffect(db) {
        val registration = db.collection("teacher_requests")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, error ->
                loading = false
                if (error != null) {
                    message = "دریافت درخواست‌ها ناموفق بود: ${error.localizedMessage ?: "خطای نامشخص"}"
                } else {
                    requests = snapshot?.documents.orEmpty().mapNotNull { doc ->
                        val uid = doc.getString("uid") ?: doc.id
                        if (uid.isBlank()) null else TeacherRequestItem(
                            uid = uid,
                            email = doc.getString("email").orEmpty(),
                            createdAtLabel = doc.getTimestamp("createdAt")?.toDate()?.toString().orEmpty()
                        )
                    }
                }
            }
        onDispose { registration.remove() }
    }

    fun approve(uid: String) {
        if (uid.isBlank() || actionInProgress) return
        actionInProgress = true
        message = null
        val batch = db.batch()
        batch.set(
            db.collection("users").document(uid),
            mapOf("role" to "TEACHER"),
            SetOptions.merge()
        )
        batch.set(
            db.collection("approved_teachers").document(uid),
            mapOf(
                "ownerUid" to uid,
                "enabled" to true,
                "approvedBy" to DARSYAR_ADMIN_UID,
                "approvedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.delete(db.collection("teacher_requests").document(uid))
        batch.commit()
            .addOnSuccessListener { message = "حساب استاد تأیید شد."; actionInProgress = false }
            .addOnFailureListener { e ->
                message = "تأیید انجام نشد: ${e.localizedMessage ?: "خطای نامشخص"}"
                actionInProgress = false
            }
    }

    fun revoke(uid: String) {
        if (uid.isBlank() || actionInProgress) return
        actionInProgress = true
        message = null
        val batch = db.batch()
        batch.set(
            db.collection("users").document(uid),
            mapOf("role" to "STUDENT"),
            SetOptions.merge()
        )
        batch.delete(db.collection("approved_teachers").document(uid))
        batch.commit()
            .addOnSuccessListener { message = "مجوز استادی لغو شد."; actionInProgress = false; revokeUid = "" }
            .addOnFailureListener { e ->
                message = "لغو مجوز انجام نشد: ${e.localizedMessage ?: "خطای نامشخص"}"
                actionInProgress = false
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مدیریت استادان") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "درخواست‌های استاد را بررسی کن. پس از تأیید، نقش حساب به استاد تغییر می‌کند.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (loading) {
                    CircularProgressIndicator()
                } else if (requests.isEmpty()) {
                    Text("درخواست در انتظار تأیید وجود ندارد.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(requests, key = { it.uid }) { request ->
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(4.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(request.email.ifBlank { "ایمیل ثبت نشده" }, style = MaterialTheme.typography.bodyMedium)
                                Text("شناسه: ${request.uid}", style = MaterialTheme.typography.labelSmall)
                                if (request.createdAtLabel.isNotBlank()) {
                                    Text(request.createdAtLabel, style = MaterialTheme.typography.labelSmall)
                                }
                                Button(
                                    onClick = { approve(request.uid) },
                                    enabled = !actionInProgress,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("تأیید استاد") }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("لغو مجوز استاد", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = revokeUid,
                    onValueChange = { revokeUid = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("شناسه UID استاد") },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = { revoke(revokeUid.trim()) },
                    enabled = !actionInProgress && revokeUid.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("لغو مجوز") }

                if (actionInProgress) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text("در حال ثبت تغییرات…")
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        }
    )
}

@Composable
fun TeacherRequestCard() {
    val user = FirebaseAuth.getInstance().currentUser
    val db = FirebaseFirestore.getInstance()
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("درخواست حساب استاد", style = MaterialTheme.typography.titleMedium)
            Text(
                "اگر می‌خواهی کلاس و جلسه ایجاد کنی، درخواستت را برای مدیر اصلی بفرست.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = {
                    val uid = user?.uid
                    if (uid.isNullOrBlank()) {
                        message = "ابتدا وارد حساب کاربری شو."
                    } else {
                        loading = true
                        message = null
                        val requestRef = db.collection("teacher_requests").document(uid)
                        requestRef.get()
                            .addOnSuccessListener { existing ->
                                if (existing.exists()) {
                                    message = "درخواست قبلی شما ثبت شده و در انتظار بررسی است."
                                    loading = false
                                } else {
                                    requestRef.set(
                                        mapOf(
                                            "uid" to uid,
                                            "email" to user.email.orEmpty(),
                                            "status" to "PENDING",
                                            "createdAt" to FieldValue.serverTimestamp()
                                        )
                                    ).addOnSuccessListener {
                                        message = "درخواست برای مدیر ارسال شد."
                                        loading = false
                                    }.addOnFailureListener { e ->
                                        message = "ارسال درخواست ناموفق بود: ${e.localizedMessage ?: "خطای نامشخص"}"
                                        loading = false
                                    }
                                }
                            }
                            .addOnFailureListener { e ->
                                message = "بررسی درخواست ناموفق بود: ${e.localizedMessage ?: "خطای نامشخص"}"
                                loading = false
                            }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (loading) "در حال ارسال…" else "ارسال درخواست")
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
