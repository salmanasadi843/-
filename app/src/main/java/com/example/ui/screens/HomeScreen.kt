package com.example.ui.screens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel,userRole: UserRole,onNavigateToAdd:()->Unit,onNavigateToDetail:(Long)->Unit,onNavigateToAiSummary:(Long)->Unit){
 val classes by viewModel.repository.allClasses.collectAsState(initial=emptyList())
 val lectures by viewModel.lectures.collectAsState()
 val query by viewModel.searchQuery.collectAsState()
 val recent=lectures.filter{query.isBlank()||it.title.contains(query,true)||it.transcript.contains(query,true)||it.tags.contains(query,true)}.take(5)
 Scaffold(topBar={TopAppBar(title={Column{Text("درس‌یار",fontWeight=FontWeight.Bold);Text(if(userRole==UserRole.TEACHER)"مدیریت کلاس و محتوای آموزشی" else "مطالعه کلاس‌ها و جلسات",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},actions={
  Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.secondaryContainer){Text(if(userRole==UserRole.TEACHER)"استاد" else "شاگرد",Modifier.padding(horizontal=10.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium)}
  IconButton(onClick={viewModel::openClasses}){Icon(Icons.Default.School,"کلاس‌ها")}
  IconButton(onClick={ { viewModel.navigateTo(Screen.Settings) } }){Icon(Icons.Default.Settings,"تنظیمات")}
 })},floatingActionButton={FloatingActionButton(onClick={viewModel::openClasses}){Icon(Icons.Default.School,"کلاس‌ها")}}){p->
  LazyColumn(Modifier.fillMaxSize().padding(p),contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{OutlinedTextField(query,viewModel::setSearchQuery,Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("جستجو در کلاس، جلسه و متن درس")},leadingIcon={Icon(Icons.Default.Search,null)},shape=RoundedCornerShape(14.dp))}
   item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("کلاس‌های من",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(classes.size.toString()+" کلاس",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
   if(classes.isEmpty()) item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.School,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(10.dp));Text(if(userRole==UserRole.TEACHER)"هنوز کلاسی ایجاد نشده است." else "هنوز کلاسی برای شما ثبت نشده است.",fontWeight=FontWeight.Bold);Spacer(Modifier.height(6.dp));Text(if(userRole==UserRole.TEACHER)"از بخش کلاس‌ها، کلاس جدید بسازید." else "بعد از اضافه شدن کلاس، درس‌ها و جلسات اینجا نمایش داده می‌شوند.",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
   else items(classes,key={it.id}){item->Card(Modifier.fillMaxWidth().clickable{viewModel.openClass(item.id)}){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(46.dp),RoundedCornerShape(13.dp),color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){Icon(Icons.Default.School,null,tint=MaterialTheme.colorScheme.primary)}};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(item.name,fontWeight=FontWeight.Bold);if(item.teacherName.isNotBlank())Text("استاد: "+item.teacherName,style=MaterialTheme.typography.bodySmall);if(item.term.isNotBlank())Text(item.term,style=MaterialTheme.typography.labelSmall)}}}}
   item{Spacer(Modifier.height(8.dp));Text("آخرین جلسات",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
   if(recent.isEmpty())item{Text("هنوز جلسه‌ای برای نمایش وجود ندارد.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
   else items(recent,key={it.id}){lecture->Card(Modifier.fillMaxWidth().clickable{onNavigateToDetail(lecture.id)}){Column(Modifier.padding(14.dp)){Text(lecture.title,fontWeight=FontWeight.Bold);Text(PersianDateUtils.format(lecture.dateMillis),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(lecture.tags.isNotBlank())Text(lecture.tags,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}}}}
   item{Spacer(Modifier.height(72.dp))}
  }
 }
}
