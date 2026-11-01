package ir.darsyar.backend

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

data class TeachingCourseRecord(
    val id: String,
    val classId: String,
    val title: String,
    val teacherName: String,
    val description: String,
    val createdAtMs: Long?,
    val sortOrder: Int,
    val version: Long
)

data class CreateTeachingCourse(
    val classId: String,
    val title: String,
    val teacherName: String = "",
    val description: String = "",
    val createdAtMs: Long? = null,
    val sortOrder: Int = 0
)

data class UpdateTeachingCourse(
    val title: String,
    val teacherName: String,
    val description: String,
    val createdAtMs: Long?,
    val sortOrder: Int
)

/** Courses are optional groupings inside one class; sessions remain class-owned. */
class TeachingCourseRepository(private val dataSource: DataSource) {
    fun listVisible(actorUserId: String, roles: Set<AppRole>, classId: String? = null): List<TeachingCourseRecord> {
        val visibleClassIds = TeachingClassRepository(dataSource)
            .listVisible(actorUserId, roles)
            .map { it.id }
            .filter { classId == null || it == classId }
        if (visibleClassIds.isEmpty()) return emptyList()
        val placeholders = visibleClassIds.joinToString(",") { "?" }
        val sql = """
            SELECT id::text, class_id::text, title, teacher_name, description,
                   created_at_ms, sort_order, version
            FROM teaching_courses
            WHERE deleted_at IS NULL AND class_id IN ($placeholders)
            ORDER BY class_id, sort_order, created_at_ms NULLS LAST, id
        """.trimIndent()
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { st ->
                visibleClassIds.forEachIndexed { index, id -> st.setObject(index + 1, UUID.fromString(id)) }
                st.executeQuery().use { rs ->
                    return buildList { while (rs.next()) add(rs.toCourseRecord()) }
                }
            }
        }
    }

    fun create(actorUserId: String, roles: Set<AppRole>, command: CreateTeachingCourse): TeachingCourseRecord {
        val actor = UUID.fromString(actorUserId)
        val classUuid = UUID.fromString(command.classId)
        require(command.title.isNotBlank()) { "Course title is required." }
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val owner = connection.findCourseClassOwner(classUuid)
                    ?: throw IllegalArgumentException("Class not found.")
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && owner == actor)
                ) throw SecurityException("Course creation is not allowed.")
                val created = connection.prepareStatement(
                    """
                    INSERT INTO teaching_courses(class_id, title, teacher_name, description, created_at_ms, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?)
                    RETURNING id::text, class_id::text, title, teacher_name, description, created_at_ms, sort_order, version
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, classUuid)
                    st.setString(2, command.title.trim())
                    st.setString(3, command.teacherName)
                    st.setString(4, command.description)
                    if (command.createdAtMs == null) st.setNull(5, java.sql.Types.BIGINT) else st.setLong(5, command.createdAtMs)
                    st.setInt(6, command.sortOrder)
                    st.executeQuery().use { rs -> check(rs.next()); rs.toCourseRecord() }
                }
                connection.courseAudit(actor, "course.created", created, owner, null)
                connection.commit()
                return created
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally { connection.autoCommit = true }
        }
    }

    fun update(actorUserId: String, roles: Set<AppRole>, courseId: String, command: UpdateTeachingCourse): TeachingCourseRecord? {
        val actor = UUID.fromString(actorUserId)
        val id = UUID.fromString(courseId)
        require(command.title.isNotBlank()) { "Course title is required." }
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val beforeAndOwner = connection.prepareStatement(
                    """
                    SELECT co.id::text, co.class_id::text, co.title, co.teacher_name, co.description,
                           co.created_at_ms, co.sort_order, co.version, c.owner_user_id
                    FROM teaching_courses co
                    JOIN teaching_classes c ON c.id = co.class_id
                    WHERE co.id = ? AND co.deleted_at IS NULL
                    FOR UPDATE OF co
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, id)
                    st.executeQuery().use { rs ->
                        if (rs.next()) rs.toCourseRecord() to rs.getObject("owner_user_id", UUID::class.java) else null
                    }
                } ?: run { connection.rollback(); return null }
                val (before, owner) = beforeAndOwner
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && owner == actor)
                ) throw SecurityException("Course management is not allowed.")
                val updated = connection.prepareStatement(
                    """
                    UPDATE teaching_courses
                    SET title = ?, teacher_name = ?, description = ?, created_at_ms = ?,
                        sort_order = ?, version = version + 1
                    WHERE id = ?
                    RETURNING id::text, class_id::text, title, teacher_name, description, created_at_ms, sort_order, version
                    """.trimIndent()
                ).use { st ->
                    st.setString(1, command.title.trim())
                    st.setString(2, command.teacherName)
                    st.setString(3, command.description)
                    if (command.createdAtMs == null) st.setNull(4, java.sql.Types.BIGINT) else st.setLong(4, command.createdAtMs)
                    st.setInt(5, command.sortOrder)
                    st.setObject(6, id)
                    st.executeQuery().use { rs -> check(rs.next()); rs.toCourseRecord() }
                }
                connection.courseAudit(actor, "course.updated", updated, owner, before)
                connection.commit()
                return updated
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally { connection.autoCommit = true }
        }
    }

    /** A course can only be soft-deleted while it has no sessions. */
    fun delete(actorUserId: String, roles: Set<AppRole>, courseId: String): Boolean {
        val actor = UUID.fromString(actorUserId)
        val id = UUID.fromString(courseId)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val pair = connection.prepareStatement(
                    """
                    SELECT co.id::text, co.class_id::text, co.title, co.teacher_name, co.description,
                           co.created_at_ms, co.sort_order, co.version, c.owner_user_id
                    FROM teaching_courses co
                    JOIN teaching_classes c ON c.id = co.class_id
                    WHERE co.id = ? AND co.deleted_at IS NULL
                    FOR UPDATE OF co
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, id)
                    st.executeQuery().use { rs ->
                        if (rs.next()) rs.toCourseRecord() to rs.getObject("owner_user_id", UUID::class.java) else null
                    }
                } ?: run { connection.rollback(); return false }
                val (before, owner) = pair
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && owner == actor)
                ) throw SecurityException("Course deletion is not allowed.")
                val hasSessions = connection.prepareStatement(
                    "SELECT EXISTS(SELECT 1 FROM teaching_sessions WHERE course_id = ?)"
                ).use { st ->
                    st.setObject(1, id)
                    st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                }
                if (hasSessions) throw IllegalStateException("Move sessions out of this course before deleting it.")
                connection.prepareStatement(
                    "UPDATE teaching_courses SET deleted_at = now(), version = version + 1 WHERE id = ?"
                ).use { st -> st.setObject(1, id); check(st.executeUpdate() == 1) }
                connection.courseAudit(actor, "course.deleted", before.copy(version = before.version + 1), owner, before)
                connection.commit()
                return true
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally { connection.autoCommit = true }
        }
    }
}

private fun java.sql.ResultSet.toCourseRecord() = TeachingCourseRecord(
    id = getString("id"),
    classId = getString("class_id"),
    title = getString("title"),
    teacherName = getString("teacher_name"),
    description = getString("description"),
    createdAtMs = getLong("created_at_ms").let { if (wasNull()) null else it },
    sortOrder = getInt("sort_order"),
    version = getLong("version")
)

private fun Connection.findCourseClassOwner(classId: UUID): UUID? =
    prepareStatement(
        "SELECT owner_user_id FROM teaching_classes WHERE id = ? FOR UPDATE"
    ).use { st ->
        st.setObject(1, classId)
        st.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
    }

private fun Connection.courseAudit(
    actor: UUID,
    action: String,
    current: TeachingCourseRecord,
    owner: UUID,
    before: TeachingCourseRecord?
) {
    fun json(course: TeachingCourseRecord) = buildJsonObject {
        put("id", JsonPrimitive(course.id))
        put("classId", JsonPrimitive(course.classId))
        put("title", JsonPrimitive(course.title))
        put("teacherName", JsonPrimitive(course.teacherName))
        put("description", JsonPrimitive(course.description))
        put("createdAtMs", course.createdAtMs?.let(::JsonPrimitive) ?: JsonNull)
        put("sortOrder", JsonPrimitive(course.sortOrder))
        put("version", JsonPrimitive(course.version))
    }.toString()
    prepareStatement(
        """
        INSERT INTO audit_log(actor_user_id, action, entity_type, entity_id, owner_user_id, before_data, after_data)
        VALUES (?, ?, 'course', ?::uuid, ?::uuid, ?::jsonb, ?::jsonb)
        """.trimIndent()
    ).use { st ->
        st.setObject(1, actor)
        st.setString(2, action)
        st.setString(3, current.id)
        st.setObject(4, owner)
        st.setString(5, before?.let(::json))
        st.setString(6, json(current))
        check(st.executeUpdate() == 1) { "Course audit record could not be written." }
    }
}
