package com.example.ui.screens

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

object AuthPreferences {
    private const val PREFS = "ostadyar_auth"
    private const val ROLE = "role"
    private const val NAME = "name"

    private const val API_KEY = "AIzaSyCuVR2qgUAepZhLbEUPAOK3XoZK86YSkdE"
    private const val APP_ID = "1:72182911776:android:bd94d0b630f72977a87d45"
    private const val PROJECT_ID = "salmanasadi843-9149"
    private const val STORAGE_BUCKET = "salmanasadi843-9149.firebasestorage.app"

    private fun ensureFirebase(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context.applicationContext,
                FirebaseOptions.Builder()
                    .setApiKey(API_KEY)
                    .setApplicationId(APP_ID)
                    .setProjectId(PROJECT_ID)
                    .setStorageBucket(STORAGE_BUCKET)
                    .build()
            )
        }
    }

    private fun auth(context: Context): FirebaseAuth {
        ensureFirebase(context)
        return FirebaseAuth.getInstance()
    }

    private fun firestore(context: Context): FirebaseFirestore {
        ensureFirebase(context)
        return FirebaseFirestore.getInstance()
    }

    fun isLoggedIn(context: Context): Boolean =
        auth(context).currentUser != null

    fun currentRole(context: Context): UserRole =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ROLE, UserRole.STUDENT.name) == UserRole.TEACHER.name
        ) UserRole.TEACHER else UserRole.STUDENT

    fun currentUid(context: Context): String? = auth(context).currentUser?.uid

    fun currentName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(NAME, "") ?: ""

    fun register(
        context: Context,
        email: String,
        password: String,
        role: UserRole,
        name: String,
        onResult: (Result<UserRole>) -> Unit
    ) {
        val normalizedEmail = email.trim().lowercase()
        if (!normalizedEmail.contains("@") || !normalizedEmail.contains(".")) {
            onResult(Result.failure(IllegalArgumentException("ایمیل معتبر وارد کنید.")))
            return
        }
        if (password.length < 6) {
            onResult(Result.failure(IllegalArgumentException("رمز عبور باید حداقل ۶ کاراکتر باشد.")))
            return
        }
        if (name.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("نام و نام خانوادگی را وارد کنید.")))
            return
        }

        auth(context).createUserWithEmailAndPassword(normalizedEmail, password)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    onResult(Result.failure(task.exception ?: IllegalArgumentException("ساخت حساب انجام نشد.")))
                    return@addOnCompleteListener
                }

                val user = auth(context).currentUser
                if (user == null) {
                    onResult(Result.failure(IllegalStateException("کاربر Firebase ایجاد شد اما قابل دسترسی نیست.")))
                    return@addOnCompleteListener
                }

                val data = hashMapOf<String, Any>(
                    "email" to normalizedEmail,
                    "name" to name.trim(),
                    "role" to role.name,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                firestore(context).collection("users").document(user.uid).set(data)
                    .addOnCompleteListener { profileTask ->
                        if (!profileTask.isSuccessful) {
                            onResult(
                                Result.failure(
                                    profileTask.exception
                                        ?: IllegalStateException("حساب ساخته شد اما پروفایل ذخیره نشد.")
                                )
                            )
                            return@addOnCompleteListener
                        }
                        cacheProfile(context, role, name.trim())
                        onResult(Result.success(role))
                    }
            }
    }

    fun login(
        context: Context,
        email: String,
        password: String,
        onResult: (Result<UserRole>) -> Unit
    ) {
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank() || password.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("ایمیل و رمز عبور را وارد کنید.")))
            return
        }

        auth(context).signInWithEmailAndPassword(normalizedEmail, password)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    onResult(Result.failure(task.exception ?: IllegalArgumentException("ایمیل یا رمز عبور نادرست است.")))
                    return@addOnCompleteListener
                }

                val user = auth(context).currentUser
                if (user == null) {
                    onResult(Result.failure(IllegalStateException("ورود انجام شد اما کاربر در دسترس نیست.")))
                    return@addOnCompleteListener
                }

                firestore(context).collection("users").document(user.uid).get()
                    .addOnCompleteListener { profileTask ->
                        if (!profileTask.isSuccessful) {
                            val role = currentRole(context)
                            onResult(Result.success(role))
                            return@addOnCompleteListener
                        }

                        val snapshot = profileTask.result
                        val role = if (snapshot.getString("role") == UserRole.TEACHER.name) {
                            UserRole.TEACHER
                        } else {
                            UserRole.STUDENT
                        }
                        val name = snapshot.getString("name").orEmpty()
                        cacheProfile(context, role, name)
                        onResult(Result.success(role))
                    }
            }
    }

    fun sendPasswordReset(
        context: Context,
        email: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        val normalizedEmail = email.trim().lowercase()
        if (!normalizedEmail.contains("@") || !normalizedEmail.contains(".")) {
            onResult(Result.failure(IllegalArgumentException("ابتدا یک ایمیل معتبر وارد کنید.")))
            return
        }

        auth(context).sendPasswordResetEmail(normalizedEmail)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    onResult(Result.success(Unit))
                } else {
                    onResult(
                        Result.failure(
                            task.exception ?: IllegalArgumentException("ارسال ایمیل بازیابی رمز عبور انجام نشد.")
                        )
                    )
                }
            }
    }

    fun refreshProfile(
        context: Context,
        onResult: (Result<UserRole>) -> Unit
    ) {
        val user = auth(context).currentUser
        if (user == null) {
            onResult(Result.failure(IllegalStateException("کاربری وارد نشده است.")))
            return
        }

        firestore(context).collection("users").document(user.uid).get()
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    onResult(
                        Result.failure(
                            task.exception ?: IllegalStateException("دریافت پروفایل کاربر انجام نشد.")
                        )
                    )
                    return@addOnCompleteListener
                }

                val snapshot = task.result
                val role = if (snapshot.getString("role") == UserRole.TEACHER.name) {
                    UserRole.TEACHER
                } else {
                    UserRole.STUDENT
                }
                val name = snapshot.getString("name").orEmpty()
                cacheProfile(context, role, name)
                onResult(Result.success(role))
            }
    }

    fun logout(context: Context) {
        auth(context).signOut()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun attach(context: Context) {
        ensureFirebase(context)
    }

    private fun cacheProfile(context: Context, role: UserRole, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ROLE, role.name)
            .putString(NAME, name)
            .apply()
    }
}
