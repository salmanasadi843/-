package ir.darsyar.backend

import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

data class UserSessionStateRecord(
    val userId: String,
    val sessionId: String,
    val isFavorite: Boolean,
    val lastPlaybackPositionMs: Long,
    val updatedAtMs: Long
)

/**
 * Stores per-user learning state without changing shared session content.
 * Visibility is checked before both reads and writes; a user cannot bookmark
 * or save playback state for a session they cannot currently access.
 */
class UserSessionStateRepository(
    private val dataSource: DataSource,
    private val sessionRepository: TeachingSessionRepository
) {
    fun get(
        userId: String,
        roles: Set<AppRole>,
        classId: String,
        sessionId: String
    ): UserSessionStateRecord? {
        val visible = sessionRepository.findVisible(userId, roles, classId, sessionId) ?: return null
        val sql = """
            SELECT is_favorite, last_playback_position_ms,
                   (extract(epoch from updated_at) * 1000)::bigint AS updated_at_ms
            FROM user_session_state
            WHERE user_id = ? AND session_id = ?
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, UUID.fromString(userId))
                statement.setObject(2, UUID.fromString(visible.id))
                statement.executeQuery().use { rows ->
                    return if (rows.next()) UserSessionStateRecord(
                        userId, visible.id, rows.getBoolean("is_favorite"),
                        rows.getLong("last_playback_position_ms"), rows.getLong("updated_at_ms")
                    ) else UserSessionStateRecord(userId, visible.id, false, 0L, 0L)
                }
            }
        }
    }

    fun put(
        userId: String,
        roles: Set<AppRole>,
        classId: String,
        sessionId: String,
        isFavorite: Boolean,
        lastPlaybackPositionMs: Long
    ): UserSessionStateRecord {
        require(lastPlaybackPositionMs in 0..604_800_000L) { "Invalid playback position" }
        val visible = sessionRepository.findVisible(userId, roles, classId, sessionId)
            ?: throw NoSuchElementException("Session is not visible")
        val sql = """
            INSERT INTO user_session_state(user_id, session_id, is_favorite, last_playback_position_ms, updated_at)
            VALUES (?, ?, ?, ?, now())
            ON CONFLICT(user_id, session_id) DO UPDATE SET
                is_favorite = EXCLUDED.is_favorite,
                last_playback_position_ms = EXCLUDED.last_playback_position_ms,
                updated_at = now()
            RETURNING is_favorite, last_playback_position_ms,
                      (extract(epoch from updated_at) * 1000)::bigint AS updated_at_ms
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, UUID.fromString(userId))
                statement.setObject(2, UUID.fromString(visible.id))
                statement.setBoolean(3, isFavorite)
                statement.setLong(4, lastPlaybackPositionMs)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "Session state was not saved" }
                    return UserSessionStateRecord(
                        userId, visible.id, rows.getBoolean("is_favorite"),
                        rows.getLong("last_playback_position_ms"), rows.getLong("updated_at_ms")
                    )
                }
            }
        }
    }
}
