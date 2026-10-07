package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.LectureEntity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel,userRole: UserRole,onNavigateToAdd:()->Unit,onNavigateToDetail:(Long)->Unit,onNavigateToAiSummary:(Long)->Unit){
 val classes by viewModel.repository.allClasses.collectAsState(initial=emptyList())
 val lectures by viewModel.lectures.collectAsState()
 val query by viewModel.searchQuery.collectAsState()
 Scaffold(topBar={TopAppBar(title={Column{Text("درس‌یار",fontWeight=FontWeight.Bold);Text(if(userRole==UserRole.TEACHER)"مدیریت کلاس و محتوای آموزشی" else "مطالعه کلاس‌ها و جلسات",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},actions={
  Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.secondaryContainer){Text(if(userRole==UserRole.TEACHER)"استاد" else "شاگرد",Modifier.padding(horizontal=10.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium)}
  IconButton(onClick={viewModel::openClasses}){Icon(Icons.Default.School,"کلاس‌ها")};IconButton(onClick={{viewModel.navigateTo(Screen.Settings)}}){Icon(Icons.Default.Settings,"تنظیمات")}
 })},floatingActionButton={FloatingActionButton(onClick=viewModel::openClasses){Icon(Icons.Default.School,"کلاس‌ها")}}){p->
  LazyColumn(Modifier.fillMaxSize().padding(p),contentPadding=PaddingValues(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item{OutlinedTextField(query,viewModel::setSearchQuery,Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("جستجو در کلاس، جلسه و متن درس")},leadingIcon={Icon(Icons.Default.Search,null)},shape=RoundedCornerShape(14.dp))}
   item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("کلاس‌های من",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("${classes.size} کلاس",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
   if(classes.isEmpty()) item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.School,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(10.dp));Text(if(userRole==UserRole.TEACHER)"هنوز کلاسی ایجاد نشده است." else "هنوز کلاسی برای شما ثبت نشده است.",fontWeight=FontWeight.Bold);Spacer(Modifier.height(6.dp));Text(if(userRole==UserRole.TEACHER)"از دکمه کلاس‌ها وارد شوید و کلاس جدید بسازید." else "بعد از اضافه شدن کلاس، درس‌ها و جلسات اینجا نمایش داده می‌شوند.",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
   else items(classes,key={it.id}){item->Card(Modifier.fillMaxWidth().clickable{viewModel.openClass(item.id)}){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(46.dp),RoundedCornerShape(13.dp),color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){Icon(Icons.Default.School,null,tint=MaterialTheme.colorScheme.primary)}};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(item.name,fontWeight=FontWeight.Bold);if(item.teacherName.isNotBlank())Text("استاد: ${item.teacherName}",style=MaterialTheme.typography.bodySmall);if(item.term.isNotBlank())Text(item.term,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}
   item{Spacer(Modifier.height(8.dp));Text("آخرین جلسات",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
   val recent=lectures.filter{query.isBlank()||it.title.contains(query,true)||it.transcript.contains(query,true)||it.tags.contains(query,true)}.take(5)
   if(recent.isEmpty())item{Text("هنوز جلسه‌ای برای نمایش وجود ندارد.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
   else items(recent,key={it.id}){lecture->Card(Modifier.fillMaxWidth().clickable{onNavigateToDetail(lecture.id)}){Column(Modifier.padding(14.dp)){Text(lecture.title,fontWeight=FontWeight.Bold);Text(PersianDateUtils.format(lecture.dateMillis),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(lecture.tags.isNotBlank())Text(lecture.tags,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,maxLines=1)}}}}
   item{Spacer(Modifier.height(72.dp))}
  }
 }
}


@Composable
private fun AiActionsCard(
    onSummary: () -> Unit,
    onNewLesson: (() -> Unit)?
) {

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            1.dp
        )
    ) {

        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            FilledTonalButton(
                onClick = onSummary,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(13.dp)
            ) {

                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(
                    modifier = Modifier.width(5.dp)
                )

                Text("خلاصه هوشمند")
            }

            if (onNewLesson != null) {
                OutlinedButton(
                    onClick = onNewLesson,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(
                        modifier = Modifier.width(5.dp)
                    )
                    Text("درس جدید")
                }
            }
        }
    }
}


@Composable
private fun CourseCard(
    courseName: String,
    sessionCount: Int,
    professorName: String,
    onClick: () -> Unit
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),

        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        ),

        elevation = CardDefaults.cardElevation(
            1.dp
        )
    ) {

        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Surface(
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(13.dp),
                color =
                    MaterialTheme.colorScheme.primaryContainer
            ) {

                Box(
                    contentAlignment =
                        Alignment.Center
                ) {

                    Icon(
                        Icons.Default.School,
                        contentDescription = null,
                        tint =
                            MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(
                modifier = Modifier.width(12.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    courseName,
                    fontWeight = FontWeight.Bold,
                    style =
                        MaterialTheme.typography.titleSmall
                )

                if (professorName.isNotBlank()) {

                    Spacer(
                        modifier = Modifier.height(3.dp)
                    )

                    Text(
                        "استاد: $professorName",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color =
                    MaterialTheme.colorScheme.surfaceVariant
            ) {

                Text(
                    "$sessionCount جلسه",

                    modifier = Modifier.padding(
                        horizontal = 9.dp,
                        vertical = 6.dp
                    ),

                    style =
                        MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}


@Composable
private fun SessionCard(
    lecture: LectureEntity,
    playableAudio: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),

        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        ),

        elevation = CardDefaults.cardElevation(
            1.dp
        )
    ) {

        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Surface(
                modifier = Modifier.size(42.dp),

                shape = RoundedCornerShape(12.dp),

                color =
                    if (!playableAudio.isNullOrBlank()) {
                        MaterialTheme.colorScheme
                            .primaryContainer
                    } else {
                        MaterialTheme.colorScheme
                            .surfaceVariant
                    }
            ) {

                Box(
                    contentAlignment =
                        Alignment.Center
                ) {

                    IconButton(
                        onClick = onPlay,
                        enabled =
                            !playableAudio.isNullOrBlank()
                    ) {

                        Icon(
                            if (
                                !playableAudio.isNullOrBlank()
                            ) {
                                Icons.Default.PlayArrow
                            } else {
                                Icons.Default.GraphicEq
                            },

                            contentDescription = "پخش صوت",

                            tint =
                                MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier.width(12.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    lecture.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow =
                        TextOverflow.Ellipsis
                )

                Spacer(
                    modifier = Modifier.height(4.dp)
                )

                Text(
                    "${PersianDateUtils.format(lecture.dateMillis)}" +
                        if (
                            lecture.professorName.isNotBlank()
                        ) {
                            "  •  ${lecture.professorName}"
                        } else {
                            ""
                        },

                    style =
                        MaterialTheme.typography.bodySmall,

                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant
                )

                if (lecture.tags.isNotBlank()) {

                    Spacer(
                        modifier = Modifier.height(4.dp)
                    )

                    Text(
                        lecture.tags,

                        style =
                            MaterialTheme.typography.labelSmall,

                        color =
                            MaterialTheme.colorScheme.primary,

                        maxLines = 1,

                        overflow =
                            TextOverflow.Ellipsis
                    )
                }
            }

            if (isPlaying) {

                Text(
                    "در حال پخش",

                    style =
                        MaterialTheme.typography.labelSmall,

                    color =
                        MaterialTheme.colorScheme.primary,

                    fontWeight =
                        FontWeight.Bold
                )
            }
        }
    }
}


@Composable
private fun EmptyState(
    title: String,
    text: String
) {

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment =
            Alignment.Center
    ) {

        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally,

            modifier = Modifier.padding(32.dp)
        ) {

            Surface(
                modifier = Modifier.size(64.dp),

                shape = RoundedCornerShape(18.dp),

                color =
                    MaterialTheme.colorScheme
                        .primaryContainer
            ) {

                Box(
                    contentAlignment =
                        Alignment.Center
                ) {

                    Icon(
                        Icons.Default.School,
                        contentDescription = null,

                        tint =
                            MaterialTheme.colorScheme.primary,

                        modifier =
                            Modifier.size(30.dp)
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            Text(
                title,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            Text(
                text,

                style =
                    MaterialTheme.typography.bodySmall,

                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant
            )
        }
    }
}
