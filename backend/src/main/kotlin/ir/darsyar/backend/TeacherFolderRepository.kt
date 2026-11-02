package ir.darsyar.backend

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

data class TeacherFolderRecord(
    val id: String,
    val ownerUserId: String,
    val parentFolderId: String?,
    val name: String,
    val sortOrder: Int,
    val isHidden: Boolean,
    val version: Long
)

data class CreateTeacherFolder(
    val ownerUserId: String,
    val parentFolderId: String?,
    val name: String,
    val sortOrder: Int = 0
)

data class UpdateTeacherFolder(
    val parentFolderId: String?,
    val name: String,
    val sortOrder: Int,
    val isHidden: Boolean
)

/**
 * Owner-scoped folder operations. Folder writes and audit entries are atomic.
 * Admins can manage any owner's folders; teachers can manage only their own.
 */
class TeacherFolderRepository(private val dataSource: DataSource) {
    fun listVisible(actorUserId: String, roles: Set<AppRole>): List<TeacherFolderRecord> {
        val admin = AppRole.SUPER_ADMIN in roles
        val teacher = AppRole.TEACHER in roles
        val student = AppRole.STUDENT in roles
        val sql = """
            SELECT f.id::text, f.owner_user_id::text, f.parent_folder_id::text,
                   f.name, f.sort_order, f.is_hidden, f.version
            FROM teacher_folders f
            WHERE f.deleted_at IS NULL AND (
                ? OR f.owner_user_id = ?::uuid OR EXISTS (
                    SELECT 1 FROM teaching_classes c
                    WHERE c.folder_id = f.id AND c.owner_user_id = f.owner_user_id
                      AND c.deleted_at IS NULL AND c.is_published = true AND c.is_hidden = false
                      AND (
                          c.visibility = 'public' OR (? AND EXISTS (
                              SELECT 1 FROM class_memberships m
                              WHERE m.class_id = c.id AND m.student_user_id = ?::uuid
                                AND m.status = 'active'
                          ))
                      )
                )
            ) AND (? OR f.owner_user_id = ?::uuid OR f.is_hidden = false)
            ORDER BY f.owner_user_id, f.parent_folder_id NULLS FIRST, f.sort_order, f.name
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setBoolean(1, admin)
                statement.setString(2, actorUserId)
                statement.setBoolean(3, student)
                statement.setString(4, actorUserId)
                statement.setBoolean(5, admin)
                statement.setString(6, actorUserId)
                statement.executeQuery().use { rows ->
                    val result = mutableListOf<TeacherFolderRecord>()
                    while (rows.next()) result += rows.toFolderRecord()
                    return result
                }
            }
        }
    }

    fun create(actorUserId: String, roles: Set<AppRole>, command: CreateTeacherFolder): TeacherFolderRecord {
        val actor = UUID.fromString(actorUserId)
        val owner = UUID.fromString(command.ownerUserId)
        require(command.name.isNotBlank()) { "Folder name is required." }
        require(AppRole.SUPER_ADMIN in roles || (AppRole.TEACHER in roles && actor == owner)) {
            "Folder creation is forbidden."
        }
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (command.parentFolderId != null) {
                    val parentOwner = connection.prepareStatement(
                        "SELECT owner_user_id::text FROM teacher_folders WHERE id = ?::uuid AND deleted_at IS NULL"
                    ).use { statement ->
                        statement.setString(1, command.parentFolderId)
                        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                    }
                    require(parentOwner == owner.toString()) {
                        "Parent folder must belong to the same teacher."
                    }
                }
                val created = connection.prepareStatement(
                    """
                    INSERT INTO teacher_folders(owner_user_id, parent_folder_id, name, sort_order)
                    VALUES (?, ?::uuid, ?, ?)
                    RETURNING id::text, owner_user_id::text, parent_folder_id::text,
                              name, sort_order, is_hidden, version
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, owner)
                    statement.setString(2, command.parentFolderId)
                    statement.setString(3, command.name.trim())
                    statement.setInt(4, command.sortOrder)
                    statement.executeQuery().use { rows -> check(rows.next()); rows.toFolderRecord() }
                }
                connection.folderAudit(actor, "teacher_folder.created", created, null)
                connection.commit()
                created
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally { connection.autoCommit = true }
        }
    }

    fun update(
        actorUserId: String,
        roles: Set<AppRole>,
        folderId: String,
        command: UpdateTeacherFolder
    ): TeacherFolderRecord? {
        val actor = UUID.fromString(actorUserId)
        val id = UUID.fromString(folderId)
        require(command.name.isNotBlank()) { "Folder name is required." }
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val before = connection.prepareStatement(
                    """
                    SELECT id::text, owner_user_id::text, parent_folder_id::text,
                           name, sort_order, is_hidden, version
                    FROM teacher_folders WHERE id = ? AND deleted_at IS NULL FOR UPDATE
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.toFolderRecord() else null }
                } ?: run { connection.rollback(); return null }

                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && before.ownerUserId == actorUserId)
                ) throw SecurityException("You cannot manage this folder.")

                if (command.parentFolderId != null) {
                    val parent = UUID.fromString(command.parentFolderId)
                    if (parent == id) throw IllegalArgumentException("A folder cannot be its own parent.")
                    val parentOwner = connection.prepareStatement(
                        "SELECT owner_user_id::text FROM teacher_folders WHERE id = ? AND deleted_at IS NULL"
                    ).use { statement ->
                        statement.setObject(1, parent)
                        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                    }
                    require(parentOwner == before.ownerUserId) {
                        "Parent folder must belong to the same teacher."
                    }
                    val cycle = connection.prepareStatement(
                        """
                        WITH RECURSIVE descendants(id) AS (
                            SELECT id FROM teacher_folders WHERE parent_folder_id = ? AND deleted_at IS NULL
                            UNION ALL
                            SELECT f.id FROM teacher_folders f
                            JOIN descendants d ON f.parent_folder_id = d.id
                            WHERE f.deleted_at IS NULL
                        )
                        SELECT EXISTS(SELECT 1 FROM descendants WHERE id = ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setObject(1, id)
                        statement.setObject(2, parent)
                        statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                    }
                    if (cycle) throw IllegalArgumentException("Folder move would create a cycle.")
                }

                val updated = connection.prepareStatement(
                    """
                    UPDATE teacher_folders
                    SET parent_folder_id = ?::uuid, name = ?, sort_order = ?, is_hidden = ?,
                        version = version + 1, updated_at = now()
                    WHERE id = ?
                    RETURNING id::text, owner_user_id::text, parent_folder_id::text,
                              name, sort_order, is_hidden, version
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, command.parentFolderId)
                    statement.setString(2, command.name.trim())
                    statement.setInt(3, command.sortOrder)
                    statement.setBoolean(4, command.isHidden)
                    statement.setObject(5, id)
                    statement.executeQuery().use { rows -> check(rows.next()); rows.toFolderRecord() }
                }
                connection.folderAudit(actor, "teacher_folder.updated", updated, before)
                connection.commit()
                updated
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally { connection.autoCommit = true }
        }
    }

    /** Soft-delete only empty folders; content must be moved explicitly first. */
    fun delete(actorUserId: String, roles: Set<AppRole>, folderId: String): Boolean {
        val actor = UUID.fromString(actorUserId)
        val id = UUID.fromString(folderId)
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val before = connection.prepareStatement(
                    """
                    SELECT id::text, owner_user_id::text, parent_folder_id::text,
                           name, sort_order, is_hidden, version
                    FROM teacher_folders WHERE id = ? AND deleted_at IS NULL FOR UPDATE
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.toFolderRecord() else null }
                } ?: run { connection.rollback(); return false }

                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && before.ownerUserId == actorUserId)
                ) throw SecurityException("You cannot delete this folder.")

                val hasChildrenOrClasses = connection.prepareStatement(
                    """
                    SELECT
                      EXISTS(SELECT 1 FROM teacher_folders WHERE parent_folder_id = ? AND deleted_at IS NULL)
                      OR EXISTS(SELECT 1 FROM teaching_classes WHERE folder_id = ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, id)
                    statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                }
                if (hasChildrenOrClasses) throw IllegalStateException("Move child folders and classes before deleting.")

                connection.prepareStatement(
                    "UPDATE teacher_folders SET deleted_at = now(), version = version + 1, updated_at = now() WHERE id = ?"
                ).use { statement -> statement.setObject(1, id); statement.executeUpdate() }
                connection.folderAudit(actor, "teacher_folder.deleted", before.copy(version = before.version + 1), before)
                connection.commit()
                true
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally { connection.autoCommit = true }
        }
    }

    private fun java.sql.ResultSet.toFolderRecord() = TeacherFolderRecord(
        id = getString("id"),
        ownerUserId = getString("owner_user_id"),
        parentFolderId = getString("parent_folder_id"),
        name = getString("name"),
        sortOrder = getInt("sort_order"),
        isHidden = getBoolean("is_hidden"),
        version = getLong("version")
    )
}

private fun Connection.folderAudit(
    actor: UUID,
    action: String,
    current: TeacherFolderRecord,
    before: TeacherFolderRecord?
) {
    fun json(folder: TeacherFolderRecord): String =
        kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(folder.id))
            put("ownerUserId", kotlinx.serialization.json.JsonPrimitive(folder.ownerUserId))
            put(
                "parentFolderId",
                folder.parentFolderId?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                    ?: kotlinx.serialization.json.JsonNull
            )
            put("name", kotlinx.serialization.json.JsonPrimitive(folder.name))
            put("sortOrder", kotlinx.serialization.json.JsonPrimitive(folder.sortOrder))
            put("isHidden", kotlinx.serialization.json.JsonPrimitive(folder.isHidden))
            put("version", kotlinx.serialization.json.JsonPrimitive(folder.version))
        }.toString()
    prepareStatement(
        """
        INSERT INTO audit_log(actor_user_id, action, entity_type, entity_id, owner_user_id, before_data, after_data)
        VALUES (?, ?, 'teacher_folder', ?::uuid, ?::uuid, ?::jsonb, ?::jsonb)
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, actor)
        statement.setString(2, action)
        statement.setString(3, current.id)
        statement.setString(4, current.ownerUserId)
        statement.setString(5, before?.let(::json))
        statement.setString(6, json(current))
        statement.executeUpdate()
    }
}
