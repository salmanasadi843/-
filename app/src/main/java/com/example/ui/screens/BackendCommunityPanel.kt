package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackendForumPanel(classId:String, sessionId:String?, currentUserId:String, canModerate:Boolean, onStatus:(String)->Unit, initialTopicId:String?=null, initialPostId:String?=null, initialOpenRequestId:Int=0) {
    val context=LocalContext.current
    val scope= rememberCoroutineScope()
    var topics by remember(classId) { mutableStateOf<List<BackendForumTopicDto>>(emptyList()) }
    var selected by remember(classId) { mutableStateOf<BackendForumTopicDto?>(null) }
    var highlightedPostId by remember(classId) { mutableStateOf<String?>(null) }
    var posts by remember(classId) { mutableStateOf<List<BackendForumPostDto>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf<String?>(null) }
    var editingTopic by remember { mutableStateOf(false) }
    var editTopicTitle by remember { mutableStateOf("") }
    var editTopicBody by remember { mutableStateOf("") }
    var editingPostId by remember { mutableStateOf<String?>(null) }
    var editPostBody by remember { mutableStateOf("") }
    var pendingDeleteTopic by remember { mutableStateOf<BackendForumTopicDto?>(null) }
    var pendingDeletePost by remember { mutableStateOf<BackendForumPostDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(action:suspend (String,DarsyarCommunityRepository)->Unit) {
        if(busy) return
        busy=true;error=null
        AuthPreferences.getFirebaseIdToken(context) { result ->
            result.fold(onSuccess={token->scope.launch {
                try { withContext(Dispatchers.IO) { action(token,DarsyarCommunityApiFactory.create()) } }
                catch(e:Exception){error=e.localizedMessage ?: "خطا در ارتباط با تالار"}
                finally {busy=false}
            }},onFailure={e->busy=false;error=e.localizedMessage ?: "دریافت توکن ناموفق بود"})
        }
    }
    LaunchedEffect(classId, initialTopicId, initialPostId, initialOpenRequestId) { run { token,repo ->
        topics=repo.topics(token,classId)
        val target=initialTopicId?.let { id -> topics.firstOrNull { it.id==id } }
        if(target!=null) { selected=target;posts=repo.posts(token,target.id);parentId=null;highlightedPostId=initialPostId }
    } }

    Column(Modifier.fillMaxWidth().padding(top=18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("تالار گفت‌وگوی کلاس",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        Text("پرسش‌ها و پاسخ‌های این کلاس؛ بحث می‌تواند به جلسه انتخاب‌شده پیوند بخورد.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(value=title,onValueChange={title=it},modifier=Modifier.fillMaxWidth(),label={Text("عنوان بحث")},singleLine=true)
        OutlinedTextField(value=body,onValueChange={body=it},modifier=Modifier.fillMaxWidth(),label={Text("متن پرسش یا بحث")},minLines=2)
        Button(enabled=!busy && title.isNotBlank() && body.isNotBlank(),onClick={
            run { token,repo ->
                val created=repo.createTopic(token,classId,sessionId,title.trim(),body.trim())
                topics=listOf(created)+topics;title="";body="";posts=emptyList();selected=created
                onStatus("بحث جدید ثبت شد.")
            }
        },modifier=Modifier.fillMaxWidth()) { Text(if(busy) "در حال انجام…" else "ایجاد بحث") }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
        if(topics.isEmpty() && !busy) Text("هنوز بحثی در این تالار ثبت نشده است.",style=MaterialTheme.typography.bodySmall)
        topics.forEach { topic ->
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(selected?.id==topic.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text((if(topic.isPinned) "📌 " else "")+topic.title+(if(topic.isLocked) " · بسته" else ""),fontWeight=FontWeight.SemiBold)
                    Text(topic.authorName+" · "+topic.replyCount+" پاسخ",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={run { token,repo -> posts=repo.posts(token,topic.id);selected=topic;parentId=null }}) { Text("مشاهده گفت‌وگو") }
                }
            }
        }
        selected?.let { topic ->
            HorizontalDivider()
            Text(topic.title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold)
            if(topic.authorUserId==currentUserId || canModerate) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled=!busy,onClick={editTopicTitle=topic.title;editTopicBody=topic.body;editingTopic=true}) { Text("ویرایش بحث") }
                    TextButton(enabled=!busy,onClick={pendingDeleteTopic=topic}) { Text("حذف بحث") }
                }
            }
            if(editingTopic) {
                OutlinedTextField(value=editTopicTitle,onValueChange={editTopicTitle=it},modifier=Modifier.fillMaxWidth(),label={Text("عنوان بحث")},singleLine=true)
                OutlinedTextField(value=editTopicBody,onValueChange={editTopicBody=it},modifier=Modifier.fillMaxWidth(),label={Text("متن بحث")},minLines=2)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(enabled=!busy && editTopicTitle.isNotBlank() && editTopicBody.isNotBlank(),onClick={run { token,repo ->
                        val updated=repo.updateTopic(token,topic.id,editTopicTitle.trim(),editTopicBody.trim())
                        topics=topics.map { if(it.id==updated.id) updated else it };selected=updated;editingTopic=false
                        onStatus("بحث ویرایش شد.")
                    }}) { Text("ذخیره تغییرات") }
                    TextButton(enabled=!busy,onClick={editingTopic=false}) { Text("انصراف") }
                }
            } else Text(topic.body,style=MaterialTheme.typography.bodyMedium)
            if(canModerate) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(enabled=!busy,onClick={run { token,repo ->
                    repo.moderate(token,topic.id,!topic.isPinned,topic.isLocked)
                    topics=repo.topics(token,classId);selected=topics.firstOrNull{it.id==topic.id}
                }}) { Text(if(topic.isPinned) "برداشتن سنجاق" else "سنجاق") }
                TextButton(enabled=!busy,onClick={run { token,repo ->
                    repo.moderate(token,topic.id,topic.isPinned,!topic.isLocked)
                    topics=repo.topics(token,classId);selected=topics.firstOrNull{it.id==topic.id}
                }}) { Text(if(topic.isLocked) "بازکردن" else "بستن بحث") }
            }
            posts.forEach { post ->
                Card(
                    Modifier.fillMaxWidth().padding(start=if(post.parentPostId==null) 0.dp else 18.dp),
                    colors=CardDefaults.cardColors(containerColor=if(post.id==highlightedPostId) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(post.authorName,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.labelMedium)
                        if(editingPostId==post.id) {
                            OutlinedTextField(value=editPostBody,onValueChange={editPostBody=it},modifier=Modifier.fillMaxWidth(),label={Text("متن پاسخ")},minLines=2)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(enabled=!busy && editPostBody.isNotBlank(),onClick={run { token,repo ->
                                    val updated=repo.updatePost(token,post.id,editPostBody.trim())
                                    posts=posts.map { if(it.id==updated.id) updated else it };editingPostId=null
                                    onStatus("پاسخ ویرایش شد.")
                                }}) { Text("ذخیره") }
                                TextButton(enabled=!busy,onClick={editingPostId=null}) { Text("انصراف") }
                            }
                        } else {
                            Text(post.body,style=MaterialTheme.typography.bodySmall)
                            if(post.authorUserId==currentUserId || canModerate) Row {
                                TextButton(enabled=!busy,onClick={editingPostId=post.id;editPostBody=post.body}) { Text("ویرایش") }
                                TextButton(enabled=!busy,onClick={pendingDeletePost=post}) { Text("حذف") }
                            }
                        }
                        if(!topic.isLocked) TextButton(onClick={parentId=post.id}) { Text("پاسخ به این پیام") }
                    }
                }
            }
            if(!topic.isLocked) {
                if(parentId!=null) Text("پاسخ به پیام انتخاب‌شده: "+(posts.firstOrNull{it.id==parentId}?.authorName ?: ""))
                OutlinedTextField(value=reply,onValueChange={reply=it},modifier=Modifier.fillMaxWidth(),label={Text("پاسخ شما")},minLines=2)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(enabled=!busy && reply.isNotBlank(),onClick={run { token,repo ->
                        repo.reply(token,topic.id,parentId,reply.trim())
                        posts=repo.posts(token,topic.id);topics=repo.topics(token,classId)
                        selected=topics.firstOrNull{it.id==topic.id};reply="";parentId=null
                        onStatus("پاسخ شما ثبت شد.")
                    }}) { Text("ارسال پاسخ") }
                    if(parentId!=null) TextButton(onClick={parentId=null}) { Text("لغو پاسخ") }
                }
            } else Text("این بحث بسته شده و امکان ارسال پاسخ ندارد.",style=MaterialTheme.typography.bodySmall)
        }
    }
    pendingDeleteTopic?.let { topic ->
        AlertDialog(
            onDismissRequest={pendingDeleteTopic=null},
            title={Text("حذف بحث")},
            text={Text("این بحث و پاسخ‌های آن از تالار حذف شود؟")},
            confirmButton={TextButton(onClick={run { token,repo ->
                repo.deleteTopic(token,topic.id);topics=topics.filterNot{it.id==topic.id};selected=null;posts=emptyList();editingTopic=false;pendingDeleteTopic=null
                onStatus("بحث حذف شد.")
            }}) { Text("حذف") }},
            dismissButton={TextButton(onClick={pendingDeleteTopic=null}) { Text("انصراف") }}
        )
    }
    pendingDeletePost?.let { post ->
        AlertDialog(
            onDismissRequest={pendingDeletePost=null},
            title={Text("حذف پاسخ")},
            text={Text("این پاسخ از تالار حذف شود؟")},
            confirmButton={TextButton(onClick={run { token,repo ->
                repo.deletePost(token,post.id);posts=posts.filterNot{it.id==post.id};topics=repo.topics(token,classId);selected=topics.firstOrNull{it.id==selected?.id};pendingDeletePost=null
                onStatus("پاسخ حذف شد.")
            }}) { Text("حذف") }},
            dismissButton={TextButton(onClick={pendingDeletePost=null}) { Text("انصراف") }}
        )
    }
}

