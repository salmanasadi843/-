package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.AddEditLectureScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ClassScreen
import com.example.ui.screens.CourseScreen
import com.example.ui.screens.CourseDetailScreen
import com.example.ui.screens.LectureDetailScreen
import com.example.ui.screens.MainViewModel
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.Screen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                // Persian is an RTL language - provide RTL layout direction for authentic typography and alignment
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        OstadYarApp()
                    }
                }
            }
        }
    }
}

@Composable
fun OstadYarApp(viewModel: MainViewModel = viewModel()) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val userRole by viewModel.userRole.collectAsState()

    // Handle system back button properly
    BackHandler(enabled = currentScreen !is Screen.Home) {
        viewModel.navigateBack()
    }

    when (val screen = currentScreen) {
        is Screen.Home -> {
            HomeScreen(
                viewModel = viewModel,
                userRole = userRole,
                onNavigateToAdd = {
                    viewModel.navigateTo(Screen.AddEdit(null))
                },
                onNavigateToDetail = { lectureId ->
                    viewModel.navigateTo(Screen.Detail(lectureId))
                },
                onNavigateToAiSummary = { lectureId ->
                    viewModel.navigateTo(Screen.Detail(lectureId))
                }
            )
        }
        Screen.Classes -> {
            ClassScreen(viewModel,userRole,{id->viewModel.openClass(id)},{viewModel.navigateBack()})
        }
        is Screen.ClassDetail -> {
            CourseScreen(screen.classId,viewModel,userRole,{id->viewModel.openCourse(id)},{viewModel.navigateBack()})
        }
        is Screen.CourseDetail -> {
            CourseDetailScreen(screen.courseId,viewModel,{id->viewModel.navigateTo(Screen.Detail(id))},{viewModel.navigateBack()})
        }
        is Screen.Detail -> {
            LectureDetailScreen(
                lectureId = screen.lectureId,
                viewModel = viewModel,
                onNavigateBack = {
                    viewModel.navigateBack()
                },
                onNavigateToEdit = { editId ->
                    if (userRole == com.example.ui.screens.UserRole.TEACHER) viewModel.navigateTo(Screen.AddEdit(editId))
                },
                isTeacher = userRole == com.example.ui.screens.UserRole.TEACHER,
            )
        }
        is Screen.AddEdit -> {
            if (userRole == com.example.ui.screens.UserRole.TEACHER) {
                AddEditLectureScreen(
                    lectureId = screen.lectureId,
                    viewModel = viewModel,
                    onNavigateBack = {
                        viewModel.navigateBack()
                    },
                    onSaved = { savedId ->
                        viewModel.navigateTo(Screen.Detail(savedId))
                    }
                )
            } else {
                viewModel.navigateTo(Screen.Home)
            }
        }
        is Screen.AiStudy -> {
            LectureDetailScreen(
                lectureId = screen.lectureId,
                viewModel = viewModel,
                onNavigateBack = {
                    viewModel.navigateBack()
                },
                onNavigateToEdit = { editId ->
                    if (userRole == com.example.ui.screens.UserRole.TEACHER) {
                        viewModel.navigateTo(Screen.AddEdit(editId))
                    }
                }
            )
        }
        Screen.Settings -> {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    viewModel.navigateBack()
                }
            )
        }
    }
}
