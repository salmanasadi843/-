package ir.darsyar.backend

import java.sql.Connection
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.sql.DataSource

data class TeachingClassRecord(
    val id: String,
    val ownerUserId: String,
    val title: String,
    val description: String,
    val term: String,
    val visibility: String,
    val isPublished: Boolean,
    val isHidden: Boolean,
    val sortOrder: Int,
    val folderId: String?
)

data class CreateTeachingClass(
    val ownerUserId: String,
    val title: String,
    val description: String,
    val term: String,
    val visibility: String,
    val isPublished: Boolean,
    val folderId: String? = null
)

data class UpdateTeachingClass(
    val title: String,
    val description: String,
    val term: String,
    val visibility: String,
    val isPublished: Boolean,
    val isHidden: Boolean,
    val sortOrder: Int,
    val folderId: String? = null
)

/** Database boundary for class operations. Writes and audit entries commit atomically. */
class TeachingClassRepository(private val dataSource: DataSource) {
    fun listVisible(actorUserId: String, roles: Set<AppRole>): List<TeachingClassRecord> {
        val isAdmin = AppRole.SUPER_ADMIN in roles
        val isTeacher = AppRole.TEACHER in roles
        val isStudent = AppRole.STUDENT in roles
        val sql = """
            SELECT c.id::text, c.owner_user_id::text, c.title, c.description,
                   c.term, c.visibility, c.is_published, c.is_hidden, c.sort_order,
                   c.folder_id::text
            FROM teaching_classes c
            WHERE (? OR c.owner_user_id = ?)
               OR (c.is_hidden = false AND c.is_published = true AND (
                    ? = true OR (
                        ? = true AND (
                            c.visibility = 'public' OR EXISTS (
                                SELECT 1 FROM class_memberships m
                                WHERE m.class_id = c.id
                                  AND m.student_user_id = ?::uuid
                                  AND m.status = 'active'
                            )
                        )
                    )
               ))
            ORDER BY c.owner_user_id, c.sort_order, c.title
        """.trimIndent()

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setBoolean(1, isAdmin)
                statement.setObject(2, UUID.fromString(actorUserId))
                statement.setBoolean(3, isTeacher)
                statement.setBoolean(4, isStudent)
                statement.setObject(5, UUID.fromString(actorUserId))
                statement.executeQuery().use { rows ->
                    val result = mutableListOf<TeachingClassRecord>()
                    while (rows.next()) result += rows.toTeachingClassRecord()
                    return result
                }
            }
        }
    }

    fun create(actorUserId: String, command: CreateTeachingClass): TeachingClassRecord {
        val actor = UUID.fromString(actorUserId)
        val owner = UUID.fromString(command.ownerUserId)
        require(command.title.isNotBlank()) { "Class title is required." }
        require(command.visibility in setOf("public", "private", "unlisted")) {
            "Invalid class visibility."
        }

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val created = connection.prepareStatement(
                    """
                    INSERT INTO teaching_classes(
                        owner_user_id, folder_id, title, description, term, visibility, is_published
                    ) VALUES (?, ?::uuid, ?, ?, ?, ?, ?)
                    RETURNING id::text, owner_user_id::text, title, description, term,
                              visibility, is_published, is_hidden, sort_order, folder_id::text
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, owner)
                    statement.setString(2, command.folderId)
                    statement.setString(3, command.title.trim())
                    statement.setString(4, command.description)
                    statement.setString(5, command.term)
                    statement.setString(6, command.visibility)
                    statement.setBoolean(7, command.isPublished)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        rows.toTeachingClassRecord()
                    }
                }
                connection.writeAudit(actor, "class.created", created.id, owner, null, created.toAuditJson())
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

    /** Returns null if missing; ownership and roles are checked under a row lock. */
    fun update(
        actorUserId: String,
        roles: Set<AppRole>,
        classId: String,
        command: UpdateTeachingClass
    ): TeachingClassRecord? {
        val actor = UUID.fromString(actorUserId)
        val id = UUID.fromString(classId)
        require(command.title.isNotBlank()) { "Class title is required." }
        require(command.visibility in setOf("public", "private", "unlisted")) {
            "Invalid class visibility."
        }

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val before = connection.prepareStatement(
                    """
                    SELECT id::text, owner_user_id::text, title, description, term,
                           visibility, is_published, is_hidden, sort_order, folder_id::text
                    FROM teaching_classes WHERE id = ? FOR UPDATE
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.toTeachingClassRecord() else null
                    }
                } ?: run {
                    connection.rollback()
                    return null
                }

                val isOwner = before.ownerUserId == actorUserId
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && isOwner)
                ) throw SecurityException("You cannot manage this class.")

                val updated = connection.prepareStatement(
                    """
                    UPDATE teaching_classes
                    SET title = ?, description = ?, term = ?, visibility = ?,
                        is_published = ?, is_hidden = ?, sort_order = ?, folder_id = ?::uuid,
                        version = version + 1, updated_at = now()
                    WHERE id = ?
                    RETURNING id::text, owner_user_id::text, title, description, term,
                              visibility, is_published, is_hidden, sort_order, folder_id::text
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, command.title.trim())
                    statement.setString(2, command.description)
                    statement.setString(3, command.term)
                    statement.setString(4, command.visibility)
                    statement.setBoolean(5, command.isPublished)
                    statement.setBoolean(6, command.isHidden)
                    statement.setInt(7, command.sortOrder)
                    statement.setString(8, command.folderId)
                    statement.setObject(9, id)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        rows.toTeachingClassRecord()
                    }
                }
                connection.writeAudit(
                    actor, "class.updated", updated.id, UUID.fromString(updated.ownerUserId),
                    before.toAuditJson(), updated.toAuditJson()
                )
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

    private fun java.sql.ResultSet.toTeachingClassRecord() = TeachingClassRecord(
        id = getString("id"),
        ownerUserId = getString("owner_user_id"),
        title = getString("title"),
        description = getString("description"),
        term = getString("term"),
        visibility = getString("visibility"),
        isPublished = getBoolean("is_published"),
        isHidden = getBoolean("is_hidden"),
        sortOrder = getInt("sort_order"),
        folderId = getString("folder_id")
    )

    private fun TeachingClassRecord.toAuditJson(): String =
        """{"id":"$id","ownerUserId":"$ownerUserId","title":${jsonString(title)},"visibility":"$visibility","isPublished":$isPublished,"isHidden":$isHidden,"sortOrder":$sortOrder}"""

    private fun jsonString(value: String): String = JsonPrimitive(value).toString()
}

private fun Connection.writeAudit(
    actor: UUID,
    action: String,
    entityId: String,
    owner: UUID,
    beforeJson: String?,
    afterJson: String
) {
    prepareStatement(
        """
        INSERT INTO audit_log(
            actor_user_id, action, entity_type, entity_id, owner_user_id,
            before_data, after_data
        ) VALUES (?, ?, 'class', ?::uuid, ?, ?::jsonb, ?::jsonb)
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, actor)
        statement.setString(2, action)
        statement.setString(3, entityId)
        statement.setObject(4, owner)
        statement.setString(5, beforeJson)
        statement.setString(6, afterJson)
        statement.executeUpdate()
    }
}
