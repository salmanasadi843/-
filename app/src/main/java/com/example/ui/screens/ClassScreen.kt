package com.example.ui.screens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.ClassEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassScreen(viewModel:MainViewModel,userRole:UserRole,onOpenClass:(Long)->Unit,onBack:()->Unit){
 val classes by viewModel.repository.allClasses.collectAsState(initial=emptyList())
 var showAdd by remember{mutableStateOf(false)}
 var name by remember{mutableStateOf("")}
 var teacher by remember{mutableStateOf("")}
 var term by remember{mutableStateOf("")}
 Scaffold(topBar={TopAppBar(title={Text("کلاس‌های من",fontWeight=FontWeight.Bold)},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"بازگشت")}})},floatingActionButton={if(userRole==UserRole.TEACHER)FloatingActionButton(onClick={showAdd=true}){Icon(Icons.Default.Add,"کلاس جدید")}}){p->
  if(classes.isEmpty())Box(Modifier.fillMaxSize().padding(p),contentAlignment=Alignment.Center){Text(if(userRole==UserRole.TEACHER)"هنوز کلاسی ایجاد نشده است." else "هنوز کلاسی برای مطالعه وجود ندارد.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
  else LazyColumn(Modifier.fillMaxSize().padding(p),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   items(classes,key={it.id}){item->Card(Modifier.fillMaxWidth().clickable{onOpenClass(item.id)}){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.School,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(42.dp));Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(item.name,fontWeight=FontWeight.Bold);if(item.teacherName.isNotBlank())Text("استاد: "+item.teacherName,style=MaterialTheme.typography.bodySmall);if(item.term.isNotBlank())Text(item.term,style=MaterialTheme.typography.labelSmall)}}}}
  }
 }
 if(showAdd)AlertDialog(onDismissRequest={showAdd=false},title={Text("ایجاد کلاس جدید")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(name,{name=it},label={Text("نام کلاس")},singleLine=true);OutlinedTextField(teacher,{teacher=it},label={Text("نام استاد")},singleLine=true);OutlinedTextField(term,{term=it},label={Text("ترم / نیمسال")},singleLine=true)}},confirmButton={TextButton(enabled=name.isNotBlank(),onClick={viewModel.saveClass(ClassEntity(name=name.trim(),teacherName=teacher.trim(),term=term.trim()));name="";teacher="";term="";showAdd=false}){Text("ذخیره")}},dismissButton={TextButton(onClick={showAdd=false}){Text("انصراف")}})
}
