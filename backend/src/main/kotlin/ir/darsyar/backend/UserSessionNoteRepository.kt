package ir.darsyar.backend

import java.util.UUID
import javax.sql.DataSource

data class UserSessionNoteRecord(
    val id: String,
    val userId: String,
    val sessionId: String,
    val quotedText: String,
    val noteText: String,
    val startOffset: Int?,
    val endOffset: Int?,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class UserSessionNoteSummaryRecord(
    val id: String,
    val userId: String,
    val sessionId: String,
    val classId: String,
    val classTitle: String,
    val sessionTitle: String,
    val courseName: String,
    val teacherName: String,
    val dateMs: Long?,
    val quotedText: String,
    val noteText: String,
    val startOffset: Int?,
    val endOffset: Int?,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/** Personal annotations never modify the shared transcript or another user's notes. */
class UserSessionNoteRepository(
    private val dataSource: DataSource,
    private val sessionRepository: TeachingSessionRepository
) {
    fun list(userId: String, roles: Set<AppRole>, classId: String, sessionId: String): List<UserSessionNoteRecord> {
        val visible = sessionRepository.findVisible(userId, roles, classId, sessionId) ?: return emptyList()
        val sql = """
            SELECT id::text, user_id::text, session_id::text, quoted_text, note_text,
                   start_offset, end_offset,
                   (extract(epoch from created_at) * 1000)::bigint AS created_at_ms,
                   (extract(epoch from updated_at) * 1000)::bigint AS updated_at_ms
            FROM user_session_notes
            WHERE user_id = ? AND session_id = ? AND deleted_at IS NULL
            ORDER BY updated_at DESC, id
        """.trimIndent()
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                st.setObject(1, UUID.fromString(userId))
                st.setObject(2, UUID.fromString(visible.id))
                st.executeQuery().use { rs ->
                    return buildList { while (rs.next()) add(rs.toNote()) }
                }
            }
        }
    }

    /** Lists only the current user's notes in sessions that remain visible to them. */
    fun listAllVisible(userId: String, roles: Set<AppRole>): List<UserSessionNoteSummaryRecord> {
        val admin = AppRole.SUPER_ADMIN in roles
        val student = AppRole.STUDENT in roles
        val actor = UUID.fromString(userId)
        val sql = """
            SELECT n.id::text, n.user_id::text, n.session_id::text,
                   s.class_id::text, c.title AS class_title, s.title AS session_title,
                   s.course_name, COALESCE(NULLIF(c.teacher_name, ''), 'استاد') AS teacher_name,
                   s.date_ms, n.quoted_text, n.note_text, n.start_offset, n.end_offset,
                   (extract(epoch from n.created_at) * 1000)::bigint AS created_at_ms,
                   (extract(epoch from n.updated_at) * 1000)::bigint AS updated_at_ms
            FROM user_session_notes n
            JOIN teaching_sessions s ON s.id = n.session_id
            JOIN teaching_classes c ON c.id = s.class_id
            WHERE n.user_id = ? AND n.deleted_at IS NULL
              AND s.deleted_at IS NULL AND c.deleted_at IS NULL
              AND (
                ? OR c.owner_user_id = ? OR (
                  s.is_published = true AND c.is_published = true AND c.is_hidden = false
                  AND (
                    c.visibility = 'public' OR (? AND EXISTS (
                      SELECT 1 FROM class_memberships m
                      WHERE m.class_id = c.id AND m.student_user_id = ?
                        AND m.status = 'active'
                    ))
                  )
                )
              )
            ORDER BY n.updated_at DESC, n.id
        """.trimIndent()
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                st.setObject(1, actor)
                st.setBoolean(2, admin)
                st.setObject(3, actor)
                st.setBoolean(4, student)
                st.setObject(5, actor)
                st.executeQuery().use { rs ->
                    return buildList {
                        while (rs.next()) add(
                            UserSessionNoteSummaryRecord(
                                id = rs.getString("id"),
                                userId = rs.getString("user_id"),
                                sessionId = rs.getString("session_id"),
                                classId = rs.getString("class_id"),
                                classTitle = rs.getString("class_title"),
                                sessionTitle = rs.getString("session_title"),
                                courseName = rs.getString("course_name"),
                                teacherName = rs.getString("teacher_name"),
                                dateMs = rs.getObject("date_ms")?.let { rs.getLong("date_ms") },
                                quotedText = rs.getString("quoted_text"),
                                noteText = rs.getString("note_text"),
                                startOffset = rs.getObject("start_offset")?.let { rs.getInt("start_offset") },
                                endOffset = rs.getObject("end_offset")?.let { rs.getInt("end_offset") },
                                createdAtMs = rs.getLong("created_at_ms"),
                                updatedAtMs = rs.getLong("updated_at_ms")
                            )
                        )
                    }
                }
            }
        }
    }

    fun create(
        userId: String, roles: Set<AppRole>, classId: String, sessionId: String,
        quotedText: String, noteText: String, startOffset: Int?, endOffset: Int?
    ): UserSessionNoteRecord {
        validate(quotedText, noteText, startOffset, endOffset)
        val visible = sessionRepository.findVisible(userId, roles, classId, sessionId)
            ?: throw NoSuchElementException("Session is not visible")
        val sql = """
            INSERT INTO user_session_notes(user_id, session_id, quoted_text, note_text, start_offset, end_offset)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id::text, user_id::text, session_id::text, quoted_text, note_text,
                      start_offset, end_offset,
                      (extract(epoch from created_at) * 1000)::bigint AS created_at_ms,
                      (extract(epoch from updated_at) * 1000)::bigint AS updated_at_ms
        """.trimIndent()
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                st.setObject(1, UUID.fromString(userId))
                st.setObject(2, UUID.fromString(visible.id))
                st.setString(3, quotedText.trim())
                st.setString(4, noteText.trim())
                st.setObject(5, startOffset)
                st.setObject(6, endOffset)
                st.executeQuery().use { rs -> check(rs.next()); return rs.toNote() }
            }
        }
    }

    fun update(
        userId: String, roles: Set<AppRole>, noteId: String,
        quotedText: String, noteText: String, startOffset: Int?, endOffset: Int?
    ): UserSessionNoteRecord {
        validate(quotedText, noteText, startOffset, endOffset)
        val user = UUID.fromString(userId)
        val id = UUID.fromString(noteId)
        return dataSource.connection.use { c ->
            val session = c.prepareStatement(
                "SELECT session_id FROM user_session_notes WHERE id = ? AND user_id = ? AND deleted_at IS NULL"
            ).use { st ->
                st.setObject(1, id); st.setObject(2, user)
                st.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
            } ?: throw NoSuchElementException("Note not found")
            val visible = findVisibleSessionForNote(userId, roles, session.toString())
                ?: throw NoSuchElementException("Session is not visible")
            val sql = """
                UPDATE user_session_notes
                SET quoted_text = ?, note_text = ?, start_offset = ?, end_offset = ?, updated_at = now()
                WHERE id = ? AND user_id = ? AND session_id = ? AND deleted_at IS NULL
                RETURNING id::text, user_id::text, session_id::text, quoted_text, note_text,
                          start_offset, end_offset,
                          (extract(epoch from created_at) * 1000)::bigint AS created_at_ms,
                          (extract(epoch from updated_at) * 1000)::bigint AS updated_at_ms
            """.trimIndent()
            c.prepareStatement(sql).use { st ->
                st.setString(1, quotedText.trim()); st.setString(2, noteText.trim())
                st.setObject(3, startOffset); st.setObject(4, endOffset)
                st.setObject(5, id); st.setObject(6, user); st.setObject(7, UUID.fromString(visible.id))
                st.executeQuery().use { rs -> if (rs.next()) rs.toNote() else throw NoSuchElementException("Note not found") }
            }
        }
    }

    fun delete(userId: String, roles: Set<AppRole>, noteId: String): Boolean {
        val user = UUID.fromString(userId)
        val id = UUID.fromString(noteId)
        dataSource.connection.use { c ->
            val session = c.prepareStatement(
                "SELECT session_id FROM user_session_notes WHERE id = ? AND user_id = ? AND deleted_at IS NULL"
            ).use { st ->
                st.setObject(1, id); st.setObject(2, user)
                st.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
            } ?: return false
            if (findVisibleSessionForNote(userId, roles, session.toString()) == null) return false
            return c.prepareStatement(
                "UPDATE user_session_notes SET deleted_at = now(), updated_at = now() WHERE id = ? AND user_id = ? AND deleted_at IS NULL"
            ).use { st ->
                st.setObject(1, id); st.setObject(2, user); st.executeUpdate() == 1
            }
        }
    }

    private fun findVisibleSessionForNote(userId: String, roles: Set<AppRole>, sessionId: String): TeachingSessionRecord? {
        // Resolve the parent ID from the note's session, then reuse the canonical visibility check.
        val classId = dataSource.connection.use { c ->
            c.prepareStatement("SELECT class_id::text FROM teaching_sessions WHERE id = ? AND deleted_at IS NULL").use { st ->
                st.setObject(1, UUID.fromString(sessionId))
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        } ?: return null
        return sessionRepository.findVisible(userId, roles, classId, sessionId)
    }

    private fun validate(quotedText: String, noteText: String, startOffset: Int?, endOffset: Int?) {
        require(noteText.trim().isNotEmpty() && noteText.length <= 20000) { "Note text is empty or too long." }
        require(quotedText.length <= 10000) { "Quoted text is too long." }
        require((startOffset == null && endOffset == null) ||
            (startOffset != null && endOffset != null && startOffset >= 0 && endOffset >= startOffset)) {
            "Invalid transcript selection."
        }
    }

    private fun java.sql.ResultSet.toNote() = UserSessionNoteRecord(
        getString("id"), getString("user_id"), getString("session_id"),
        getString("quoted_text"), getString("note_text"),
        getObject("start_offset")?.let { getInt("start_offset") },
        getObject("end_offset")?.let { getInt("end_offset") },
        getLong("created_at_ms"), getLong("updated_at_ms")
    )
}
