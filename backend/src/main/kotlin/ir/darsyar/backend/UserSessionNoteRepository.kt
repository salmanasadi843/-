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
        dataSource.connection.use { c ->
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
