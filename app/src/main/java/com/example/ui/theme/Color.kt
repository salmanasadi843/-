package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// تم اصلی: بنفش خلاقانه — آرام، مدرن و مناسب مطالعه
val PurplePrimaryLight = Color(0xFF6C4CF1)
val PurpleOnPrimaryLight = Color(0xFFFFFFFF)
val PurplePrimaryContainerLight = Color(0xFFEAE3FF)
val PurpleOnPrimaryContainerLight = Color(0xFF26105F)
val PurpleSecondaryLight = Color(0xFF00AFA3)
val PurpleOnSecondaryLight = Color(0xFFFFFFFF)
val PurpleSecondaryContainerLight = Color(0xFFD2F5F1)
val PurpleOnSecondaryContainerLight = Color(0xFF003D39)
val PurpleTertiaryLight = Color(0xFFFF8A00)
val PurpleOnTertiaryLight = Color(0xFFFFFFFF)
val PurpleTertiaryContainerLight = Color(0xFFFFE8C7)
val PurpleOnTertiaryContainerLight = Color(0xFF4A2800)

val BluePrimaryLight = Color(0xFF326FE8)
val BlueOnPrimaryLight = Color(0xFFFFFFFF)
val BluePrimaryContainerLight = Color(0xFFDCE7FF)
val BlueOnPrimaryContainerLight = Color(0xFF0B245B)
val BlueSecondaryLight = Color(0xFF00A6C7)
val BlueOnSecondaryLight = Color(0xFFFFFFFF)
val BlueSecondaryContainerLight = Color(0xFFCFF4FA)
val BlueOnSecondaryContainerLight = Color(0xFF003640)
val BlueTertiaryLight = Color(0xFF36558F)
val BlueOnTertiaryLight = Color(0xFFFFFFFF)
val BlueTertiaryContainerLight = Color(0xFFDCE5FF)
val BlueOnTertiaryContainerLight = Color(0xFF071A42)

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
val BackgroundLight = Color(0xFFF7F8FC)
val OnBackgroundLight = Color(0xFF1E293B)
val SurfaceLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF1E293B)
val SurfaceVariantLight = Color(0xFFEEF0F6)
val OnSurfaceVariantLight = Color(0xFF64748B)

val BackgroundDark = Color(0xFF0F1220)
val OnBackgroundDark = Color(0xFFE9EAF2)
val SurfaceDark = Color(0xFF171A27)
val OnSurfaceDark = Color(0xFFE9EAF2)
val SurfaceVariantDark = Color(0xFF282C3A)
val OnSurfaceVariantDark = Color(0xFFB9BDCC)

// Dark palette is intentionally shared in structure; the primary accent follows the selected theme.
val PurplePrimaryDark = Color(0xFFB9A7FF)
val PurpleOnPrimaryDark = Color(0xFF24105E)
val PurplePrimaryContainerDark = Color(0xFF4C35A8)
val PurpleOnPrimaryContainerDark = Color(0xFFEAE3FF)
val PurpleSecondaryDark = Color(0xFF66D8CE)
val PurpleOnSecondaryDark = Color(0xFF003A36)
val PurpleTertiaryDark = Color(0xFFFFB968)
val PurpleOnTertiaryDark = Color(0xFF4A2800)

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

val TagPillBg = Color(0xFFE7E9F2)
val TagPillText = Color(0xFF334155)
val SuccessGreen = Color(0xFF10A981)
val RecordRed = Color(0xFFDC4C4C)

fun themeAccent(mode: AppThemeMode): Color = when (mode) {
    AppThemeMode.PURPLE -> PurplePrimaryLight
    AppThemeMode.BLUE -> BluePrimaryLight
    AppThemeMode.GREEN -> GreenPrimaryLight
    AppThemeMode.ORANGE -> OrangePrimaryLight
}
