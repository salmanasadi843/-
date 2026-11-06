package ir.darsyar.backend

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

data class TeacherApplicationRecord(
    val userId: String,
    val displayName: String,
    val email: String?,
    val teachingField: String?,
    val institutionName: String?,
    val approvalStatus: String
)

/** Teacher access is granted only after an administrator approves the profile. */
class TeacherAdministrationRepository(private val dataSource: DataSource) {
    fun submitApplication(actorUserId: String, teachingField: String?, institutionName: String?): TeacherApplicationRecord {
        val actor = UUID.fromString(actorUserId)
        require(!teachingField.isNullOrBlank() || !institutionName.isNullOrBlank()) {
            "At least one teacher application detail is required."
        }
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = connection.prepareStatement(
                    """
                    INSERT INTO teacher_profiles(user_id, approval_status, teaching_field, institution_name)
                    SELECT id, 'pending', ?, ? FROM app_users WHERE id = ? AND status = 'active'
                    ON CONFLICT (user_id) DO UPDATE
                    SET approval_status = 'pending', teaching_field = EXCLUDED.teaching_field,
                        institution_name = EXCLUDED.institution_name,
                        approved_at = NULL, approved_by = NULL, updated_at = now()
                    WHERE teacher_profiles.approval_status IN ('pending', 'rejected')
                    RETURNING user_id::text, approval_status, teaching_field, institution_name
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, teachingField?.trim()?.takeIf { it.isNotEmpty() })
                    statement.setString(2, institutionName?.trim()?.takeIf { it.isNotEmpty() })
                    statement.setObject(3, actor)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) throw IllegalStateException("Application cannot be submitted for this account or its current status.")
                        TeacherApplicationRecord(rows.getString("user_id"), "", null,
                            rows.getString("teaching_field"), rows.getString("institution_name"),
                            rows.getString("approval_status"))
                    }
                }
                connection.writeTeacherAudit(actor, "teacher_application.submitted", actor,
                    """{"teachingField":${jsonValue(teachingField)},"institutionName":${jsonValue(institutionName)},"status":"pending"}""")
                connection.commit()
                return result
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun listPendingApplications(): List<TeacherApplicationRecord> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT u.id::text AS user_id, u.display_name, u.email,
                       tp.teaching_field, tp.institution_name, tp.approval_status
                FROM teacher_profiles tp JOIN app_users u ON u.id = tp.user_id
                WHERE tp.approval_status = 'pending' AND u.status = 'active'
                ORDER BY tp.updated_at, u.display_name
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(TeacherApplicationRecord(
                            rows.getString("user_id"), rows.getString("display_name"), rows.getString("email"),
                            rows.getString("teaching_field"), rows.getString("institution_name"),
                            rows.getString("approval_status")))
                    }
                }
            }
        }

    fun decideApplication(adminUserId: String, targetUserId: String, decision: String): TeacherApplicationRecord? {
        require(decision in setOf("approved", "rejected", "revoked")) { "Unsupported teacher application decision." }
        val admin = UUID.fromString(adminUserId)
        val target = UUID.fromString(targetUserId)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val before = connection.prepareStatement(
                    """
                    SELECT tp.approval_status, u.display_name, u.email, tp.teaching_field, tp.institution_name
                    FROM teacher_profiles tp JOIN app_users u ON u.id = tp.user_id
                    WHERE tp.user_id = ? AND u.status = 'active' FOR UPDATE OF tp, u
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, target)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) null else listOf(
                            rows.getString("approval_status"), rows.getString("display_name"),
                            rows.getString("email"), rows.getString("teaching_field"),
                            rows.getString("institution_name"))
                    }
                } ?: run { connection.rollback(); return null }

                connection.prepareStatement(
                    """
                    UPDATE teacher_profiles
                    SET approval_status = ?, approved_at = CASE WHEN ? = 'approved' THEN now() ELSE NULL END,
                        approved_by = CASE WHEN ? = 'approved' THEN ? ELSE NULL END, updated_at = now()
                    WHERE user_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, decision); statement.setString(2, decision)
                    statement.setString(3, decision); statement.setObject(4, admin)
                    statement.setObject(5, target); check(statement.executeUpdate() == 1)
                }

                if (decision == "approved") {
                    connection.prepareStatement(
                        """
                        INSERT INTO app_roles(user_id, role_name, granted_by) VALUES (?, 'teacher', ?)
                        ON CONFLICT (user_id, role_name)
                        DO UPDATE SET granted_by = EXCLUDED.granted_by, granted_at = now()
                        """.trimIndent()
                    ).use { statement ->
                        statement.setObject(1, target); statement.setObject(2, admin); statement.executeUpdate()
                    }
                } else {
                    connection.prepareStatement("DELETE FROM app_roles WHERE user_id = ? AND role_name = 'teacher'").use {
                        it.setObject(1, target); it.executeUpdate()
                    }
                }

                connection.writeTeacherAudit(admin, "teacher_application.$decision", target,
                    """{"beforeStatus":${jsonValue(before[0])},"afterStatus":${jsonValue(decision)}}""")
                connection.commit()
                return TeacherApplicationRecord(targetUserId, before[1] ?: "", before[2],
                    before[3], before[4], decision)
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }
}

private fun Connection.writeTeacherAudit(actor: UUID, action: String, target: UUID, afterJson: String) {
    prepareStatement(
        """
        INSERT INTO audit_log(actor_user_id, action, entity_type, entity_id, owner_user_id, after_data)
        VALUES (?, ?, 'teacher_profile', ?, ?, ?::jsonb)
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, actor); statement.setString(2, action)
        statement.setObject(3, target); statement.setObject(4, target)
        statement.setString(5, afterJson); statement.executeUpdate()
    }
}

private fun jsonValue(value: String?): String =
    value?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" } ?: "null"
