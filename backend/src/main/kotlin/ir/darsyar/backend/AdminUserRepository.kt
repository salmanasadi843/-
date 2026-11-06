package ir.darsyar.backend

import javax.sql.DataSource

data class AdminUserRecord(
    val userId: String,
    val displayName: String,
    val email: String?,
    val status: String,
    val roles: List<String>,
    val teacherApprovalStatus: String?
)

/**
 * Read-only administrative directory. Mutating roles remains behind the
 * dedicated teacher-approval workflow; this endpoint cannot grant privileges.
 */
class AdminUserRepository(private val dataSource: DataSource) {
    fun listUsers(limit: Int = 50, offset: Int = 0): List<AdminUserRecord> {
        require(limit in 1..100) { "Limit must be between 1 and 100." }
        require(offset >= 0) { "Offset must not be negative." }
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT u.id::text AS user_id, u.display_name, u.email, u.status,
                       COALESCE(
                           (SELECT array_agg(r.role_name ORDER BY r.role_name)
                            FROM app_roles r WHERE r.user_id = u.id),
                           ARRAY[]::text[]
                       ) AS role_names,
                       tp.approval_status AS teacher_approval_status
                FROM app_users u
                LEFT JOIN teacher_profiles tp ON tp.user_id = u.id
                ORDER BY u.created_at, u.id
                LIMIT ? OFFSET ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, limit)
                statement.setInt(2, offset)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val roleArray = rows.getArray("role_names")
                            val roles = try {
                                (roleArray.array as Array<*>).mapNotNull { it?.toString() }
                            } finally {
                                roleArray.free()
                            }
                            add(
                                AdminUserRecord(
                                    userId = rows.getString("user_id"),
                                    displayName = rows.getString("display_name"),
                                    email = rows.getString("email"),
                                    status = rows.getString("status"),
                                    roles = roles,
                                    teacherApprovalStatus = rows.getString("teacher_approval_status")
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
