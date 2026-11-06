package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AdminUserDirectoryRoutesIntegrationTest {
    @Test
    fun `only super admin can read paginated user directory`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "admin_users_${UUID.randomUUID().toString().replace("-", "")}"

        Flyway.configure().dataSource(jdbcUrl, username, password)
            .schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load().migrate()

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = 4
            connectionInitSql = "SET search_path TO $schema"
        }

        HikariDataSource(config).use { dataSource ->
            val admin = UUID.randomUUID()
            val teacher = UUID.randomUUID()
            val student = UUID.randomUUID()
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO app_users(id, display_name, email) VALUES (?, 'Admin', 'admin@example.invalid'), (?, 'Teacher', 'teacher@example.invalid'), (?, 'Student', 'student@example.invalid')"
                ).use { statement ->
                    statement.setObject(1, admin)
                    statement.setObject(2, teacher)
                    statement.setObject(3, student)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO app_roles(user_id, role_name) VALUES (?, 'super_admin'), (?, 'teacher'), (?, 'student')"
                ).use { statement ->
                    statement.setObject(1, admin)
                    statement.setObject(2, teacher)
                    statement.setObject(3, student)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO teacher_profiles(user_id, approval_status, teaching_field) VALUES (?, 'approved', 'فقه')"
                ).use { statement ->
                    statement.setObject(1, teacher)
                    statement.executeUpdate()
                }
            }

            testApplication {
                application {
                    module(
                        dataSource = dataSource,
                        tokenVerifier = IdentityTokenVerifier { token ->
                            VerifiedIdentity(provider = "firebase", subject = token)
                        },
                        userRepository = AuthenticatedUserRepository { provider, subject ->
                            check(provider == "firebase")
                            when (subject) {
                                "admin-token" -> AuthenticatedUserRecord(admin.toString(), "active", setOf(AppRole.SUPER_ADMIN))
                                "teacher-token" -> AuthenticatedUserRecord(teacher.toString(), "active", setOf(AppRole.TEACHER))
                                "student-token" -> AuthenticatedUserRecord(student.toString(), "active", setOf(AppRole.STUDENT))
                                else -> null
                            }
                        }
                    )
                }

                val path = "/api/v1/admin/users"
                assertEquals(401, client.get(path).status.value)
                assertEquals(403, client.get(path) { bearerAuth("teacher-token") }.status.value)
                assertEquals(403, client.get(path) { bearerAuth("student-token") }.status.value)

                val adminResponse = client.get(path) { bearerAuth("admin-token") }
                assertEquals(200, adminResponse.status.value, adminResponse.bodyAsText())
                assertContains(adminResponse.bodyAsText(), teacher.toString())
                assertContains(adminResponse.bodyAsText(), "teacher@example.invalid")
                assertContains(adminResponse.bodyAsText(), "\"teacherApprovalStatus\":\"approved\"")
                assertContains(adminResponse.bodyAsText(), "\"roles\":[\"teacher\"]")

                val limited = client.get("$path?limit=1&offset=1") { bearerAuth("admin-token") }
                assertEquals(200, limited.status.value)
                assertEquals(1, Regex("\\"userId\\":").findAll(limited.bodyAsText()).count())

                val invalidLimit = client.get("$path?limit=101") { bearerAuth("admin-token") }
                assertEquals(400, invalidLimit.status.value)
                val negativeOffset = client.get("$path?offset=-1") { bearerAuth("admin-token") }
                assertEquals(400, negativeOffset.status.value)
            }
        }
    }
}
