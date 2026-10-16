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
    private const val PROJECT_ID = "salmanasadi843-9149a"
    private const val STORAGE_BUCKET = "salmanasadi843-9149a.firebasestorage.app"
    // حساب اصلی استاد در درس‌یار؛ مستقل از حساب یادنو است.
    private const val PRIMARY_TEACHER_EMAIL = "salmanasadi843@gmail.com"

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
                    "role" to (if (normalizedEmail == PRIMARY_TEACHER_EMAIL) UserRole.TEACHER else UserRole.STUDENT).name,
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
                        val effectiveRole = if (normalizedEmail == PRIMARY_TEACHER_EMAIL) UserRole.TEACHER else UserRole.STUDENT
                        cacheProfile(context, effectiveRole, name.trim())
                        onResult(Result.success(effectiveRole))
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
                            onResult(
                                Result.failure(
                                    profileTask.exception
                                        ?: IllegalStateException("دریافت نقش کاربر از Firebase انجام نشد.")
                                )
                            )
                            return@addOnCompleteListener
                        }

                        val snapshot = profileTask.result
                        if (!snapshot.exists()) {
                            onResult(
                                Result.failure(
                                    IllegalStateException(
                                        "پروفایل این حساب در Firebase وجود ندارد. نقش کاربر مشخص نشده است."
                                    )
                                )
                            )
                            return@addOnCompleteListener
                        }

                        val isPrimaryTeacher =
                            normalizedEmail == PRIMARY_TEACHER_EMAIL
                        val roleValue = snapshot.getString("role")
                        val role = when {
                            isPrimaryTeacher -> UserRole.TEACHER
                            roleValue == UserRole.TEACHER.name -> UserRole.TEACHER
                            roleValue == UserRole.STUDENT.name -> UserRole.STUDENT
                            else -> {
                                onResult(
                                    Result.failure(
                                        IllegalStateException(
                                            "نقش این حساب در Firebase مشخص نشده است. مقدار role باید TEACHER یا STUDENT باشد."
                                        )
                                    )
                                )
                                return@addOnCompleteListener
                            }
                        }
                        val name = snapshot.getString("name").orEmpty().ifBlank {
                            if (isPrimaryTeacher) "سلمان اسدی" else ""
                        }

                        if (isPrimaryTeacher &&
                            (roleValue != UserRole.TEACHER.name || snapshot.getString("name").isNullOrBlank())
                        ) {
                            val teacherProfile = hashMapOf<String, Any>(
                                "email" to normalizedEmail,
                                "name" to name,
                                "role" to UserRole.TEACHER.name,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                            firestore(context).collection("users").document(user.uid)
                                .set(teacherProfile, com.google.firebase.firestore.SetOptions.merge())
                                .addOnCompleteListener { updateTask ->
                                    if (!updateTask.isSuccessful) {
                                        onResult(
                                            Result.failure(
                                                updateTask.exception
                                                    ?: IllegalStateException("تنظیم نقش استاد انجام نشد.")
                                            )
                                        )
                                        return@addOnCompleteListener
                                    }
                                    cacheProfile(context, UserRole.TEACHER, name)
                                    onResult(Result.success(UserRole.TEACHER))
                                }
                        } else {
                            cacheProfile(context, role, name)
                            onResult(Result.success(role))
                        }
                    }
            }
    }

    fun isPrimaryTeacher(context: Context): Boolean =
        auth(context).currentUser?.email?.trim()?.lowercase() == PRIMARY_TEACHER_EMAIL

    fun submitTeacherRequest(
        context: Context,
        teacherName: String,
        teachingField: String,
        institutionName: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        val user = auth(context).currentUser
        if (user == null) {
            onResult(Result.failure(IllegalStateException("برای ثبت درخواست ابتدا وارد حساب شوید.")))
            return
        }
        if (teacherName.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("نام استاد را وارد کنید.")))
            return
        }
        if (teachingField.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("درس موردنظر برای تدریس را وارد کنید.")))
            return
        }
        if (isPrimaryTeacher(context)) {
            onResult(Result.failure(IllegalStateException("حساب مدیر سامانه نیازی به درخواست تأیید استاد ندارد.")))
            return
        }

        val request = hashMapOf<String, Any>(
            "uid" to user.uid,
            "email" to (user.email ?: ""),
            "name" to teacherName.trim(),
            "teacherName" to teacherName.trim(),
            "teachingField" to teachingField.trim(),
            "subject" to teachingField.trim(),
            "institutionName" to institutionName.trim(),
            "status" to "PENDING",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        val firestore = firestore(context)
        val batch = firestore.batch()
        batch.set(
            firestore.collection("teacher_requests").document(user.uid),
            request,
            com.google.firebase.firestore.SetOptions.merge()
        )
        // درخواست فقط در صف مرکزی ثبت می‌شود تا ثبت درخواست به نوشتن در دو مسیر وابسته نباشد.
        batch.commit().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                onResult(Result.success(Unit))
            } else {
                onResult(
                    Result.failure(
                        task.exception ?: IllegalStateException("ثبت درخواست استاد انجام نشد.")
                    )
                )
            }
        }
    }

    fun reviewTeacherRequest(
        context: Context,
        uid: String,
        teacherName: String,
        email: String,
        approve: Boolean,
        onResult: (Result<Unit>) -> Unit
    ) {
        if (!isPrimaryTeacher(context)) {
            onResult(Result.failure(SecurityException("فقط مدیر اصلی سامانه اجازه بررسی درخواست استاد را دارد.")))
            return
        }
        if (uid.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("شناسه حساب درخواست‌دهنده خالی است.")))
            return
        }

        val db = firestore(context)
        val status = if (approve) "APPROVED" else "REJECTED"
        val batch = db.batch()
        val requestData = hashMapOf<String, Any>(
            "status" to status,
            "reviewedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        batch.set(
            db.collection("teacher_requests").document(uid),
            requestData,
            com.google.firebase.firestore.SetOptions.merge()
        )
        // وضعیت درخواست در صف مرکزی به‌روزرسانی می‌شود؛ از نوشتن تکراری در زیرمجموعه حساب پرهیز می‌کنیم.
        if (approve) {
            batch.set(
                db.collection("users").document(uid),
                hashMapOf<String, Any>(
                    "email" to email,
                    "name" to teacherName,
                    "role" to UserRole.TEACHER.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
        }
        batch.commit().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                onResult(Result.success(Unit))
            } else {
                onResult(
                    Result.failure(
                        task.exception ?: IllegalStateException("بررسی درخواست استاد انجام نشد.")
                    )
                )
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
                val userEmail = user.email?.trim()?.lowercase().orEmpty()
                val role = if (
                    userEmail == PRIMARY_TEACHER_EMAIL ||
                    snapshot.getString("role") == UserRole.TEACHER.name
                ) {
                    UserRole.TEACHER
                } else if (snapshot.getString("role") == UserRole.STUDENT.name) {
                    UserRole.STUDENT
                } else {
                    onResult(
                        Result.failure(
                            IllegalStateException("نقش این حساب در Firebase مشخص نشده است.")
                        )
                    )
                    return@addOnCompleteListener
                }
                val name = snapshot.getString("name").orEmpty().ifBlank {
                    if (userEmail == PRIMARY_TEACHER_EMAIL) "سلمان اسدی" else ""
                }
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
