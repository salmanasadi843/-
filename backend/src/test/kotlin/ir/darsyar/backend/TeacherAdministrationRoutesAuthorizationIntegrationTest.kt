package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.contentType
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TeacherAdministrationRoutesAuthorizationIntegrationTest {
    @Test
    fun `only super admin can list and decide teacher applications over HTTP`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "teacher_http_${UUID.randomUUID().toString().replace("-", "")}"

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
                    "INSERT INTO app_users(id, display_name) VALUES (?, 'Admin'), (?, 'Teacher'), (?, 'Student')"
                ).use { statement ->
                    statement.setObject(1, admin)
                    statement.setObject(2, teacher)
                    statement.setObject(3, student)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO app_roles(user_id, role_name)
                    VALUES (?, 'super_admin'), (?, 'teacher'), (?, 'student')
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, admin)
                    statement.setObject(2, teacher)
                    statement.setObject(3, student)
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
                                "admin-token" -> AuthenticatedUserRecord(
                                    userId = admin.toString(), status = "active",
                                    roles = setOf(AppRole.SUPER_ADMIN)
                                )
                                "teacher-token" -> AuthenticatedUserRecord(
                                    userId = teacher.toString(), status = "active",
                                    roles = setOf(AppRole.TEACHER)
                                )
                                "student-token" -> AuthenticatedUserRecord(
                                    userId = student.toString(), status = "active",
                                    roles = setOf(AppRole.STUDENT)
                                )
                                else -> null
                            }
                        }
                    )
                }

                val adminPath = "/api/v1/admin/teacher-applications"
                val applicationPath = "/api/v1/teacher-applications"

                val guestList = client.get(adminPath)
                assertEquals(HttpStatusCode.Unauthorized, guestList.status)

                val studentList = client.get(adminPath) { bearerAuth("student-token") }
                assertEquals(HttpStatusCode.Forbidden, studentList.status)

                val teacherList = client.get(adminPath) { bearerAuth("teacher-token") }
                assertEquals(HttpStatusCode.Forbidden, teacherList.status)

                val guestDecision = client.patch("$adminPath/$student") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"decision":"approved"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, guestDecision.status)

                val studentDecision = client.patch("$adminPath/$student") {
                    bearerAuth("student-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"decision":"approved"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, studentDecision.status)

                val teacherDecision = client.patch("$adminPath/$student") {
                    bearerAuth("teacher-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"decision":"approved"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, teacherDecision.status)

                val submit = client.post(applicationPath) {
                    bearerAuth("student-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"teachingField":"فقه","institutionName":"حوزه علمیه"}""")
                }
                assertEquals(HttpStatusCode.Created, submit.status, submit.bodyAsText())
                assertContains(submit.bodyAsText(), student.toString())

                val adminList = client.get(adminPath) { bearerAuth("admin-token") }
                assertEquals(HttpStatusCode.OK, adminList.status, adminList.bodyAsText())
                assertContains(adminList.bodyAsText(), student.toString())

                val approve = client.patch("$adminPath/$student") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"decision":"approved"}""")
                }
                assertEquals(HttpStatusCode.OK, approve.status, approve.bodyAsText())
                assertContains(approve.bodyAsText(), "\"approvalStatus\":\"approved\"")

                val noLongerPending = client.get(adminPath) { bearerAuth("admin-token") }
                assertEquals(HttpStatusCode.OK, noLongerPending.status)
                kotlin.test.assertFalse(noLongerPending.bodyAsText().contains(student.toString()))

                val invalidDecision = client.patch("$adminPath/$student") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"decision":"make_admin"}""")
                }
                assertEquals(HttpStatusCode.BadRequest, invalidDecision.status)

                val missingApplication = client.patch("$adminPath/${UUID.randomUUID()}") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"decision":"approved"}""")
                }
                assertEquals(HttpStatusCode.NotFound, missingApplication.status)
            }
        }
    }
}
