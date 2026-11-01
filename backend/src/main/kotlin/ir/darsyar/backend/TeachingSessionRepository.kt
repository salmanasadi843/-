package ir.darsyar.backend

import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

data class TeachingSessionRecord(
    val id: String,
    val classId: String,
    val courseId: String?,
    val title: String,
    val courseName: String,
    val professorName: String,
    val dateMs: Long?,
    val audioDurationMs: Long,
    val transcript: String,
    val originalTranscript: String,
    val aiSummary: String?,
    val aiKeyPoints: String?,
    val aiQuizJson: String?,
    val tags: String,
    val isPublished: Boolean,
    val lastEditedMs: Long?,
    val sortOrder: Int,
    val version: Long
)

data class CreateTeachingSession(
    val classId: String,
    val courseId: String? = null,
    val title: String,
    val courseName: String = "",
    val professorName: String = "",
    val dateMs: Long? = null,
    val audioDurationMs: Long = 0,
    val transcript: String = "",
    val originalTranscript: String = "",
    val aiSummary: String? = null,
    val aiKeyPoints: String? = null,
    val aiQuizJson: String? = null,
    val tags: String = "",
    val isPublished: Boolean = true,
    val lastEditedMs: Long? = null,
    val sortOrder: Int = 0
)

data class UpdateTeachingSession(
    val courseId: String?,
    val title: String,
    val courseName: String,
    val professorName: String,
    val dateMs: Long?,
    val audioDurationMs: Long,
    val transcript: String,
    val originalTranscript: String,
    val aiSummary: String?,
    val aiKeyPoints: String?,
    val aiQuizJson: String?,
    val tags: String,
    val isPublished: Boolean,
    val lastEditedMs: Long?,
    val sortOrder: Int
)