@Composable
fun BackendAnnouncementsPanel(classId:String,canManage:Boolean,onStatus:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var items by remember(classId) { mutableStateOf<List<BackendAnnouncementDto>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() {
        if(busy) return
        busy=true
        AuthPreferences.getFirebaseIdToken(context) { result -> result.fold(onSuccess={token->scope.launch {
            try { items=withContext(Dispatchers.IO){DarsyarCommunityApiFactory.create().announcements(token,classId)} }
            catch(e:Exception){error=e.localizedMessage ?: "دریافت اعلان‌ها ناموفق بود"}
            finally {busy=false}
        }},onFailure={error=it.localizedMessage ?: "دریافت توکن ناموفق بود";busy=false}) }
    }
    LaunchedEffect(classId) { load() }
    Column(Modifier.fillMaxWidth().padding(top=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("اعلان‌های رسمی کلاس",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        if(canManage) {
            OutlinedTextField(value=title,onValueChange={title=it},modifier=Modifier.fillMaxWidth(),label={Text("عنوان اعلان")},singleLine=true)
            OutlinedTextField(value=body,onValueChange={body=it},modifier=Modifier.fillMaxWidth(),label={Text("متن اعلان")},minLines=2)
            Button(enabled=!busy && title.isNotBlank() && body.isNotBlank(),onClick={
                busy=true;error=null
                AuthPreferences.getFirebaseIdToken(context) { result -> result.fold(onSuccess={token->scope.launch {
                    try {
                        val created=withContext(Dispatchers.IO){DarsyarCommunityApiFactory.create().createAnnouncement(token,classId,title.trim(),body.trim())}
                        items=listOf(created)+items;title="";body="";onStatus("اعلان برای اعضای کلاس منتشر شد.")
                    } catch(e:Exception){error=e.localizedMessage ?: "انتشار اعلان ناموفق بود"}
                    finally {busy=false}
                }},onFailure={error=it.localizedMessage ?: "دریافت توکن ناموفق بود";busy=false}) }
            },modifier=Modifier.fillMaxWidth()) { Text("انتشار اعلان") }
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
        if(items.isEmpty() && !busy) Text("هنوز اعلانی منتشر نشده است.",style=MaterialTheme.typography.bodySmall)
        items.forEach { item ->
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(item.title,fontWeight=FontWeight.SemiBold)
                    Text(item.body,style=MaterialTheme.typography.bodySmall)
                    Text(item.authorName+" · "+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.publishedAtMs)),style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun BackendNotificationsDialog(onDismiss:()->Unit, onUnreadCountChanged:(Int)->Unit = {}, onOpenNotification:(BackendNotificationDto)->Unit = {}) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var notifications by remember { mutableStateOf<List<BackendNotificationDto>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var markingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadNotifications() {
        if(busy || markingId!=null) return
        busy=true;error=null
        AuthPreferences.getFirebaseIdToken(context) { result ->
            result.fold(onSuccess={token->scope.launch {
                try {
                    notifications=withContext(Dispatchers.IO){DarsyarCommunityApiFactory.create().notifications(token)}
                    onUnreadCountChanged(notifications.count { !it.isRead })
                }
                catch(e:Exception){error=e.localizedMessage ?: "دریافت اعلان‌ها ناموفق بود"}
                finally {busy=false}
            }},onFailure={error=it.localizedMessage ?: "دریافت توکن ناموفق بود";busy=false})
        }
    }

    LaunchedEffect(Unit) { loadNotifications() }

    AlertDialog(
        onDismissRequest=onDismiss,
        title={Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("اعلان‌ها")
            Spacer(Modifier.weight(1f))
            TextButton(enabled=!busy && markingId==null,onClick={loadNotifications()}) { Text("تازه‌سازی") }
        }},
        text={
            Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                if(!busy && notifications.isEmpty() && error==null) Text("اعلانی ندارید.")
                notifications.take(50).forEach { item ->
                    Card(
                        Modifier.fillMaxWidth().clickable(enabled=markingId==null) {
                            if (!item.isRead) {
                                markingId=item.id
                                AuthPreferences.getFirebaseIdToken(context) { result -> result.fold(
                                    onSuccess={token->scope.launch {
                                        try {
                                            withContext(Dispatchers.IO){DarsyarCommunityApiFactory.create().markRead(token,item.id)}
                                            notifications=notifications.map { n -> if(n.id==item.id) n.copy(isRead=true) else n }
                                            onUnreadCountChanged(notifications.count { !it.isRead })
                                            onOpenNotification(item)
                                        } catch(e:Exception) {
                                            error=e.localizedMessage ?: "علامت‌گذاری اعلان ناموفق بود"
                                        } finally { markingId=null }
                                    }},
                                    onFailure={error=it.localizedMessage ?: "دریافت توکن ناموفق بود";markingId=null;onOpenNotification(item)}
                                ) }
                            } else onOpenNotification(item)
                        },
                        colors=CardDefaults.cardColors(containerColor=if(item.isRead) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text(item.message,style=MaterialTheme.typography.bodyMedium)
                            Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.createdAtMs)),style=MaterialTheme.typography.labelSmall)
                            if(!item.isRead) TextButton(enabled=markingId==null,onClick={
                                markingId=item.id;error=null
                                AuthPreferences.getFirebaseIdToken(context) { result -> result.fold(
                                    onSuccess={token->scope.launch {
                                        try {
                                            withContext(Dispatchers.IO){DarsyarCommunityApiFactory.create().markRead(token,item.id)}
                                            notifications=notifications.map { n -> if(n.id==item.id) n.copy(isRead=true) else n }
                                            onUnreadCountChanged(notifications.count { !it.isRead })
                                        } catch(e:Exception) {
                                            error=e.localizedMessage ?: "علامت‌گذاری اعلان ناموفق بود"
                                        } finally { markingId=null }
                                    }},
                                    onFailure={error=it.localizedMessage ?: "دریافت توکن ناموفق بود";markingId=null}
                                ) }
                            }) { Text(if(markingId==item.id) "در حال ثبت…" else "خواندم") }
                        }
                    }
                }
                if(notifications.size>50) Text("۵۰ اعلان اخیر نمایش داده شده است.",style=MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton={TextButton(onClick=onDismiss){Text("بستن")}}
    )
}
