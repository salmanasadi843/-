package com.example.ui.theme

import android.content.Context

enum class AppThemeMode(val title: String) {
    PURPLE("کرم و سرمه‌ای"),
    BLUE("آبی دانشگاهی"),
    GREEN("سبز آرامش‌بخش"),
    ORANGE("نارنجی انرژی")
}

object ThemePreferences {
    private const val PREFS_NAME = "ostadyar_theme_preferences"
    private const val KEY_THEME = "theme_mode"

    fun load(context: Context): AppThemeMode {
        val value = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, AppThemeMode.PURPLE.name)
            ?: AppThemeMode.PURPLE.name

        return runCatching { AppThemeMode.valueOf(value) }
            .getOrDefault(AppThemeMode.PURPLE)
    }

    fun save(context: Context, mode: AppThemeMode) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode.name)
            .apply()
    }
}
