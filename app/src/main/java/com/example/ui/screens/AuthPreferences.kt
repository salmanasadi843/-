package com.example.ui.screens

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

data class TeacherRequestRecord(val uid: String, val teacherName: String, val email: String, val teachingField: String, val institutionName: String, val status: String)
data class TeacherAccountRecord(val uid: String, val teacherName: String, val email: String, val role: String)

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
    // This account has admin privileges in Firestore rules and also uses the teacher UI.
    private const val PRIMARY_ADMIN_UID = "xgUdnaQ6X0hPobOUwx3SDI0BJVc2"

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
        if (role == UserRole.TEACHER) {
            onResult(
                Result.failure(
                    IllegalStateException(
                        "ثبت‌نام مستقیم استاد فعال نیست؛ نقش استاد باید از مسیر تأیید مدیر فعال شود."
                    )
                )
            )
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
                    // Client registration must never grant TEACHER privileges.
                    // A trusted admin can approve a teacher and set the role server-side.
                    "role" to UserRole.STUDENT.name,
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
                        cacheProfile(context, UserRole.STUDENT, name.trim())
                        onResult(Result.success(UserRole.STUDENT))
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

                        val roleValue = snapshot.getString("role")
                        val name = snapshot.getString("name").orEmpty().ifBlank {
                            if (normalizedEmail == PRIMARY_TEACHER_EMAIL) "سلمان اسدی" else ""
                        }
                        resolveTrustedRole(context, user.uid, roleValue, name, onResult)
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
                val name = snapshot.getString("name").orEmpty().ifBlank {
                    if (userEmail == PRIMARY_TEACHER_EMAIL) "سلمان اسدی" else ""
                }
                resolveTrustedRole(
                    context,
                    user.uid,
                    snapshot.getString("role"),
                    name,
                    onResult
                )
            }
    }

    private fun resolveTrustedRole(
        context: Context,
        uid: String,
        roleValue: String?,
        name: String,
        onResult: (Result<UserRole>) -> Unit
    ) {
        when (roleValue) {
            UserRole.STUDENT.name -> {
                cacheProfile(context, UserRole.STUDENT, name)
                onResult(Result.success(UserRole.STUDENT))
            }

            UserRole.TEACHER.name -> {
                // The primary admin account also acts as a teacher. Its admin
                // authority is enforced by Firestore rules; the app still opens
                // the teacher UI, so it can manage its own lessons normally.
                if (uid == PRIMARY_ADMIN_UID) {
                    cacheProfile(context, UserRole.TEACHER, name)
                    onResult(Result.success(UserRole.TEACHER))
                    return
                }

                // Other TEACHER accounts require a separate trusted approval.
                firestore(context).collection("approved_teachers").document(uid).get()
                    .addOnCompleteListener { approvalTask ->
                        if (!approvalTask.isSuccessful) {
                            onResult(
                                Result.failure(
                                    approvalTask.exception
                                        ?: IllegalStateException("بررسی تأیید استاد انجام نشد.")
                                )
                            )
                            return@addOnCompleteListener
                        }
                        if (!approvalTask.result.exists()) {
                            onResult(
                                Result.failure(
                                    IllegalStateException(
                                        "این حساب هنوز توسط مدیر به‌عنوان استاد تأیید نشده است."
                                    )
                                )
                            )
                            return@addOnCompleteListener
                        }
                        cacheProfile(context, UserRole.TEACHER, name)
                        onResult(Result.success(UserRole.TEACHER))
                    }
            }

            else -> onResult(
                Result.failure(
                    IllegalStateException("نقش این حساب در Firebase مشخص نشده است.")
                )
            )
        }
    }


    fun isPrimaryTeacher(context: Context): Boolean {
        val user = auth(context).currentUser ?: return false
        return user.uid == PRIMARY_ADMIN_UID || user.email?.trim()?.lowercase() == PRIMARY_TEACHER_EMAIL
    }
    fun loadTeacherRequests(context: Context, onResult: (Result<List<TeacherRequestRecord>>) -> Unit) {
        if (!isPrimaryTeacher(context)) { onResult(Result.failure(SecurityException("فقط مدیر اصلی اجازه مشاهده درخواست‌ها را دارد."))); return }
        firestore(context).collection("teacher_requests").get().addOnSuccessListener { snapshot ->
            val requests = snapshot.documents.mapNotNull { doc ->
                val uid = doc.getString("uid") ?: doc.id
                if (uid.isBlank()) null else TeacherRequestRecord(uid, doc.getString("teacherName") ?: doc.getString("name").orEmpty(), doc.getString("email").orEmpty(), doc.getString("teachingField") ?: doc.getString("subject").orEmpty(), doc.getString("institutionName") ?: doc.getString("institution").orEmpty(), doc.getString("status").orEmpty().ifBlank { "PENDING" })
            }.sortedWith(compareBy<TeacherRequestRecord> { it.status != "PENDING" }.thenBy { it.teacherName })
            onResult(Result.success(requests))
        }.addOnFailureListener { onResult(Result.failure(it)) }
    }
    fun loadTeacherAccounts(context: Context, onResult: (Result<List<TeacherAccountRecord>>) -> Unit) {
        if (!isPrimaryTeacher(context)) { onResult(Result.failure(SecurityException("فقط مدیر اصلی اجازه مدیریت استادان را دارد."))); return }
        firestore(context).collection("users").get().addOnSuccessListener { snapshot ->
            val accounts = snapshot.documents.mapNotNull { doc ->
                val role = doc.getString("role") ?: return@mapNotNull null
                if (role != UserRole.TEACHER.name && role != UserRole.STUDENT.name) return@mapNotNull null
                TeacherAccountRecord(doc.id, doc.getString("name").orEmpty().ifBlank { "بدون نام" }, doc.getString("email").orEmpty(), role)
            }.sortedWith(compareBy<TeacherAccountRecord> { it.role != UserRole.STUDENT.name }.thenBy { it.teacherName })
            onResult(Result.success(accounts))
        }.addOnFailureListener { onResult(Result.failure(it)) }
    }
    fun reviewTeacherRequest(context: Context, uid: String, teacherName: String, email: String, approve: Boolean, onResult: (Result<Unit>) -> Unit) {
        if (!isPrimaryTeacher(context)) { onResult(Result.failure(SecurityException("فقط مدیر اصلی اجازه بررسی درخواست استاد را دارد."))); return }
        if (uid.isBlank() || uid == currentUid(context)) { onResult(Result.failure(IllegalArgumentException("شناسه حساب درخواست‌دهنده معتبر نیست."))); return }
        val db = firestore(context); val batch = db.batch()
        batch.set(db.collection("teacher_requests").document(uid), mapOf("uid" to uid, "teacherName" to teacherName, "email" to email, "status" to if (approve) "APPROVED" else "REJECTED", "reviewedAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()), com.google.firebase.firestore.SetOptions.merge())
        if (approve) {
            batch.set(db.collection("users").document(uid), mapOf("uid" to uid, "name" to teacherName, "email" to email, "role" to UserRole.TEACHER.name, "updatedAt" to FieldValue.serverTimestamp()), com.google.firebase.firestore.SetOptions.merge())
            batch.set(db.collection("approved_teachers").document(uid), mapOf("ownerUid" to uid, "enabled" to true, "approved" to true, "updatedAt" to FieldValue.serverTimestamp()), com.google.firebase.firestore.SetOptions.merge())
        }
        batch.commit().addOnSuccessListener { onResult(Result.success(Unit)) }.addOnFailureListener { onResult(Result.failure(it)) }
    }
    fun setTeacherRole(context: Context, uid: String, teacherName: String, email: String, enabled: Boolean, onResult: (Result<Unit>) -> Unit) {
        if (!isPrimaryTeacher(context)) { onResult(Result.failure(SecurityException("فقط مدیر اصلی اجازه تغییر مجوز استاد را دارد."))); return }
        if (uid.isBlank() || uid == currentUid(context)) { onResult(Result.failure(IllegalArgumentException("تغییر نقش حساب مدیر اصلی از این بخش مجاز نیست."))); return }
        val db = firestore(context); val batch = db.batch()
        batch.set(db.collection("users").document(uid), mapOf("uid" to uid, "name" to teacherName, "email" to email, "role" to if (enabled) UserRole.TEACHER.name else UserRole.STUDENT.name, "updatedAt" to FieldValue.serverTimestamp()), com.google.firebase.firestore.SetOptions.merge())
        batch.set(db.collection("teacher_requests").document(uid), mapOf("uid" to uid, "teacherName" to teacherName, "email" to email, "status" to if (enabled) "APPROVED" else "REVOKED", "updatedAt" to FieldValue.serverTimestamp()), com.google.firebase.firestore.SetOptions.merge())
        val approvalRef = db.collection("approved_teachers").document(uid)
        if (enabled) batch.set(approvalRef, mapOf("ownerUid" to uid, "enabled" to true, "approved" to true, "updatedAt" to FieldValue.serverTimestamp()), com.google.firebase.firestore.SetOptions.merge()) else batch.delete(approvalRef)
        batch.commit().addOnSuccessListener { onResult(Result.success(Unit)) }.addOnFailureListener { onResult(Result.failure(it)) }
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
