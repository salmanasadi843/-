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
    val lightScheme = lightColorScheme(
        primary = PurplePrimaryLight,
        onPrimary = PurpleOnPrimaryLight,
        primaryContainer = PurplePrimaryContainerLight,
        onPrimaryContainer = PurpleOnPrimaryContainerLight,
        secondary = PurpleSecondaryLight,
        onSecondary = PurpleOnSecondaryLight,
        secondaryContainer = PurpleSecondaryContainerLight,
        onSecondaryContainer = PurpleOnSecondaryContainerLight,
        tertiary = PurpleTertiaryLight,
        onTertiary = PurpleOnTertiaryLight,
        tertiaryContainer = PurpleTertiaryContainerLight,
        onTertiaryContainer = PurpleOnTertiaryContainerLight,
        background = BackgroundLight,
        onBackground = OnBackgroundLight,
        surface = SurfaceLight,
        onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        outline = Color(0xFFBDB6A9),
        outlineVariant = Color(0xFFDDD7CB)
    )

    val darkScheme = darkColorScheme(
        primary = PurplePrimaryDark,
        onPrimary = PurpleOnPrimaryDark,
        primaryContainer = PurplePrimaryContainerDark,
        onPrimaryContainer = PurpleOnPrimaryContainerDark,
        secondary = PurpleSecondaryDark,
        onSecondary = PurpleOnSecondaryDark,
        tertiary = PurpleTertiaryDark,
        onTertiary = PurpleOnTertiaryDark,
        background = BackgroundDark,
        onBackground = OnBackgroundDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark
    )

    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme else lightScheme,
        typography = Typography,
        content = content
    )
}
