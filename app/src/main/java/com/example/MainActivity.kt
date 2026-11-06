package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.ui.screens.BackendBrowseScreen
import com.example.ui.screens.PublicCatalogScreen
import com.example.ui.screens.AuthPreferences
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ClassScreen
import com.example.ui.screens.TeacherFoldersScreen
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
import java.io.PrintWriter
import java.io.StringWriter

private const val CRASH_PREFS = "darsyar_crash_diagnostics"
private const val LAST_CRASH_KEY = "last_crash"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashDiagnostics()
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

    private fun installCrashDiagnostics() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = StringWriter().also { writer ->
                    throwable.printStackTrace(PrintWriter(writer))
                }.toString()
                getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(LAST_CRASH_KEY, trace.takeLast(16_000))
                    .commit()
                Log.e("DarsyarCrash", "Uncaught exception on thread ${thread.name}", throwable)
            } catch (diagnosticError: Throwable) {
                Log.e("DarsyarCrash", "Could not persist crash diagnostics", diagnosticError)
            }

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }
}

@Composable
fun OstadYarApp(
    viewModel: MainViewModel = viewModel(),
    onThemeChanged: (AppThemeMode) -> Unit = {}
) {
    val context = LocalContext.current
    val crashPrefs = remember(context) {
        context.getSharedPreferences(CRASH_PREFS, android.content.Context.MODE_PRIVATE)
    }
    var previousCrash by remember {
        mutableStateOf(crashPrefs.getString(LAST_CRASH_KEY, null))
    }

    if (!previousCrash.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = {
                crashPrefs.edit().remove(LAST_CRASH_KEY).apply()
                previousCrash = null
            },
            title = { Text("گزارش بسته‌شدن قبلی برنامه") },
            text = {
                Text("یک خطای ثبت‌نشده باعث بسته‌شدن برنامه شده است. گزارش فنی ذخیره شده؛ آن را کپی کنید و در همین گفتگو بفرستید تا علت دقیق بررسی شود.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("گزارش خطای درس‌یار", previousCrash.orEmpty())
                    )
                    crashPrefs.edit().remove(LAST_CRASH_KEY).apply()
                    previousCrash = null
                }) {
                    Text("کپی گزارش و بستن")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    crashPrefs.edit().remove(LAST_CRASH_KEY).apply()
                    previousCrash = null
                }) {
                    Text("فعلاً نه")
                }
            }
        )
    }

    var authenticated by remember { mutableStateOf(AuthPreferences.isLoggedIn(context)) }
    var guestBrowsing by remember { mutableStateOf(false) }
    var userRole by remember { mutableStateOf(AuthPreferences.currentRole(context)) }
    val currentScreen by viewModel.currentScreen.collectAsState()
    var pendingSearchQuery by remember { mutableStateOf("") }
    var pendingSearchMatchMode by remember { mutableStateOf("all") }
    LaunchedEffect(currentScreen) {
        if (currentScreen !is Screen.Detail) {
            pendingSearchQuery = ""
            pendingSearchMatchMode = "all"
        }
    }

    LaunchedEffect(authenticated) {
        if (authenticated) {
            AuthPreferences.refreshProfile(context) { result ->
                result.onSuccess { role ->
                    userRole = role
                    viewModel.setUserRole(role)
                    if (role == UserRole.TEACHER) {
                        viewModel.startCloudSync()
                    } else {
                        // دانشجو هنگام ورود، مطالب منتشرشده را خودکار دریافت می‌کند.
                        viewModel.startStudentMaterialsSync()
                    }
                }.onFailure {
                    // اگر مجوز استاد لغو شده یا قابل تأیید نباشد، نشست قبلی نباید
                    // با نقش ذخیره‌شده محلی اجازه ساخت/ویرایش بدهد.
                    AuthPreferences.logout(context)
                    authenticated = false
                    userRole = UserRole.STUDENT
                    viewModel.setUserRole(UserRole.STUDENT)
                    viewModel.navigateTo(Screen.Home)
                }
            }
        }
    }

    if (guestBrowsing && !authenticated) {
        BackHandler(enabled = true) { guestBrowsing = false }
        PublicCatalogScreen(
            onBack = { guestBrowsing = false },
            onLogin = { guestBrowsing = false }
        )
        return
    }

    if (!authenticated) {
        BackHandler(enabled = false) { }
        AuthScreen(
            onBrowsePublic = { guestBrowsing = true },
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
                onNavigateToSearchResult = { lectureId, query, matchMode ->
                    pendingSearchQuery = query
                    pendingSearchMatchMode = matchMode
                    viewModel.navigateTo(Screen.Detail(lectureId))
                },
                onNavigateToAiSummary = { lectureId -> viewModel.navigateTo(Screen.Detail(lectureId)) },
                onNavigateToBackendBrowse = { viewModel.navigateTo(Screen.BackendBrowse) }
            )
        }

        Screen.Teachers -> {
            TeacherFoldersScreen(
                viewModel = viewModel,
                userRole = userRole,
                onOpenTeacher = { key, name -> viewModel.openTeacherClasses(key, name) },
                onBack = { viewModel.navigateBack() }
            )
        }

        is Screen.TeacherClasses -> {
            ClassScreen(
                viewModel = viewModel,
                userRole = userRole,
                teacherKey = screen.teacherKey,
                teacherLabel = screen.teacherName,
                onOpenClass = { id -> viewModel.openClass(id, screen.teacherKey, screen.teacherName) },
                onBack = { viewModel.navigateBack() }
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
                onNavigateBack = { pendingSearchQuery = ""; pendingSearchMatchMode = "all"; viewModel.navigateBack() },
                onNavigateToEdit = { editId ->
                    if (userRole == UserRole.TEACHER) viewModel.navigateTo(Screen.AddEdit(editId))
                },
                isTeacher = userRole == UserRole.TEACHER,
                initialSearchQuery = pendingSearchQuery,
                initialSearchMatchMode = pendingSearchMatchMode
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

        Screen.BackendBrowse -> {
            BackendBrowseScreen(onBack = { viewModel.navigateBack() })
        }

        Screen.Settings -> {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { viewModel.navigateBack() },
                currentTheme = ThemePreferences.load(LocalContext.current),
                onThemeChanged = onThemeChanged,
                onLogout = {
                    AuthPreferences.logout(context)
                    authenticated = false
                    userRole = UserRole.STUDENT
                    viewModel.setUserRole(UserRole.STUDENT)
                    viewModel.navigateTo(Screen.Home)
                }
            )
        }
    }
}
