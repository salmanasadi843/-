package com.example.data.discussion

/**
 * Cloud model for a discussion thread attached to a teacher-owned class.
 *
 * IDs for class/course/lecture are stored as strings from the teacher's local
 * database. Student-side negative Room IDs are derived cache IDs and must
 * never be written here as canonical IDs.
 *
 * This is a data contract only; Firestore access must be routed through a
 * repository with server-side rules that validate the author's role and
 * publication state. Client-provided role/name fields are display metadata,
 * not authorization evidence.
 */
data class DiscussionThread(
    val id: String = "",
    val teacherUid: String = "",
    val classId: String = "",
    val courseId: String? = null,
    val lectureId: String? = null,
    val title: String = "",
    val questionText: String = "",
    val anchorText: String? = null,
    val anchorStart: Int? = null,
    val anchorEnd: Int? = null,
    val authorUid: String = "",
    val authorName: String = "",
    val authorRole: String = "",
    val status: String = STATUS_UNANSWERED,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val resolvedAtMillis: Long? = null,
    val isDeleted: Boolean = false
) {
    companion object {
        const val STATUS_UNANSWERED = "UNANSWERED"
        const val STATUS_ANSWERED = "ANSWERED"
        const val STATUS_RESOLVED = "RESOLVED"
    }
}

/**
 * A reply or follow-up within a thread. Editing must create an
 * EditHistoryEntry in the same trusted repository operation.
 */
data class DiscussionPost(
    val id: String = "",
    val threadId: String = "",
    val teacherUid: String = "",
    val classId: String = "",
    val authorUid: String = "",
    val authorName: String = "",
    val authorRole: String = "",
    val text: String = "",
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val isDeleted: Boolean = false
)

/**
 * Append-only audit entry. Entries should never be updated or deleted by
 * ordinary users. previousText is retained so an edit cannot erase history.
 */
data class DiscussionEditHistory(
    val id: String = "",
    val teacherUid: String = "",
    val classId: String = "",
    val threadId: String = "",
    val postId: String? = null,
    val targetType: String = TARGET_QUESTION,
    val editedByUid: String = "",
    val previousText: String = "",
    val replacementText: String = "",
    val editedAtMillis: Long = 0L
) {
    companion object {
        const val TARGET_QUESTION = "QUESTION"
        const val TARGET_POST = "POST"
    }
}
