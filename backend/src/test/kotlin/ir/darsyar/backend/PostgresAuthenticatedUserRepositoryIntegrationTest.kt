package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Runs against an isolated PostgreSQL schema when AUTH_REPOSITORY_TEST_DB_URL
 * is configured. CI supplies a disposable PostgreSQL service; local unit-test
 * runs can omit the variables and skip this integration test.
 */
class PostgresAuthenticatedUserRepositoryIntegrationTest {
    @Test
    fun `loads database roles and only grants approved teacher role`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")

        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = System.getenv("AUTH_REPOSITORY_TEST_DB_SCHEMA") ?: "auth_repository_test"
        require(schema.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) { "Invalid test schema name" }

        Flyway.configure()
            .dataSource(jdbcUrl, username, password)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = 2
            connectionInitSql = "SET search_path TO $schema"
        }
        HikariDataSource(config).use { dataSource ->
            val adminId = UUID.randomUUID().toString()
            val approvedTeacherId = UUID.randomUUID().toString()
            val pendingTeacherId = UUID.randomUUID().toString()

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO app_users(id, status) VALUES (?, 'active'), (?, 'active'), (?, 'active')"
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(adminId))
                    statement.setObject(2, UUID.fromString(approvedTeacherId))
                    statement.setObject(3, UUID.fromString(pendingTeacherId))
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO auth_identities(user_id, provider, provider_subject)
                    VALUES (?, 'firebase', ?), (?, 'firebase', ?), (?, 'firebase', ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(adminId))
                    statement.setString(2, "admin-${UUID.randomUUID()}")
                    statement.setObject(3, UUID.fromString(approvedTeacherId))
                    statement.setString(4, "approved-${UUID.randomUUID()}")
                    statement.setObject(5, UUID.fromString(pendingTeacherId))
                    statement.setString(6, "pending-${UUID.randomUUID()}")
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO app_roles(user_id, role_name)
                    VALUES (?, 'super_admin'), (?, 'teacher'), (?, 'student'), (?, 'teacher'), (?, 'student')
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(adminId))
                    statement.setObject(2, UUID.fromString(approvedTeacherId))
                    statement.setObject(3, UUID.fromString(approvedTeacherId))
                    statement.setObject(4, UUID.fromString(pendingTeacherId))
                    statement.setObject(5, UUID.fromString(pendingTeacherId))
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO teacher_profiles(user_id, approval_status)
                    VALUES (?, 'approved'), (?, 'pending')
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(approvedTeacherId))
                    statement.setObject(2, UUID.fromString(pendingTeacherId))
                    statement.executeUpdate()
                }
            }

            val repository = PostgresAuthenticatedUserRepository(dataSource)

            val admin = repository.findByIdentity("firebase", findSubject(dataSource, adminId))
            assertNotNull(admin)
            assertEquals(adminId, admin.userId)
            assertEquals(setOf(AppRole.SUPER_ADMIN), admin.roles)

            val approvedTeacher = repository.findByIdentity(
                "firebase",
                findSubject(dataSource, approvedTeacherId)
            )
            assertNotNull(approvedTeacher)
            assertEquals(setOf(AppRole.TEACHER, AppRole.STUDENT), approvedTeacher.roles)

            val pendingTeacher = repository.findByIdentity(
                "firebase",
                findSubject(dataSource, pendingTeacherId)
            )
            assertNotNull(pendingTeacher)
            assertEquals(setOf(AppRole.STUDENT), pendingTeacher.roles)

            assertNull(repository.findByIdentity("firebase", "unlinked-${UUID.randomUUID()}"))
        }
    }

    private fun findSubject(dataSource: HikariDataSource, userId: String): String =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT provider_subject FROM auth_identities WHERE user_id = ?"
            ).use { statement ->
                statement.setObject(1, UUID.fromString(userId))
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "Test identity was not inserted" }
                    rows.getString(1)
                }
            }
        }
}
