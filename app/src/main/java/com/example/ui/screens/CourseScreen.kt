package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.CourseEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseScreen(classId:Long,viewModel:MainViewModel,userRole:UserRole,onOpenCourse:(Long)->Unit,onBack:()->Unit){
 val courses by viewModel.repository.coursesForClass(classId).collectAsState(initial=emptyList())
 var className by remember{mutableStateOf("کلاس")}
 LaunchedEffect(classId){className=viewModel.repository.getClass(classId)?.name ?: "کلاس"}
 Scaffold(topBar={TopAppBar(title={Column{Text(className,fontWeight=FontWeight.Bold);Text("درس‌های این کلاس",style=MaterialTheme.typography.bodySmall)}},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"بازگشت")}})}){p->
  LazyColumn(Modifier.fillMaxSize().padding(p),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   if(courses.isEmpty())item{Text("هنوز درسی برای این کلاس ثبت نشده است.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
   items(courses,key={it.id}){course->Card(Modifier.fillMaxWidth().clickable{onOpenCourse(course.id)}){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
    Icon(Icons.Default.MenuBook,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(40.dp));Spacer(Modifier.width(12.dp));Column{Text(course.name,fontWeight=FontWeight.Bold);if(course.teacherName.isNotBlank())Text("استاد: ${course.teacherName}",style=MaterialTheme.typography.bodySmall);if(course.description.isNotBlank())Text(course.description,style=MaterialTheme.typography.bodySmall)}
   }}}
  }
 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(courseId:Long,viewModel:MainViewModel,onOpenSession:(Long)->Unit,onBack:()->Unit){
 val allLectures by viewModel.repository.allLectures.collectAsState(initial=emptyList())
 var course by remember{mutableStateOf<CourseEntity?>(null)}
 LaunchedEffect(courseId){course=viewModel.repository.getCourse(courseId)}
 val lectures = allLectures.filter { it.courseId == courseId || (course?.name?.isNotBlank() == true && it.courseName.equals(course?.name, ignoreCase = true)) }
 val title = course?.name ?: "درس"
 Scaffold(topBar={TopAppBar(title={Text(title,fontWeight=FontWeight.Bold)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"بازگشت")}})}){p->
  LazyColumn(Modifier.fillMaxSize().padding(p),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   item{Text("جلسات",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
   if(lectures.isEmpty())item{Text("هنوز جلسه‌ای به این درس متصل نشده است.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
   items(lectures,key={it.id}){lecture->Card(Modifier.fillMaxWidth().clickable{onOpenSession(lecture.id)}){Column(Modifier.padding(16.dp)){Text(lecture.title,fontWeight=FontWeight.Bold);Text(PersianDateUtils.format(lecture.dateMillis),style=MaterialTheme.typography.bodySmall);if(lecture.tags.isNotBlank())Text(lecture.tags,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}}}
  }
 }
}
