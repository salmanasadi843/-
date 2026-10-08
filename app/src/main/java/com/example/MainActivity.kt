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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.AddEditLectureScreen
import com.example.ui.screens.ClassScreen
import com.example.ui.screens.ClassSessionsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LectureDetailScreen
import com.example.ui.screens.MainViewModel
import com.example.ui.screens.Screen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.UserRole
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemePreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AuthPreferences.attach(this)
        setContent {
            var appTheme by remember {
                mutableStateOf(ThemePreferences.load(this@MainActivity))
            }

            MyApplicationTheme(appTheme = appTheme) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        OstadYarApp(
                            onThemeChanged = { mode ->
                                appTheme = mode
                                ThemePreferences.save(this@MainActivity, mode)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OstadYarApp(
    viewModel: MainViewModel = viewModel(),
    onThemeChanged: (AppThemeMode) -> Unit = {}
) {
    var authenticated by remember { mutableStateOf(AuthPreferences.isLoggedIn(LocalContext.current)) }
    var userRole by remember { mutableStateOf(AuthPreferences.currentRole(LocalContext.current)) }
    val currentScreen by viewModel.currentScreen.collectAsState()

    if (!authenticated) {
        BackHandler(enabled = false) { }
        AuthScreen(
            onAuthenticated = { role ->
                userRole = role
                viewModel.setUserRole(role)
                authenticated = true
            }
        )
        return
    }

    BackHandler(enabled = true) {
        viewModel.navigateBack()
    }

    when (val screen = currentScreen) {
        is Screen.Home -> {
            HomeScreen(
                viewModel = viewModel,
                userRole = userRole,
                onNavigateToAdd = { viewModel.openClasses() },
                onNavigateToDetail = { lectureId -> viewModel.navigateTo(Screen.Detail(lectureId)) },
                onNavigateToAiSummary = { lectureId -> viewModel.navigateTo(Screen.Detail(lectureId)) }
            )
        }

        Screen.Classes -> {
            ClassScreen(
                viewModel = viewModel,
                userRole = userRole,
                onOpenClass = { id -> viewModel.openClass(id) },
                onBack = { viewModel.navigateBack() }
            )
        }

        is Screen.ClassDetail -> {
            ClassSessionsScreen(
                classId = screen.classId,
                viewModel = viewModel,
                userRole = userRole,
                onOpenSession = { id -> viewModel.navigateTo(Screen.Detail(id)) },
                onNewSession = { viewModel.navigateTo(Screen.AddEdit(null, screen.classId)) },
                onBack = { viewModel.navigateBack() }
            )
        }

        is Screen.Detail -> {
            LectureDetailScreen(
                lectureId = screen.lectureId,
                viewModel = viewModel,
                onNavigateBack = { viewModel.navigateBack() },
                onNavigateToEdit = { editId ->
                    if (userRole == UserRole.TEACHER) viewModel.navigateTo(Screen.AddEdit(editId))
                },
                isTeacher = userRole == UserRole.TEACHER
            )
        }

        is Screen.AddEdit -> {
            if (userRole == UserRole.TEACHER) {
                AddEditLectureScreen(
                    lectureId = screen.lectureId,
                    viewModel = viewModel,
                    onNavigateBack = { viewModel.navigateBack() },
                    onSaved = { savedId -> viewModel.navigateTo(Screen.Detail(savedId)) }
                )
            } else {
                viewModel.navigateTo(Screen.Home)
            }
        }

        is Screen.AiStudy -> {
            LectureDetailScreen(
                lectureId = screen.lectureId,
                viewModel = viewModel,
                onNavigateBack = { viewModel.navigateBack() },
                onNavigateToEdit = { editId ->
                    if (userRole == UserRole.TEACHER) viewModel.navigateTo(Screen.AddEdit(editId))
                }
            )
        }

        Screen.Settings -> {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { viewModel.navigateBack() },
                currentTheme = ThemePreferences.load(LocalContext.current),
                onThemeChanged = onThemeChanged
            )
        }
    }
}
