package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionManagementRouteIntegrationTest {
    @Test
    fun `admin can manage another teachers session over HTTP but another teacher cannot`() = testApplication {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "session_route_${UUID.randomUUID().toString().replace("-", "")}"

        Flyway.configure().dataSource(jdbcUrl, username, password)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = 3
            connectionInitSql = "SET search_path TO $schema"
        }
        HikariDataSource(config).use { dataSource ->
            val adminId = UUID.randomUUID()
            val ownerId = UUID.randomUUID()
            val otherTeacherId = UUID.randomUUID()

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO app_users(id, display_name) VALUES (?, 'مدیر'), (?, 'استاد مالک'), (?, 'استاد دیگر')"
                ).use { statement ->
                    statement.setObject(1, adminId)
                    statement.setObject(2, ownerId)
                    statement.setObject(3, otherTeacherId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO app_roles(user_id, role_name) VALUES (?, 'teacher'), (?, 'teacher')"
                ).use { statement ->
                    statement.setObject(1, ownerId)
                    statement.setObject(2, otherTeacherId)
                    statement.executeUpdate()
                }
            }

            val classRepository = TeachingClassRepository(dataSource)
            val sourceClass = classRepository.create(
                ownerId.toString(),
                CreateTeachingClass(ownerId.toString(), "کلاس مبدأ", "", "", "private", true)
            )
            val targetClass = classRepository.create(
                ownerId.toString(),
                CreateTeachingClass(ownerId.toString(), "کلاس مقصد", "", "", "private", true)
            )
            val sessionRepository = TeachingSessionRepository(dataSource)
            val session = sessionRepository.create(
                ownerId.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(
                    classId = sourceClass.id,
                    title = "جلسه اولیه",
                    transcript = "متن اصلی جلسه",
                    originalTranscript = "متن اصلی جلسه",
                    isPublished = true,
                    sortOrder = 1
                )
            )
            val siblingSession = sessionRepository.create(
                ownerId.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(
                    classId = sourceClass.id,
                    title = "جلسه هم‌گروه",
                    transcript = "متن جلسه هم‌گروه",
                    originalTranscript = "متن جلسه هم‌گروه",
                    isPublished = true,
                    sortOrder = 0
                )
            )

            val identities = mapOf(
                "admin-token" to (adminId.toString() to setOf(AppRole.SUPER_ADMIN)),
                "owner-token" to (ownerId.toString() to setOf(AppRole.TEACHER)),
                "other-teacher-token" to (otherTeacherId.toString() to setOf(AppRole.TEACHER))
            )
            val verifier = IdentityTokenVerifier { token ->
                check(token in identities)
                VerifiedIdentity(provider = "firebase", subject = token)
            }
            val users = AuthenticatedUserRepository { provider, subject ->
                check(provider == "firebase")
                val (userId, roles) = identities.getValue(subject)
                AuthenticatedUserRecord(userId = userId, status = "active", roles = roles)
            }

            application { module(dataSource, verifier, users) }

            val updateBody = """{
                "title":"ویرایش مدیر",
                "courseName":"",
                "professorName":"",
                "audioDurationMs":0,
                "transcript":"متن ویرایش‌شده",
                "originalTranscript":"متن اصلی جلسه",
                "tags":"",
                "isPublished":true,
                "sortOrder":0
            }""".trimIndent()

            val forbiddenUpdate = client.patch("/api/v1/sessions/${session.id}") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
                contentType(ContentType.Application.Json)
                setBody(updateBody)
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenUpdate.status, forbiddenUpdate.bodyAsText())

            val adminUpdate = client.patch("/api/v1/sessions/${session.id}") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
                contentType(ContentType.Application.Json)
                setBody(updateBody)
            }
            val updateResponse = adminUpdate.bodyAsText()
            assertEquals(HttpStatusCode.OK, adminUpdate.status, updateResponse)
            assertTrue(updateResponse.contains("ویرایش مدیر"), updateResponse)

            val moveBody = """{"targetClassId":"${targetClass.id}"}"""
            val forbiddenMove = client.post("/api/v1/sessions/${session.id}/move") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
                contentType(ContentType.Application.Json)
                setBody(moveBody)
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenMove.status, forbiddenMove.bodyAsText())

            val adminMove = client.post("/api/v1/sessions/${session.id}/move") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
                contentType(ContentType.Application.Json)
                setBody(moveBody)
            }
            val moveResponse = adminMove.bodyAsText()
            assertEquals(HttpStatusCode.OK, adminMove.status, moveResponse)
            assertTrue(moveResponse.contains(targetClass.id), moveResponse)
            assertTrue(moveResponse.contains("متن اصلی جلسه"), moveResponse)

            // The owning teacher may also move a session between two classes they own.
            val ownerMove = client.post("/api/v1/sessions/${session.id}/move") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
                contentType(ContentType.Application.Json)
                setBody("""{"targetClassId":"${sourceClass.id}"}""")
            }
            val ownerMoveResponse = ownerMove.bodyAsText()
            assertEquals(HttpStatusCode.OK, ownerMove.status, ownerMoveResponse)
            assertTrue(ownerMoveResponse.contains(sourceClass.id), ownerMoveResponse)
            assertTrue(ownerMoveResponse.contains("متن اصلی جلسه"), ownerMoveResponse)

            // Session reorder is also protected at the HTTP boundary: admins and owners
            // may reorder, while a different teacher remains read-only.
            val forbiddenReorder = client.post("/api/v1/sessions/\${session.id}/reorder") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
                contentType(ContentType.Application.Json)
                setBody("""{"direction":"up"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenReorder.status, forbiddenReorder.bodyAsText())

            val adminReorder = client.post("/api/v1/sessions/\${session.id}/reorder") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
                contentType(ContentType.Application.Json)
                setBody("""{"direction":"up"}""")
            }
            assertEquals(HttpStatusCode.OK, adminReorder.status, adminReorder.bodyAsText())
            var sourceOrder = sessionRepository.listVisible(adminId.toString(), setOf(AppRole.SUPER_ADMIN))
                .filter { it.classId == sourceClass.id }
                .sortedBy { it.sortOrder }
                .map { it.id }
            assertEquals(listOf(session.id, siblingSession.id), sourceOrder)

            val ownerReorder = client.post("/api/v1/sessions/\${session.id}/reorder") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
                contentType(ContentType.Application.Json)
                setBody("""{"direction":"down"}""")
            }
            assertEquals(HttpStatusCode.OK, ownerReorder.status, ownerReorder.bodyAsText())
            sourceOrder = sessionRepository.listVisible(adminId.toString(), setOf(AppRole.SUPER_ADMIN))
                .filter { it.classId == sourceClass.id }
                .sortedBy { it.sortOrder }
                .map { it.id }
            assertEquals(listOf(siblingSession.id, session.id), sourceOrder)

            val invalidReorder = client.post("/api/v1/sessions/\${session.id}/reorder") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
                contentType(ContentType.Application.Json)
                setBody("""{"direction":"sideways"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalidReorder.status, invalidReorder.bodyAsText())

            // Concurrent reorders acquire the complete sibling set in stable ID order.
            val startTogether = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val firstMove = executor.submit {
                    startTogether.await()
                    sessionRepository.reorder(adminId.toString(), setOf(AppRole.SUPER_ADMIN), session.id, "up")
                }
                val secondMove = executor.submit {
                    startTogether.await()
                    sessionRepository.reorder(adminId.toString(), setOf(AppRole.SUPER_ADMIN), siblingSession.id, "down")
                }
                startTogether.countDown()
                firstMove.get(10, TimeUnit.SECONDS)
                secondMove.get(10, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }

            val forbiddenDelete = client.delete("/api/v1/sessions/${session.id}") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenDelete.status)

            val adminDelete = client.delete("/api/v1/sessions/${session.id}") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
            }
            assertEquals(HttpStatusCode.NoContent, adminDelete.status)
            assertEquals(
                setOf(session.id),
                sessionRepository.listDeleted(adminId.toString(), setOf(AppRole.SUPER_ADMIN)).map { it.id }.toSet()
            )
        }
    }
}
