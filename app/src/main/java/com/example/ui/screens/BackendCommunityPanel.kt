package com.example.ui.screens

import androidx.compose.foundation.layout.*
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
fun BackendForumPanel(classId:String, sessionId:String?, canModerate:Boolean, onStatus:(String)->Unit) {
    val context=LocalContext.current
    val scope= rememberCoroutineScope()
    var topics by remember(classId) { mutableStateOf<List<BackendForumTopicDto>>(emptyList()) }
    var selected by remember { mutableStateOf<BackendForumTopicDto?>(null) }
    var posts by remember { mutableStateOf<List<BackendForumPostDto>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf<String?>(null) }
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
    LaunchedEffect(classId) { run { token,repo -> topics=repo.topics(token,classId) } }

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
            Text(topic.body,style=MaterialTheme.typography.bodyMedium)
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
                Card(Modifier.fillMaxWidth().padding(start=if(post.parentPostId==null) 0.dp else 18.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(post.authorName,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.labelMedium)
                        Text(post.body,style=MaterialTheme.typography.bodySmall)
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
}

@Composable
fun BackendNotificationsDialog(onDismiss:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var notifications by remember { mutableStateOf<List<BackendNotificationDto>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        AuthPreferences.getFirebaseIdToken(context) { result ->
            result.fold(onSuccess={token->scope.launch {
                try { notifications=withContext(Dispatchers.IO){DarsyarCommunityApiFactory.create().notifications(token)} }
                catch(e:Exception){error=e.localizedMessage ?: "دریافت اعلان‌ها ناموفق بود"}
                finally {busy=false}
            }},onFailure={error=it.localizedMessage ?: "دریافت توکن ناموفق بود";busy=false})
        }
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("اعلان‌ها")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(busy) CircularProgressIndicator()
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(!busy && notifications.isEmpty()) Text("اعلانی ندارید.")
            notifications.take(30).forEach { item ->
                Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(item.isRead) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(item.message,style=MaterialTheme.typography.bodyMedium)
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.createdAtMs)),style=MaterialTheme.typography.labelSmall)
                        if(!item.isRead) TextButton(onClick={
                            AuthPreferences.getFirebaseIdToken(context) { result -> result.onSuccess { token -> scope.launch {
                                runCatching { withContext(Dispatchers.IO){DarsyarCommunityApiFactory.create().markRead(token,item.id)} }
                                notifications=notifications.map { n -> if(n.id==item.id) n.copy(isRead=true) else n }
                            } } }
                        }) { Text("خواندم") }
                    }
                }
            }
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("بستن")}})
}
