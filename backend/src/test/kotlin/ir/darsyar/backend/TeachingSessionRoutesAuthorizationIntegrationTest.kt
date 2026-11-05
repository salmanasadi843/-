package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.bearerAuth
import io.ktor.http.contentType
import io.ktor.client.request.delete
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
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse

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
            val admin = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val otherTeacher = UUID.randomUUID()
            val student = UUID.randomUUID()
            dataSource.connection.use { connection ->
                connection.prepareStatement("INSERT INTO app_users(id) VALUES (?), (?), (?), (?)").use { statement ->
                    statement.setObject(1, admin)
                    statement.setObject(2, owner)
                    statement.setObject(3, otherTeacher)
                    statement.setObject(4, student)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO app_roles(user_id, role_name) VALUES (?, 'super_admin'), (?, 'teacher'), (?, 'teacher'), (?, 'student')"
                ).use { statement ->
                    statement.setObject(1, admin)
                    statement.setObject(2, owner)
                    statement.setObject(3, otherTeacher)
                    statement.setObject(4, student)
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
            val memberPrivateClass = classRepository.create(
                otherTeacher.toString(),
                CreateTeachingClass(otherTeacher.toString(), "Member-only private class", "", "", "private", true)
            )
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO class_memberships(class_id, student_user_id, status) VALUES (?::uuid, ?, 'active')"
                ).use { statement ->
                    statement.setString(1, memberPrivateClass.id)
                    statement.setObject(2, student)
                    statement.executeUpdate()
                }
            }
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
            val foreignSession = sessionRepository.create(
                otherTeacher.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(otherTeacherClass.id, title = "Foreign teacher session")
            )
            val memberPrivateSession = sessionRepository.create(
                otherTeacher.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(memberPrivateClass.id, title = "Member-only private session")
            )
            val ownerManagedSession = sessionRepository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(ownerClass.id, title = "Owner managed session")
            )

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
                                "owner-token" -> AuthenticatedUserRecord(
                                    userId = owner.toString(), status = "active",
                                    roles = setOf(AppRole.TEACHER)
                                )
                                "other-teacher-token" -> AuthenticatedUserRecord(
                                    userId = otherTeacher.toString(), status = "active",
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

                // The owner may perform the same mutations on their own content.
                val ownerCreate = client.post("/api/v1/classes/${ownerClass.id}/sessions") {
                    bearerAuth("owner-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Owner-created session"}""")
                }
                assertEquals(HttpStatusCode.Created, ownerCreate.status)

                val ownerUpdate = client.patch("/api/v1/sessions/${ownerManagedSession.id}") {
                    bearerAuth("owner-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Owner updated session"}""")
                }
                assertEquals(HttpStatusCode.OK, ownerUpdate.status)

                val ownerReorder = client.post("/api/v1/sessions/${ownerManagedSession.id}/reorder") {
                    bearerAuth("owner-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"direction":"up"}""")
                }
                assertEquals(HttpStatusCode.OK, ownerReorder.status)

                val ownerDelete = client.delete("/api/v1/sessions/${ownerManagedSession.id}") {
                    bearerAuth("owner-token")
                }
                assertEquals(HttpStatusCode.NoContent, ownerDelete.status)

                val ownerRestore = client.post("/api/v1/sessions/${ownerManagedSession.id}/restore") {
                    bearerAuth("owner-token")
                }
                assertEquals(HttpStatusCode.NoContent, ownerRestore.status)

                // A student is authenticated but cannot create or mutate teacher content.
                val studentCreate = client.post("/api/v1/classes/${ownerClass.id}/sessions") {
                    bearerAuth("student-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Student-created session"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, studentCreate.status)

                val studentUpdate = client.patch("/api/v1/sessions/${activeSession.id}") {
                    bearerAuth("student-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Student update"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, studentUpdate.status)

                val studentDelete = client.delete("/api/v1/sessions/${activeSession.id}") {
                    bearerAuth("student-token")
                }
                assertEquals(HttpStatusCode.Forbidden, studentDelete.status)

                // The main admin may manage another teacher's content, including moving
                // it across teacher ownership while preserving the session identity.
                val adminMove = client.post("/api/v1/sessions/${foreignSession.id}/move") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"targetClassId":"${ownerClass.id}"}""")
                }
                assertEquals(HttpStatusCode.OK, adminMove.status)
                val movedSessionJson = adminMove.bodyAsText()
                assertContains(movedSessionJson, foreignSession.id)
                assertContains(movedSessionJson, ownerClass.id)

                val adminUpdate = client.patch("/api/v1/sessions/${foreignSession.id}") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Admin updated foreign session"}""")
                }
                assertEquals(HttpStatusCode.OK, adminUpdate.status)

                val adminDelete = client.delete("/api/v1/sessions/${foreignSession.id}") {
                    bearerAuth("admin-token")
                }
                assertEquals(HttpStatusCode.NoContent, adminDelete.status)

                val adminRestore = client.post("/api/v1/sessions/${foreignSession.id}/restore") {
                    bearerAuth("admin-token")
                }
                assertEquals(HttpStatusCode.NoContent, adminRestore.status)

                // No bearer token represents a guest; protected mutation endpoints reject it.
                val guestCreate = client.post("/api/v1/classes/${ownerClass.id}/sessions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Guest-created session"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, guestCreate.status)
                
                // HTTP read visibility: anonymous users see only public, published,
                // non-hidden content; authenticated students need active membership
                // for private classes, while teachers retain ownership boundaries.
                val anonymousClasses = client.get("/api/v1/public/classes").bodyAsText()
                assertContains(anonymousClasses, ownerClass.id)
                assertFalse(anonymousClasses.contains(otherTeacherClass.id))
                assertFalse(anonymousClasses.contains(memberPrivateClass.id))

                val anonymousPublicSessions = client.get(
                    "/api/v1/public/classes/${ownerClass.id}/sessions"
                )
                assertEquals(HttpStatusCode.OK, anonymousPublicSessions.status)
                assertContains(anonymousPublicSessions.bodyAsText(), activeSession.id)

                val anonymousPublicDetail = client.get(
                    "/api/v1/public/classes/${ownerClass.id}/sessions/${activeSession.id}"
                )
                assertEquals(HttpStatusCode.OK, anonymousPublicDetail.status)

                val anonymousPrivateDetail = client.get(
                    "/api/v1/public/classes/${otherTeacherClass.id}/sessions/${foreignSession.id}"
                )
                assertEquals(HttpStatusCode.NotFound, anonymousPrivateDetail.status)

                val ownerClasses = client.get("/api/v1/classes") {
                    bearerAuth("owner-token")
                }.bodyAsText()
                assertContains(ownerClasses, ownerClass.id)
                assertFalse(ownerClasses.contains(otherTeacherClass.id))

                val otherTeacherClasses = client.get("/api/v1/classes") {
                    bearerAuth("other-teacher-token")
                }.bodyAsText()
                assertContains(otherTeacherClasses, ownerClass.id)
                assertContains(otherTeacherClasses, otherTeacherClass.id)

                val studentClasses = client.get("/api/v1/classes") {
                    bearerAuth("student-token")
                }.bodyAsText()
                assertContains(studentClasses, ownerClass.id)
                assertContains(studentClasses, memberPrivateClass.id)
                assertFalse(studentClasses.contains(otherTeacherClass.id))

                val studentPrivateSessions = client.get(
                    "/api/v1/classes/${memberPrivateClass.id}/sessions"
                ) {
                    bearerAuth("student-token")
                }
                assertEquals(HttpStatusCode.OK, studentPrivateSessions.status)
                assertContains(studentPrivateSessions.bodyAsText(), memberPrivateSession.id)

                val studentUnjoinedPrivateSessions = client.get(
                    "/api/v1/classes/${otherTeacherClass.id}/sessions"
                ) {
                    bearerAuth("student-token")
                }
                assertEquals(HttpStatusCode.OK, studentUnjoinedPrivateSessions.status)
                assertFalse(studentUnjoinedPrivateSessions.bodyAsText().contains(foreignSession.id))

                val adminClasses = client.get("/api/v1/classes") {
                    bearerAuth("admin-token")
                }.bodyAsText()
                assertContains(adminClasses, ownerClass.id)
                assertContains(adminClasses, otherTeacherClass.id)

                
                // Class-level HTTP authorization: teachers manage only their own classes;
                // the main admin may manage classes owned by any teacher.
                val foreignClassUpdateByTeacher = client.patch("/api/v1/classes/${ownerClass.id}") {
                    bearerAuth("other-teacher-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Unauthorized class edit","description":"","term":"","visibility":"public","isPublished":true,"isHidden":false,"sortOrder":0}""")
                }
                assertEquals(HttpStatusCode.Forbidden, foreignClassUpdateByTeacher.status)

                val foreignClassDeleteByTeacher = client.delete("/api/v1/classes/${ownerClass.id}") {
                    bearerAuth("other-teacher-token")
                }
                assertEquals(HttpStatusCode.Forbidden, foreignClassDeleteByTeacher.status)

                val studentClassCreate = client.post("/api/v1/classes") {
                    bearerAuth("student-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Student class","visibility":"private"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, studentClassCreate.status)

                val teacherCannotCreateForAnotherOwner = client.post("/api/v1/classes") {
                    bearerAuth("other-teacher-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"ownerUserId":"${owner}","title":"Impersonated class","visibility":"private"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, teacherCannotCreateForAnotherOwner.status)

                val ownerClassUpdate = client.patch("/api/v1/classes/${ownerClass.id}") {
                    bearerAuth("owner-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Owner updated class","description":"","term":"","visibility":"public","isPublished":true,"isHidden":false,"sortOrder":0}""")
                }
                assertEquals(HttpStatusCode.OK, ownerClassUpdate.status)

                val adminClassUpdate = client.patch("/api/v1/classes/${otherTeacherClass.id}") {
                    bearerAuth("admin-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Admin updated other teacher class","description":"","term":"","visibility":"private","isPublished":true,"isHidden":false,"sortOrder":0}""")
                }
                assertEquals(HttpStatusCode.OK, adminClassUpdate.status)

                val adminClassDelete = client.delete("/api/v1/classes/${otherTeacherClass.id}") {
                    bearerAuth("admin-token")
                }
                assertEquals(HttpStatusCode.NoContent, adminClassDelete.status)

                val adminClassRestore = client.post("/api/v1/classes/${otherTeacherClass.id}/restore") {
                    bearerAuth("admin-token")
                }
                assertEquals(HttpStatusCode.NoContent, adminClassRestore.status)

                val guestClassCreate = client.post("/api/v1/classes") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"Guest class","visibility":"public"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, guestClassCreate.status)
            }
        }
    }
}
