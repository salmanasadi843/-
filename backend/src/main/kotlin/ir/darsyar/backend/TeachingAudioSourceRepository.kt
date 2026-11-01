package ir.darsyar.backend

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

enum class AudioSourceKind(val value: String) {
    DIRECT_URL("direct_url"),
    HOSTED_PAGE("hosted_page"),
    EXTERNAL_LINK("external_link"),
    UPLOADED_ASSET("uploaded_asset");

    companion object {
        fun from(value: String): AudioSourceKind =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unsupported audio source kind.")
    }
}

data class TeachingAudioSourceRecord(
    val id: String,
    val sessionId: String,
    val sourceKind: String,
    val sourceUrl: String?,
    val storageKey: String?,
    val title: String?,
    val sortOrder: Int,
    val isActive: Boolean
)

data class CreateTeachingAudioSource(
    val sourceKind: String,
    val sourceUrl: String? = null,
    val storageKey: String? = null,
    val title: String? = null,
    val sortOrder: Int = 0
)

data class UpdateTeachingAudioSource(
    val sourceUrl: String?,
    val storageKey: String?,
    val title: String?,
    val sortOrder: Int
)

/**
 * Audio metadata is independent from the storage provider. The API never accepts
 * a local device path; uploaded assets are addressed by an opaque storage key.
 */
class TeachingAudioSourceRepository(private val dataSource: DataSource) {
    fun listVisible(
        actorUserId: String,
        roles: Set<AppRole>,
        sessionId: String
    ): List<TeachingAudioSourceRecord> {
        val sessionUuid = UUID.fromString(sessionId)
        val sessionVisible = TeachingSessionRepository(dataSource)
            .listVisible(actorUserId, roles)
            .any { it.id == sessionId }
        if (!sessionVisible) return emptyList()

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id::text, session_id::text, source_kind, source_url, storage_key,
                       title, sort_order, is_active
                FROM session_audio_sources
                WHERE session_id = ? AND is_active = true
                ORDER BY sort_order, created_at, id
                """.trimIndent()
            ).use { st ->
                st.setObject(1, sessionUuid)
                st.executeQuery().use { rs ->
                    return buildList {
                        while (rs.next()) add(rs.toAudioSourceRecord())
                    }
                }
            }
        }
    }

    fun create(
        actorUserId: String,
        roles: Set<AppRole>,
        sessionId: String,
        command: CreateTeachingAudioSource
    ): TeachingAudioSourceRecord {
        val kind = AudioSourceKind.from(command.sourceKind)
        validateSource(kind, command.sourceUrl, command.storageKey)
        val actor = UUID.fromString(actorUserId)
        val session = UUID.fromString(sessionId)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val owner = connection.findSessionOwner(session)
                    ?: throw IllegalArgumentException("Session not found.")
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && owner == actor)
                ) throw SecurityException("Audio source management is not allowed.")

                val created = connection.prepareStatement(
                    """
                    INSERT INTO session_audio_sources(
                      session_id, source_kind, source_url, storage_key, title, sort_order
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    RETURNING id::text, session_id::text, source_kind, source_url, storage_key,
                              title, sort_order, is_active
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, session)
                    st.setString(2, kind.value)
                    st.setString(3, command.sourceUrl)
                    st.setString(4, command.storageKey)
                    st.setString(5, command.title)
                    st.setInt(6, command.sortOrder)
                    st.executeQuery().use { rs -> check(rs.next()); rs.toAudioSourceRecord() }
                }
                connection.writeAudioAudit(actor, "audio_source.created", created, owner)
                connection.commit()
                return created
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun update(
        actorUserId: String,
        roles: Set<AppRole>,
        sessionId: String,
        sourceId: String,
        command: UpdateTeachingAudioSource
    ): TeachingAudioSourceRecord? {
        val actor = UUID.fromString(actorUserId)
        val session = UUID.fromString(sessionId)
        val source = UUID.fromString(sourceId)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val before = connection.prepareStatement(
                    """
                    SELECT a.id::text, a.session_id::text, a.source_kind, a.source_url,
                           a.storage_key, a.title, a.sort_order, a.is_active,
                           c.owner_user_id
                    FROM session_audio_sources a
                    JOIN teaching_sessions s ON s.id = a.session_id
                    JOIN teaching_classes c ON c.id = s.class_id
                    WHERE a.id = ? AND a.session_id = ? AND s.deleted_at IS NULL
                    FOR UPDATE OF a
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, source)
                    st.setObject(2, session)
                    st.executeQuery().use { rs ->
                        if (rs.next()) rs.toAudioSourceRecord() to rs.getObject("owner_user_id", UUID::class.java)
                        else null
                    }
                } ?: run { connection.rollback(); return null }
                val (old, owner) = before
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && owner == actor)
                ) throw SecurityException("Audio source management is not allowed.")
                validateSource(AudioSourceKind.from(old.sourceKind), command.sourceUrl, command.storageKey)

                val updated = connection.prepareStatement(
                    """
                    UPDATE session_audio_sources
                    SET source_url = ?, storage_key = ?, title = ?, sort_order = ?
                    WHERE id = ? AND session_id = ?
                    RETURNING id::text, session_id::text, source_kind, source_url, storage_key,
                              title, sort_order, is_active
                    """.trimIndent()
                ).use { st ->
                    st.setString(1, command.sourceUrl)
                    st.setString(2, command.storageKey)
                    st.setString(3, command.title)
                    st.setInt(4, command.sortOrder)
                    st.setObject(5, source)
                    st.setObject(6, session)
                    st.executeQuery().use { rs -> check(rs.next()); rs.toAudioSourceRecord() }
                }
                connection.writeAudioAudit(actor, "audio_source.updated", updated, owner)
                connection.commit()
                return updated
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /** Deactivation is reversible metadata; the underlying storage object is never deleted here. */
    fun deactivate(
        actorUserId: String,
        roles: Set<AppRole>,
        sessionId: String,
        sourceId: String
    ): Boolean {
        val actor = UUID.fromString(actorUserId)
        val session = UUID.fromString(sessionId)
        val source = UUID.fromString(sourceId)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val row = connection.prepareStatement(
                    """
                    SELECT a.id::text, a.session_id::text, a.source_kind, a.source_url,
                           a.storage_key, a.title, a.sort_order, a.is_active, c.owner_user_id
                    FROM session_audio_sources a
                    JOIN teaching_sessions s ON s.id = a.session_id
                    JOIN teaching_classes c ON c.id = s.class_id
                    WHERE a.id = ? AND a.session_id = ? AND s.deleted_at IS NULL
                    FOR UPDATE OF a
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, source)
                    st.setObject(2, session)
                    st.executeQuery().use { rs ->
                        if (rs.next()) rs.toAudioSourceRecord() to rs.getObject("owner_user_id", UUID::class.java)
                        else null
                    }
                } ?: run { connection.rollback(); return false }
                val (old, owner) = row
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && owner == actor)
                ) throw SecurityException("Audio source management is not allowed.")
                if (!old.isActive) {
                    connection.rollback()
                    return true
                }
                connection.prepareStatement(
                    "UPDATE session_audio_sources SET is_active = false WHERE id = ?"
                ).use { st ->
                    st.setObject(1, source)
                    check(st.executeUpdate() == 1)
                }
                connection.writeAudioAudit(actor, "audio_source.deactivated", old.copy(isActive = false), owner)
                connection.commit()
                return true
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun validateSource(kind: AudioSourceKind, sourceUrl: String?, storageKey: String?) {
        if (kind == AudioSourceKind.UPLOADED_ASSET) {
            require(!storageKey.isNullOrBlank()) { "Uploaded assets require a storage key." }
            require(sourceUrl.isNullOrBlank()) { "Uploaded assets must not expose a provider URL." }
        } else {
            require(!sourceUrl.isNullOrBlank()) { "External audio sources require a URL." }
            require(storageKey.isNullOrBlank()) { "External audio sources must not include a storage key." }
            val uri = try { java.net.URI(sourceUrl) }
            catch (error: Exception) { throw IllegalArgumentException("Invalid audio source URL.", error) }
            require(uri.isAbsolute && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) {
                "Audio source URL must be an absolute HTTP(S) URL."
            }
            require(uri.rawUserInfo == null) { "Audio source URL must not contain credentials." }
        }
    }
}

