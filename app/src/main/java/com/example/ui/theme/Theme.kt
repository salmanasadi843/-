package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun MyApplicationTheme(
    appTheme: AppThemeMode = AppThemeMode.PURPLE,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val lightScheme = when (appTheme) {
        AppThemeMode.PURPLE -> lightColorScheme(
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
            onSurfaceVariant = OnSurfaceVariantLight
        )
        AppThemeMode.BLUE -> lightColorScheme(
            primary = BluePrimaryLight,
            onPrimary = BlueOnPrimaryLight,
            primaryContainer = BluePrimaryContainerLight,
            onPrimaryContainer = BlueOnPrimaryContainerLight,
            secondary = BlueSecondaryLight,
            onSecondary = BlueOnSecondaryLight,
            secondaryContainer = BlueSecondaryContainerLight,
            onSecondaryContainer = BlueOnSecondaryContainerLight,
            tertiary = BlueTertiaryLight,
            onTertiary = BlueOnTertiaryLight,
            tertiaryContainer = BlueTertiaryContainerLight,
            onTertiaryContainer = BlueOnTertiaryContainerLight,
            background = BackgroundLight,
            onBackground = OnBackgroundLight,
            surface = SurfaceLight,
            onSurface = OnSurfaceLight,
            surfaceVariant = SurfaceVariantLight,
            onSurfaceVariant = OnSurfaceVariantLight
        )
        AppThemeMode.GREEN -> lightColorScheme(
            primary = GreenPrimaryLight,
            onPrimary = GreenOnPrimaryLight,
            primaryContainer = GreenPrimaryContainerLight,
            onPrimaryContainer = GreenOnPrimaryContainerLight,
            secondary = GreenSecondaryLight,
            onSecondary = GreenOnSecondaryLight,
            secondaryContainer = GreenSecondaryContainerLight,
            onSecondaryContainer = GreenOnSecondaryContainerLight,
            tertiary = GreenTertiaryLight,
            onTertiary = GreenOnTertiaryLight,
            tertiaryContainer = GreenTertiaryContainerLight,
            onTertiaryContainer = GreenOnTertiaryContainerLight,
            background = BackgroundLight,
            onBackground = OnBackgroundLight,
            surface = SurfaceLight,
            onSurface = OnSurfaceLight,
            surfaceVariant = SurfaceVariantLight,
            onSurfaceVariant = OnSurfaceVariantLight
        )
        AppThemeMode.ORANGE -> lightColorScheme(
            primary = OrangePrimaryLight,
            onPrimary = OrangeOnPrimaryLight,
            primaryContainer = OrangePrimaryContainerLight,
            onPrimaryContainer = OrangeOnPrimaryContainerLight,
            secondary = OrangeSecondaryLight,
            onSecondary = OrangeOnSecondaryLight,
            secondaryContainer = OrangeSecondaryContainerLight,
            onSecondaryContainer = OrangeOnSecondaryContainerLight,
            tertiary = OrangeTertiaryLight,
            onTertiary = OrangeOnTertiaryLight,
            tertiaryContainer = OrangeTertiaryContainerLight,
            onTertiaryContainer = OrangeOnTertiaryContainerLight,
            background = BackgroundLight,
            onBackground = OnBackgroundLight,
            surface = SurfaceLight,
            onSurface = OnSurfaceLight,
            surfaceVariant = SurfaceVariantLight,
            onSurfaceVariant = OnSurfaceVariantLight
        )
    }

    val darkScheme = when (appTheme) {
        AppThemeMode.PURPLE -> darkColorScheme(
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
        AppThemeMode.BLUE -> darkColorScheme(
            primary = BluePrimaryDark,
            onPrimary = BlueOnPrimaryDark,
            primaryContainer = BluePrimaryContainerDark,
            onPrimaryContainer = BlueOnPrimaryContainerDark,
            secondary = BlueSecondaryDark,
            onSecondary = BlueOnSecondaryDark,
            tertiary = BlueTertiaryDark,
            onTertiary = BlueOnTertiaryDark,
            background = BackgroundDark,
            onBackground = OnBackgroundDark,
            surface = SurfaceDark,
            onSurface = OnSurfaceDark,
            surfaceVariant = SurfaceVariantDark,
            onSurfaceVariant = OnSurfaceVariantDark
        )
        AppThemeMode.GREEN -> darkColorScheme(
            primary = GreenPrimaryDark,
            onPrimary = GreenOnPrimaryDark,
            primaryContainer = GreenPrimaryContainerDark,
            onPrimaryContainer = GreenOnPrimaryContainerDark,
            secondary = GreenSecondaryDark,
            onSecondary = GreenOnSecondaryDark,
            tertiary = GreenTertiaryDark,
            onTertiary = GreenOnTertiaryDark,
            background = BackgroundDark,
            onBackground = OnBackgroundDark,
            surface = SurfaceDark,
            onSurface = OnSurfaceDark,
            surfaceVariant = SurfaceVariantDark,
            onSurfaceVariant = OnSurfaceVariantDark
        )
        AppThemeMode.ORANGE -> darkColorScheme(
            primary = OrangePrimaryDark,
            onPrimary = OrangeOnPrimaryDark,
            primaryContainer = OrangePrimaryContainerDark,
            onPrimaryContainer = OrangeOnPrimaryContainerDark,
            secondary = OrangeSecondaryDark,
            onSecondary = OrangeOnSecondaryDark,
            tertiary = OrangeTertiaryDark,
            onTertiary = OrangeOnTertiaryDark,
            background = BackgroundDark,
            onBackground = OnBackgroundDark,
            surface = SurfaceDark,
            onSurface = OnSurfaceDark,
            surfaceVariant = SurfaceVariantDark,
            onSurfaceVariant = OnSurfaceVariantDark
        )
    }

    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme else lightScheme,
        typography = Typography,
        content = content
    )
}
