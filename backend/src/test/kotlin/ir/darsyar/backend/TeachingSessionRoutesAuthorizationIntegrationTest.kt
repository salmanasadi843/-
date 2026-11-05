package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.contentType
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class TeachingSessionRoutesAuthorizationIntegrationTest {
    @Test
    fun `non-owner teacher can read public sessions but cannot mutate them over HTTP`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "session_http_${UUID.randomUUID().toString().replace("-", "")}"

        Flyway.configure().dataSource(jdbcUrl, username, password)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = 4
            connectionInitSql = "SET search_path TO $schema"
        }

        HikariDataSource(config).use { dataSource ->
            val owner = UUID.randomUUID()
            val otherTeacher = UUID.randomUUID()
            dataSource.connection.use { connection ->
                connection.prepareStatement("INSERT INTO app_users(id) VALUES (?), (?)").use { statement ->
                    statement.setObject(1, owner)
                    statement.setObject(2, otherTeacher)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO app_roles(user_id, role_name) VALUES (?, 'teacher'), (?, 'teacher')"
                ).use { statement ->
                    statement.setObject(1, owner)
                    statement.setObject(2, otherTeacher)
                    statement.executeUpdate()
                }
            }

            val classRepository = TeachingClassRepository(dataSource)
            val ownerClass = classRepository.create(
                owner.toString(),
                CreateTeachingClass(owner.toString(), "Owner public class", "", "", "public", true)
            )
            val otherTeacherClass = classRepository.create(
                otherTeacher.toString(),
                CreateTeachingClass(otherTeacher.toString(), "Other teacher class", "", "", "private", true)
            )
            val sessionRepository = TeachingSessionRepository(dataSource)
            val activeSession = sessionRepository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(ownerClass.id, title = "Published session")
            )
            val deletedSession = sessionRepository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(ownerClass.id, title = "Deleted session")
            )
            sessionRepository.setDeleted(owner.toString(), setOf(AppRole.TEACHER), deletedSession.id, true)

            testApplication {
                application {
                    module(
                        dataSource = dataSource,
                        tokenVerifier = IdentityTokenVerifier { token ->
                            VerifiedIdentity(provider = "firebase", subject = token)
                        },
                        userRepository = AuthenticatedUserRepository { provider, subject ->
                            check(provider == "firebase")
                            if (subject == "other-teacher-token") {
                                AuthenticatedUserRecord(
                                    userId = otherTeacher.toString(),
                                    status = "active",
                                    roles = setOf(AppRole.TEACHER)
                                )
                            } else null
                        }
                    )
                }

                val readResponse = client.get("/api/v1/classes/${ownerClass.id}/sessions") {
                    bearerAuth("other-teacher-token")
                }
                assertEquals(HttpStatusCode.OK, readResponse.status)

                val createResponse = client.post("/api/v1/classes/${ownerClass.id}/sessions") {
                    bearerAuth("other-teacher-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Unauthorized HTTP session"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, createResponse.status)

                val updateResponse = client.patch("/api/v1/sessions/${activeSession.id}") {
                    bearerAuth("other-teacher-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Unauthorized update"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, updateResponse.status)

                val reorderResponse = client.post("/api/v1/sessions/${activeSession.id}/reorder") {
                    bearerAuth("other-teacher-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"direction":"up"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, reorderResponse.status)

                val moveResponse = client.post("/api/v1/sessions/${activeSession.id}/move") {
                    bearerAuth("other-teacher-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"targetClassId":"${otherTeacherClass.id}"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, moveResponse.status)

                val deleteResponse = client.delete("/api/v1/sessions/${activeSession.id}") {
                    bearerAuth("other-teacher-token")
                }
                assertEquals(HttpStatusCode.Forbidden, deleteResponse.status)

                val restoreResponse = client.post("/api/v1/sessions/${deletedSession.id}/restore") {
                    bearerAuth("other-teacher-token")
                }
                assertEquals(HttpStatusCode.Forbidden, restoreResponse.status)
            }
        }
    }
}
