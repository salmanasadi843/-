package com.example.data.repository

/**
 * The root document is the commit point for an immutable backup generation.
 * Comparing its complete version prevents a stale upload from replacing a
 * generation or admin restore committed while the new records were staged.
 */
internal data class BackupRootVersion(
    val exists: Boolean,
    val ownerUid: String?,
    val schemaVersion: Long?,
    val activeGeneration: String?,
    val adminPending: Boolean,
    val adminPendingVersion: Long?,
    val syncLockUntilMillis: Long?,
    val updateTimeSeconds: Long?,
    val updateTimeNanos: Int?
)

internal object BackupGenerationCommitGuard {
    fun requireCanActivate(
        expectedOwnerUid: String,
        expected: BackupRootVersion,
        current: BackupRootVersion,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        require(expected.ownerUid.isNullOrBlank() || expected.ownerUid == expectedOwnerUid) {
            "مالک نسخهٔ پشتیبان با حساب فعلی سازگار نیست؛ پشتیبان‌گیری متوقف شد."
        }
        require(current.ownerUid.isNullOrBlank() || current.ownerUid == expectedOwnerUid) {
            "مالک نسخهٔ پشتیبان در زمان همگام‌سازی تغییر کرده است؛ نسخهٔ قبلی حفظ شد."
        }
        require(!expected.adminPending && !current.adminPending) {
            "تغییرات مدیریتی هنوز در دستگاه استاد بازیابی نشده است؛ ابتدا بازیابی ابری را انجام دهید."
        }
        require(
            (expected.syncLockUntilMillis ?: 0L) <= nowMillis &&
                (current.syncLockUntilMillis ?: 0L) <= nowMillis
        ) {
            "همگام‌سازی دیگری در حال انجام است؛ کمی بعد دوباره تلاش کنید."
        }
        require(
            expected.exists == current.exists &&
                expected.schemaVersion == current.schemaVersion &&
                expected.activeGeneration == current.activeGeneration &&
                expected.adminPendingVersion == current.adminPendingVersion &&
                expected.syncLockUntilMillis == current.syncLockUntilMillis &&
                expected.updateTimeSeconds == current.updateTimeSeconds &&
                expected.updateTimeNanos == current.updateTimeNanos
        ) {
            "نسخهٔ پشتیبان هنگام همگام‌سازی تغییر کرده است؛ برای جلوگیری از بازنویسی اطلاعات جدید، این بار پشتیبان‌گیری متوقف شد. دوباره تلاش کنید."
        }
    }
}
