package com.example.ui.screens

import android.content.Context
import java.security.MessageDigest

object AuthPreferences {
    private const val PREFS = "ostadyar_auth"
    private const val SESSION = "logged_in"
    private const val EMAIL = "email"
    private const val ROLE = "role"
    private const val NAME = "name"

    fun isLoggedIn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(SESSION, false)

    fun currentRole(context: Context): UserRole =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ROLE, UserRole.STUDENT.name) == UserRole.TEACHER.name
        ) UserRole.TEACHER else UserRole.STUDENT

    fun register(email: String, password: String, role: UserRole, name: String): Result<UserRole> {
        if (!email.contains("@") || !email.contains(".")) {
            return Result.failure(IllegalArgumentException("ایمیل معتبر وارد کنید."))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("رمز عبور باید حداقل ۶ کاراکتر باشد."))
        }
        if (name.isBlank()) {
            return Result.failure(IllegalArgumentException("نام و نام خانوادگی را وارد کنید."))
        }

        val context = AppContextHolder.context
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("password_hash", hash(password))
            .putString(EMAIL, email)
            .putString(ROLE, role.name)
            .putString(NAME, name)
            .putBoolean(SESSION, true)
            .apply()
        return Result.success(role)
    }

    fun login(email: String, password: String): Result<UserRole> {
        val context = AppContextHolder.context
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedEmail = prefs.getString(EMAIL, null)
        val savedHash = prefs.getString("password_hash", null)
        if (savedEmail != email || savedHash != hash(password)) {
            return Result.failure(IllegalArgumentException("ایمیل یا رمز عبور نادرست است."))
        }
        val role = currentRole(context)
        prefs.edit().putBoolean(SESSION, true).apply()
        return Result.success(role)
    }

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(SESSION, false).apply()
    }

    fun attach(context: Context) {
        AppContextHolder.context = context.applicationContext
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

private object AppContextHolder {
    lateinit var context: Context
}