/** Sessions belong to a class; a course is an optional grouping within that class. */
class TeachingSessionRepository(private val dataSource: DataSource) {
    fun listVisible(actorUserId: String, roles: Set<AppRole>, classId: String? = null): List<TeachingSessionRecord> {
        val admin = AppRole.SUPER_ADMIN in roles
        val teacher = AppRole.TEACHER in roles
        val student = AppRole.STUDENT in roles
        val sql = """
            SELECT s.id::text, s.class_id::text, s.course_id::text, s.title, s.course_name,
                   s.professor_name, s.date_ms, s.audio_duration_ms, s.transcript,
                   s.original_transcript, s.ai_summary, s.ai_key_points,
                   s.ai_quiz_json::text AS ai_quiz_json, s.tags, s.is_published,
                   s.last_edited_ms, s.sort_order, s.version
            FROM teaching_sessions s
            JOIN teaching_classes c ON c.id = s.class_id
            WHERE s.deleted_at IS NULL
              AND (?::uuid IS NULL OR s.class_id = ?::uuid)
              AND (
                ? OR c.owner_user_id = ?::uuid OR (
                  s.is_published = true AND c.is_published = true AND c.is_hidden = false
                  AND (? OR (? AND (
                    c.visibility = 'public' OR EXISTS (
                      SELECT 1 FROM class_memberships m
                      WHERE m.class_id = c.id AND m.student_user_id = ?::uuid
                        AND m.status = 'active'
                    )
                  )))
                )
              )
            ORDER BY s.class_id, s.sort_order, s.date_ms NULLS LAST, s.title
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, classId?.let(UUID::fromString))
                statement.setObject(2, classId?.let(UUID::fromString))
                statement.setBoolean(3, admin)
                statement.setObject(4, UUID.fromString(actorUserId))
                statement.setBoolean(5, teacher)
                statement.setBoolean(6, student)
                statement.setObject(7, UUID.fromString(actorUserId))
                statement.executeQuery().use { rows ->
                    return buildList { while (rows.next()) add(rows.toSessionRecord()) }
                }
            }
        }
    }

    fun create(actorUserId: String, roles: Set<AppRole>, command: CreateTeachingSession): TeachingSessionRecord {
        require(command.title.isNotBlank()) { "Session title is required." }
        require(command.audioDurationMs >= 0) { "Audio duration cannot be negative." }
        val actor = UUID.fromString(actorUserId)
        val classUuid = UUID.fromString(command.classId)
        val courseUuid = command.courseId?.let(UUID::fromString)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val ownerId = connection.prepareStatement(
                    "SELECT owner_user_id FROM teaching_classes WHERE id = ? FOR UPDATE"
                ).use { st ->
                    st.setObject(1, classUuid)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
                } ?: throw IllegalArgumentException("Class not found.")
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && ownerId == actor)
                ) throw SecurityException("Session creation is not allowed.")
                val created = connection.prepareStatement(
                    """
                    INSERT INTO teaching_sessions(
                      class_id, course_id, title, course_name, professor_name, date_ms,
                      audio_duration_ms, transcript, original_transcript, ai_summary,
                      ai_key_points, ai_quiz_json, tags, is_published, last_edited_ms, sort_order
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                    RETURNING id::text, class_id::text, course_id::text, title, course_name,
                      professor_name, date_ms, audio_duration_ms, transcript, original_transcript,
                      ai_summary, ai_key_points, ai_quiz_json::text AS ai_quiz_json, tags,
                      is_published, last_edited_ms, sort_order, version
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, classUuid); st.setObject(2, courseUuid)
                    st.setString(3, command.title.trim()); st.setString(4, command.courseName)
                    st.setString(5, command.professorName); st.setObject(6, command.dateMs)
                    st.setLong(7, command.audioDurationMs); st.setString(8, command.transcript)
                    st.setString(9, command.originalTranscript); st.setString(10, command.aiSummary)
                    st.setString(11, command.aiKeyPoints); st.setString(12, command.aiQuizJson)
                    st.setString(13, command.tags); st.setBoolean(14, command.isPublished)
                    st.setObject(15, command.lastEditedMs); st.setInt(16, command.sortOrder)
                    st.executeQuery().use { rs -> check(rs.next()); rs.toSessionRecord() }
                }
                connection.writeSessionAudit(actor, "session.created", created, null)
                connection.commit()
                return created
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally { connection.autoCommit = true }
        }
    }

    fun update(actorUserId: String, roles: Set<AppRole>, sessionId: String, command: UpdateTeachingSession): TeachingSessionRecord? {
        require(command.title.isNotBlank()) { "Session title is required." }
        require(command.audioDurationMs >= 0) { "Audio duration cannot be negative." }
        val actor = UUID.fromString(actorUserId)
        val id = UUID.fromString(sessionId)
        val courseUuid = command.courseId?.let(UUID::fromString)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val before = connection.prepareStatement(
                    """
                    SELECT s.id::text, s.class_id::text, s.course_id::text, s.title, s.course_name,
                      s.professor_name, s.date_ms, s.audio_duration_ms, s.transcript,
                      s.original_transcript, s.ai_summary, s.ai_key_points,
                      s.ai_quiz_json::text AS ai_quiz_json, s.tags, s.is_published,
                      s.last_edited_ms, s.sort_order, s.version, c.owner_user_id::text
                    FROM teaching_sessions s JOIN teaching_classes c ON c.id = s.class_id
                    WHERE s.id = ? AND s.deleted_at IS NULL FOR UPDATE OF s
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, id)
                    st.executeQuery().use { rs -> if (rs.next()) rs.toSessionRecord() to rs.getString("owner_user_id") else null }
                } ?: run { connection.rollback(); return null }
                val (old, ownerId) = before
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && ownerId == actorUserId)
                ) throw SecurityException("Session management is not allowed.")
                val updated = connection.prepareStatement(
                    """
                    UPDATE teaching_sessions SET course_id = ?, title = ?, course_name = ?,
                      professor_name = ?, date_ms = ?, audio_duration_ms = ?, transcript = ?,
                      original_transcript = ?, ai_summary = ?, ai_key_points = ?,
                      ai_quiz_json = ?::jsonb, tags = ?, is_published = ?, last_edited_ms = ?,
                      sort_order = ?, version = version + 1, updated_at = now()
                    WHERE id = ?
                    RETURNING id::text, class_id::text, course_id::text, title, course_name,
                      professor_name, date_ms, audio_duration_ms, transcript, original_transcript,
                      ai_summary, ai_key_points, ai_quiz_json::text AS ai_quiz_json, tags,
                      is_published, last_edited_ms, sort_order, version
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, courseUuid); st.setString(2, command.title.trim())
                    st.setString(3, command.courseName); st.setString(4, command.professorName)
                    st.setObject(5, command.dateMs); st.setLong(6, command.audioDurationMs)
                    st.setString(7, command.transcript); st.setString(8, command.originalTranscript)
                    st.setString(9, command.aiSummary); st.setString(10, command.aiKeyPoints)
                    st.setString(11, command.aiQuizJson); st.setString(12, command.tags)
                    st.setBoolean(13, command.isPublished); st.setObject(14, command.lastEditedMs)
                    st.setInt(15, command.sortOrder); st.setObject(16, id)
                    st.executeQuery().use { rs -> check(rs.next()); rs.toSessionRecord() }
                }
                connection.writeSessionAudit(actor, "session.updated", updated, old)
                connection.commit()
                return updated
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally { connection.autoCommit = true }
        }
    }

    private fun java.sql.ResultSet.toSessionRecord() = TeachingSessionRecord(
        id = getString("id"), classId = getString("class_id"), courseId = getString("course_id"),
        title = getString("title"), courseName = getString("course_name"),
        professorName = getString("professor_name"), dateMs = getLong("date_ms").let { if (wasNull()) null else it },
        audioDurationMs = getLong("audio_duration_ms"), transcript = getString("transcript"),
        originalTranscript = getString("original_transcript"), aiSummary = getString("ai_summary"),
        aiKeyPoints = getString("ai_key_points"), aiQuizJson = getString("ai_quiz_json"),
        tags = getString("tags"), isPublished = getBoolean("is_published"),
        lastEditedMs = getLong("last_edited_ms").let { if (wasNull()) null else it },
        sortOrder = getInt("sort_order"), version = getLong("version")
    )
}

private fun Connection.writeSessionAudit(
    actor: UUID, action: String, after: TeachingSessionRecord, before: TeachingSessionRecord?
) {
    fun json(value: TeachingSessionRecord): String =
        """{"id":${JsonPrimitive(value.id)},"classId":${JsonPrimitive(value.classId)},"title":${JsonPrimitive(value.title)},"isPublished":${value.isPublished},"version":${value.version}}"""
    prepareStatement(
        """
        INSERT INTO audit_log(actor_user_id, action, entity_type, entity_id, owner_user_id, before_data, after_data)
        SELECT ?, ?, 'session', ?::uuid, c.owner_user_id, ?::jsonb, ?::jsonb
        FROM teaching_classes c WHERE c.id = ?::uuid
        """.trimIndent()
    ).use { st ->
        st.setObject(1, actor); st.setString(2, action); st.setString(3, after.id)
        st.setString(4, before?.let(::json)); st.setString(5, json(after)); st.setString(6, after.classId)
        check(st.executeUpdate() == 1) { "Session audit record could not be written." }
    }
}
