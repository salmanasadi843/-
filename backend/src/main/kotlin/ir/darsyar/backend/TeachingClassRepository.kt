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

data class VisibleTeacherRecord(
    val userId: String,
    val displayName: String
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
            WHERE c.deleted_at IS NULL
              AND ((? OR c.owner_user_id = ?)
               OR (c.is_hidden = false AND c.is_published = true AND (
                    c.visibility = 'public' OR (
                        ? = true AND EXISTS (
                            SELECT 1 FROM class_memberships m
                            WHERE m.class_id = c.id
                              AND m.student_user_id = ?::uuid
                              AND m.status = 'active'
                        )
                    )
               )))
            ORDER BY c.owner_user_id, c.sort_order, c.title
        """.trimIndent()

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setBoolean(1, isAdmin)
                statement.setObject(2, UUID.fromString(actorUserId))
                statement.setBoolean(3, isStudent)
                statement.setObject(4, UUID.fromString(actorUserId))
                statement.executeQuery().use { rows ->
                    val result = mutableListOf<TeachingClassRecord>()
                    while (rows.next()) result += rows.toTeachingClassRecord()
                    return result
                }
            }
        }
    }

    /**
     * Returns only teachers who own at least one class visible to the current actor.
     * No email or other account details are exposed by this directory endpoint.
     */
    fun listVisibleTeachers(actorUserId: String, roles: Set<AppRole>): List<VisibleTeacherRecord> {
        val admin = AppRole.SUPER_ADMIN in roles
        val student = AppRole.STUDENT in roles
        val sql = """
            SELECT c.owner_user_id::text AS user_id,
                   COALESCE(
                     NULLIF(MAX(NULLIF(c.teacher_name, '')), ''),
                     NULLIF(u.display_name, ''),
                     'استاد'
                   ) AS display_name
            FROM teaching_classes c
            JOIN app_users u ON u.id = c.owner_user_id
            WHERE c.deleted_at IS NULL
              AND (
                ? OR c.owner_user_id = ? OR (
                  c.is_hidden = false AND c.is_published = true AND (
                    c.visibility = 'public' OR (
                      ? AND EXISTS (
                        SELECT 1 FROM class_memberships m
                        WHERE m.class_id = c.id
                          AND m.student_user_id = ?::uuid
                          AND m.status = 'active'
                      )
                    )
                  )
                )
              )
            GROUP BY c.owner_user_id, u.display_name
            ORDER BY display_name, c.owner_user_id
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setBoolean(1, admin)
                statement.setObject(2, UUID.fromString(actorUserId))
                statement.setBoolean(3, student)
                statement.setObject(4, UUID.fromString(actorUserId))
                statement.executeQuery().use { rows ->
                    return buildList {
                        while (rows.next()) add(
                            VisibleTeacherRecord(rows.getString("user_id"), rows.getString("display_name"))
                        )
                    }
                }
            }
        }
    }

    fun create(actorUserId: String, command: CreateTeachingClass): TeachingClassRecord {
        val actor = UUID.fromString(actorUserId)
        val owner = UUID.fromString(command.ownerUserId)
        require(command.title.isNotBlank()) { "Class title is required." }
        require(command.visibility in setOf("public", "private")) {
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
        require(command.visibility in setOf("public", "private")) {
            "Invalid class visibility."
        }

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val before = connection.prepareStatement(
                    """
                    SELECT id::text, owner_user_id::text, title, description, term,
                           visibility, is_published, is_hidden, sort_order, folder_id::text
                    FROM teaching_classes WHERE id = ? AND deleted_at IS NULL FOR UPDATE
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


    /** Deleted classes are visible only in the owner's/admin's recovery area. */
    fun listDeleted(actorUserId: String, roles: Set<AppRole>): List<TeachingClassRecord> {
        val actor = UUID.fromString(actorUserId)
        val admin = AppRole.SUPER_ADMIN in roles
        if (!admin && AppRole.TEACHER !in roles) throw SecurityException("Class recovery is not allowed.")
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id::text, owner_user_id::text, title, description, term,
                       visibility, is_published, is_hidden, sort_order, folder_id::text
                FROM teaching_classes
                WHERE deleted_at IS NOT NULL AND (? OR owner_user_id = ?)
                ORDER BY deleted_at DESC, owner_user_id, title
                """.trimIndent()
            ).use { st ->
                st.setBoolean(1, admin)
                st.setObject(2, actor)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toTeachingClassRecord()) } }
            }
        }
    }

    /** Soft-deletes/restores a class without touching any nested records. */
    fun setDeleted(actorUserId: String, roles: Set<AppRole>, classId: String, deleted: Boolean): Boolean? {
        val actor = UUID.fromString(actorUserId)
        val id = UUID.fromString(classId)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val before = connection.prepareStatement(
                    """
                    SELECT id::text, owner_user_id::text, title, description, term,
                           visibility, is_published, is_hidden, sort_order, folder_id::text,
                           deleted_at
                    FROM teaching_classes WHERE id = ? FOR UPDATE
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, id)
                    st.executeQuery().use { rs ->
                        if (rs.next()) rs.toTeachingClassRecord() to
                            (rs.getTimestamp("deleted_at") != null) else null
                    }
                } ?: run { connection.rollback(); return null }
                val (old, currentlyDeleted) = before
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && old.ownerUserId == actorUserId)
                ) throw SecurityException("You cannot manage this class.")
                if (currentlyDeleted == deleted) {
                    connection.commit()
                    return false
                }
                connection.prepareStatement(
                    """
                    UPDATE teaching_classes
                    SET deleted_at = CASE WHEN ? THEN now() ELSE NULL END,
                        version = version + 1, updated_at = now()
                    WHERE id = ?
                    """.trimIndent()
                ).use { st ->
                    st.setBoolean(1, deleted)
                    st.setObject(2, id)
                    check(st.executeUpdate() == 1)
                }
                val action = if (deleted) "class.deleted" else "class.restored"
                val beforeJson = """{"id":""" + jsonString(old.id) + ""","title":""" + jsonString(old.title) + ""","deleted":$currentlyDeleted}"""
                val afterJson = """{"id":""" + jsonString(old.id) + ""","title":""" + jsonString(old.title) + ""","deleted":$deleted}"""
                connection.writeAudit(actor, action, old.id, UUID.fromString(old.ownerUserId), beforeJson, afterJson)
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