private fun java.sql.ResultSet.toAudioSourceRecord() = TeachingAudioSourceRecord(
    id = getString("id"),
    sessionId = getString("session_id"),
    sourceKind = getString("source_kind"),
    sourceUrl = getString("source_url"),
    storageKey = getString("storage_key"),
    title = getString("title"),
    sortOrder = getInt("sort_order"),
    isActive = getBoolean("is_active")
)

private fun Connection.findSessionOwner(sessionId: UUID): UUID? =
    prepareStatement(
        """
        SELECT c.owner_user_id
        FROM teaching_sessions s
        JOIN teaching_classes c ON c.id = s.class_id
        WHERE s.id = ? AND s.deleted_at IS NULL
        FOR UPDATE OF s
        """.trimIndent()
    ).use { st ->
        st.setObject(1, sessionId)
        st.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
    }

private fun Connection.writeAudioAudit(
    actor: UUID,
    action: String,
    source: TeachingAudioSourceRecord,
    owner: UUID
) {
    prepareStatement(
        """
        INSERT INTO audit_log(actor_user_id, action, entity_type, entity_id, owner_user_id, after_data)
        VALUES (?, ?, 'audio_source', ?::uuid, ?, ?::jsonb)
        """.trimIndent()
    ).use { st ->
        val json = """{"sessionId":${kotlinx.serialization.json.JsonPrimitive(source.sessionId)},"sourceKind":${kotlinx.serialization.json.JsonPrimitive(source.sourceKind)},"isActive":${source.isActive}}"""
        st.setObject(1, actor)
        st.setString(2, action)
        st.setString(3, source.id)
        st.setObject(4, owner)
        st.setString(5, json)
        check(st.executeUpdate() == 1) { "Audio source audit record could not be written." }
    }
}
