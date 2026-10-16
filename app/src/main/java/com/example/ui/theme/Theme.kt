package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun MyApplicationTheme(
    appTheme: AppThemeMode = AppThemeMode.PURPLE,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val lightScheme = when (appTheme) {
        AppThemeMode.PURPLE -> lightColorScheme(
            primary = Color(0xFF1C2F4A),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD9E1EA),
            onPrimaryContainer = Color(0xFF24344D),
            secondary = Color(0xFFC58F2D),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFF0DFC0),
            onSecondaryContainer = Color(0xFF4A3515),
            tertiary = Color(0xFFB58A3A),
            onTertiary = Color.White,
            background = BackgroundLight,
            onBackground = OnBackgroundLight,
            surface = SurfaceLight,
            onSurface = OnSurfaceLight,
            surfaceVariant = SurfaceVariantLight,
            onSurfaceVariant = OnSurfaceVariantLight,
            outline = Color(0xFFBDB6A9),
            outlineVariant = Color(0xFFDDD7CB)
        )
        AppThemeMode.BLUE -> lightColorScheme(
            primary = Color(0xFF2457A7),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCE8FF),
            onPrimaryContainer = Color(0xFF17345F),
            secondary = Color(0xFF4979B8),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE2ECF8),
            onSecondaryContainer = Color(0xFF243B58),
            tertiary = Color(0xFF267D86),
            onTertiary = Color.White,
            background = Color(0xFFF0F5FC),
            onBackground = Color(0xFF1D2B40),
            surface = Color(0xFFFAFCFF),
            onSurface = Color(0xFF1D2B40),
            surfaceVariant = Color(0xFFE3EAF4),
            onSurfaceVariant = Color(0xFF4C5D73),
            outline = Color(0xFFB8C6D8),
            outlineVariant = Color(0xFFD2DDEB)
        )
        AppThemeMode.GREEN -> lightColorScheme(
            primary = Color(0xFF286448),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD4EBDD),
            onPrimaryContainer = Color(0xFF173F2C),
            secondary = Color(0xFF56845D),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE1F0DF),
            onSecondaryContainer = Color(0xFF29472D),
            tertiary = Color(0xFF8A6D32),
            onTertiary = Color.White,
            background = Color(0xFFF0F6EF),
            onBackground = Color(0xFF24382A),
            surface = Color(0xFFFBFDF9),
            onSurface = Color(0xFF24382A),
            surfaceVariant = Color(0xFFE2EBDD),
            onSurfaceVariant = Color(0xFF506353),
            outline = Color(0xFFB9C9B8),
            outlineVariant = Color(0xFFD5E0D2)
        )
        AppThemeMode.ORANGE -> lightColorScheme(
            primary = Color(0xFF9A481A),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFE0CC),
            onPrimaryContainer = Color(0xFF60290B),
            secondary = Color(0xFFC36B2B),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFFFE9D8),
            onSecondaryContainer = Color(0xFF5C3217),
            tertiary = Color(0xFF8C5D28),
            onTertiary = Color.White,
            background = Color(0xFFFFF5ED),
            onBackground = Color(0xFF3E2B20),
            surface = Color(0xFFFFFCF9),
            onSurface = Color(0xFF3E2B20),
            surfaceVariant = Color(0xFFF4E4D7),
            onSurfaceVariant = Color(0xFF705849),
            outline = Color(0xFFD0B6A2),
            outlineVariant = Color(0xFFE8D4C4)
        )
    }

    val darkScheme = when (appTheme) {
        AppThemeMode.PURPLE -> darkColorScheme(
            primary = Color(0xFFB9C6D9),
            onPrimary = Color(0xFF182638),
            primaryContainer = Color(0xFF34465F),
            onPrimaryContainer = Color(0xFFF7F4EC),
            secondary = Color(0xFFD8B45A),
            onSecondary = Color(0xFF30220A),
            tertiary = Color(0xFFE0C27A),
            onTertiary = Color(0xFF30220A),
            background = BackgroundDark,
            onBackground = OnBackgroundDark,
            surface = SurfaceDark,
            onSurface = OnSurfaceDark,
            surfaceVariant = SurfaceVariantDark,
            onSurfaceVariant = OnSurfaceVariantDark
        )
        AppThemeMode.BLUE -> darkColorScheme(
            primary = Color(0xFFB6CCFF),
            onPrimary = Color(0xFF16345E),
            primaryContainer = Color(0xFF244A7D),
            onPrimaryContainer = Color(0xFFDCE8FF),
            secondary = Color(0xFFA9C7F0),
            onSecondary = Color(0xFF203C60),
            tertiary = Color(0xFF8ED7D9),
            onTertiary = Color(0xFF123F43),
            background = Color(0xFF101A29),
            onBackground = Color(0xFFEAF1FC),
            surface = Color(0xFF182538),
            onSurface = Color(0xFFEAF1FC),
            surfaceVariant = Color(0xFF29384D),
            onSurfaceVariant = Color(0xFFC1CDDF)
        )
        AppThemeMode.GREEN -> darkColorScheme(
            primary = Color(0xFFA7D8B8),
            onPrimary = Color(0xFF143D29),
            primaryContainer = Color(0xFF28553C),
            onPrimaryContainer = Color(0xFFD4EBDD),
            secondary = Color(0xFFB5D7A9),
            onSecondary = Color(0xFF29472D),
            tertiary = Color(0xFFE3C784),
            onTertiary = Color(0xFF493710),
            background = Color(0xFF111D17),
            onBackground = Color(0xFFEAF3EB),
            surface = Color(0xFF1A2A20),
            onSurface = Color(0xFFEAF3EB),
            surfaceVariant = Color(0xFF2A3B30),
            onSurfaceVariant = Color(0xFFC4D5C7)
        )
        AppThemeMode.ORANGE -> darkColorScheme(
            primary = Color(0xFFFFB98A),
            onPrimary = Color(0xFF5A2A0C),
            primaryContainer = Color(0xFF793C17),
            onPrimaryContainer = Color(0xFFFFE0CC),
            secondary = Color(0xFFFFC49A),
            onSecondary = Color(0xFF5C3217),
            tertiary = Color(0xFFE8C28E),
            onTertiary = Color(0xFF4A3214),
            background = Color(0xFF211710),
            onBackground = Color(0xFFFFF0E4),
            surface = Color(0xFF302219),
            onSurface = Color(0xFFFFF0E4),
            surfaceVariant = Color(0xFF493427),
            onSurfaceVariant = Color(0xFFE2C7B4)
        )
    }

    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme else lightScheme,
        typography = Typography,
        content = content
    )
}
