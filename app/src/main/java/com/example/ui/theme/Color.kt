package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// تم اصلی: کرم، سرمه‌ای و طلایی — آرام، مدرن و مناسب مطالعه
val PurplePrimaryLight = Color(0xFF24344D)
val PurpleOnPrimaryLight = Color(0xFFFFFFFF)
val PurplePrimaryContainerLight = Color(0xFFE8DFCF)
val PurpleOnPrimaryContainerLight = Color(0xFF24344D)
val PurpleSecondaryLight = Color(0xFFB58A3A)
val PurpleOnSecondaryLight = Color(0xFFFFFFFF)
val PurpleSecondaryContainerLight = Color(0xFFF1E7D2)
val PurpleOnSecondaryContainerLight = Color(0xFF4A381D)
val PurpleTertiaryLight = Color(0xFFB58A3A)
val PurpleOnTertiaryLight = Color(0xFFFFFFFF)
val PurpleTertiaryContainerLight = Color(0xFFF1E7D2)
val PurpleOnTertiaryContainerLight = Color(0xFF4A381D)

val BluePrimaryLight = Color(0xFF24344D)
val BlueOnPrimaryLight = Color(0xFFFFFFFF)
val BluePrimaryContainerLight = Color(0xFFE8DFCF)
val BlueOnPrimaryContainerLight = Color(0xFF24344D)
val BlueSecondaryLight = Color(0xFFB58A3A)
val BlueOnSecondaryLight = Color(0xFFFFFFFF)
val BlueSecondaryContainerLight = Color(0xFFF1E7D2)
val BlueOnSecondaryContainerLight = Color(0xFF4A381D)
val BlueTertiaryLight = Color(0xFFB58A3A)
val BlueOnTertiaryLight = Color(0xFFFFFFFF)
val BlueTertiaryContainerLight = Color(0xFFF1E7D2)
val BlueOnTertiaryContainerLight = Color(0xFF4A381D)

val GreenPrimaryLight = Color(0xFF10A981)
val GreenOnPrimaryLight = Color(0xFFFFFFFF)
val GreenPrimaryContainerLight = Color(0xFFC9F6E7)
val GreenOnPrimaryContainerLight = Color(0xFF00382A)
val GreenSecondaryLight = Color(0xFF059669)
val GreenOnSecondaryLight = Color(0xFFFFFFFF)
val GreenSecondaryContainerLight = Color(0xFFC8F5DE)
val GreenOnSecondaryContainerLight = Color(0xFF00391F)
val GreenTertiaryLight = Color(0xFFF59E0B)
val GreenOnTertiaryLight = Color(0xFF382000)
val GreenTertiaryContainerLight = Color(0xFFFFEDC1)
val GreenOnTertiaryContainerLight = Color(0xFF2C1800)

val OrangePrimaryLight = Color(0xFFF97316)
val OrangeOnPrimaryLight = Color(0xFFFFFFFF)
val OrangePrimaryContainerLight = Color(0xFFFFE5D5)
val OrangeOnPrimaryContainerLight = Color(0xFF4B1B00)
val OrangeSecondaryLight = Color(0xFF10B981)
val OrangeOnSecondaryLight = Color(0xFFFFFFFF)
val OrangeSecondaryContainerLight = Color(0xFFC9F7E8)
val OrangeOnSecondaryContainerLight = Color(0xFF00382A)
val OrangeTertiaryLight = Color(0xFFB83A18)
val OrangeOnTertiaryLight = Color(0xFFFFFFFF)
val OrangeTertiaryContainerLight = Color(0xFFFFDBCF)
val OrangeOnTertiaryContainerLight = Color(0xFF421105)

// Neutral surfaces — shared by all branded themes
val BackgroundLight = Color(0xFFF7F4EC)
val OnBackgroundLight = Color(0xFF24344D)
val SurfaceLight = Color(0xFFFFFCF7)
val OnSurfaceLight = Color(0xFF24344D)
val SurfaceVariantLight = Color(0xFFEDE7DC)
val OnSurfaceVariantLight = Color(0xFF657080)

val BackgroundDark = Color(0xFF0F1220)
val OnBackgroundDark = Color(0xFFE9EAF2)
val SurfaceDark = Color(0xFF171A27)
val OnSurfaceDark = Color(0xFFE9EAF2)
val SurfaceVariantDark = Color(0xFF282C3A)
val OnSurfaceVariantDark = Color(0xFFB9BDCC)

// Dark palette is intentionally shared in structure; the primary accent follows the selected theme.
val PurplePrimaryDark = Color(0xFFB9C6D9)
val PurpleOnPrimaryDark = Color(0xFF182638)
val PurplePrimaryContainerDark = Color(0xFF34465F)
val PurpleOnPrimaryContainerDark = Color(0xFFF7F4EC)
val PurpleSecondaryDark = Color(0xFFD8B45A)
val PurpleOnSecondaryDark = Color(0xFF30220A)
val PurpleTertiaryDark = Color(0xFFE0C27A)
val PurpleOnTertiaryDark = Color(0xFF30220A)

val BluePrimaryDark = Color(0xFFAEC6FF)
val BlueOnPrimaryDark = Color(0xFF06275F)
val BluePrimaryContainerDark = Color(0xFF2759B8)
val BlueOnPrimaryContainerDark = Color(0xFFDCE7FF)
val BlueSecondaryDark = Color(0xFF5DDAF1)
val BlueOnSecondaryDark = Color(0xFF00343E)
val BlueTertiaryDark = Color(0xFFB4C8FF)
val BlueOnTertiaryDark = Color(0xFF122A5E)

val GreenPrimaryDark = Color(0xFF65DBB8)
val GreenOnPrimaryDark = Color(0xFF00382A)
val GreenPrimaryContainerDark = Color(0xFF087C60)
val GreenOnPrimaryContainerDark = Color(0xFFC9F6E7)
val GreenSecondaryDark = Color(0xFF5DDBA2)
val GreenOnSecondaryDark = Color(0xFF003820)
val GreenTertiaryDark = Color(0xFFFFC56B)
val GreenOnTertiaryDark = Color(0xFF402700)

val OrangePrimaryDark = Color(0xFFFFB27D)
val OrangeOnPrimaryDark = Color(0xFF542000)
val OrangePrimaryContainerDark = Color(0xFFB94F14)
val OrangeOnPrimaryContainerDark = Color(0xFFFFE5D5)
val OrangeSecondaryDark = Color(0xFF65DDB2)
val OrangeOnSecondaryDark = Color(0xFF00382A)
val OrangeTertiaryDark = Color(0xFFFFB49E)
val OrangeOnTertiaryDark = Color(0xFF4A1607)

val TagPillBg = Color(0xFFEDE2CF)
val TagPillText = Color(0xFF4A381D)
val SuccessGreen = Color(0xFF10A981)
val RecordRed = Color(0xFFDC4C4C)

fun themeAccent(mode: AppThemeMode): Color = when (mode) {
    AppThemeMode.PURPLE -> PurplePrimaryLight
    AppThemeMode.BLUE -> BluePrimaryLight
    AppThemeMode.GREEN -> GreenPrimaryLight
    AppThemeMode.ORANGE -> OrangePrimaryLight
}
