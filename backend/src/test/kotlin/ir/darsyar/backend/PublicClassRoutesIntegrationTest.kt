package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicClassRoutesIntegrationTest {
    @Test
    fun `anonymous routes expose only published public classes and sessions`() = testApplication {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "public_route_${UUID.randomUUID().toString().replace("-", "")}"

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
            val teacherId = UUID.randomUUID().toString()
            dataSource.connection.use { connection ->
                connection.prepareStatement("INSERT INTO app_users(id, display_name) VALUES (?::uuid, 'استاد آزمون')").use {
                    it.setString(1, teacherId)
                    it.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO app_roles(user_id, role_name) VALUES (?::uuid, 'teacher')").use {
                    it.setString(1, teacherId)
                    it.executeUpdate()
                }
            }

            val classes = TeachingClassRepository(dataSource)
            val publicClass = classes.create(
                teacherId,
                CreateTeachingClass(teacherId, "عمومی منتشر", "", "", "public", true)
            )
            val privateClass = classes.create(
                teacherId,
                CreateTeachingClass(teacherId, "خصوصی منتشر", "", "", "private", true)
            )
            val hiddenClass = classes.create(
                teacherId,
                CreateTeachingClass(teacherId, "عمومی مخفی", "", "", "public", true)
            )
            classes.update(
                teacherId, setOf(AppRole.TEACHER), hiddenClass.id,
                UpdateTeachingClass("عمومی مخفی", "", "", "public", true, true, 0)
            )
            val draftClass = classes.create(
                teacherId,
                CreateTeachingClass(teacherId, "عمومی پیش‌نویس", "", "", "public", false)
            )

            val sessions = TeachingSessionRepository(dataSource)
            val publicSession = sessions.create(
                teacherId, setOf(AppRole.TEACHER),
                CreateTeachingSession(publicClass.id, title = "جلسه عمومی", transcript = "متن عمومی", isPublished = true)
            )
            val draftSession = sessions.create(
                teacherId, setOf(AppRole.TEACHER),
                CreateTeachingSession(publicClass.id, title = "جلسه پیش‌نویس", transcript = "متن مخفی", isPublished = false)
            )
            val privateSession = sessions.create(
                teacherId, setOf(AppRole.TEACHER),
                CreateTeachingSession(privateClass.id, title = "جلسه خصوصی", transcript = "متن خصوصی", isPublished = true)
            )

            val verifier = IdentityTokenVerifier { token -> VerifiedIdentity(provider = "firebase", subject = token) }
            val users = AuthenticatedUserRepository { _, subject ->
                AuthenticatedUserRecord(userId = subject, status = "active", roles = setOf(AppRole.TEACHER))
            }
            application { module(dataSource, verifier, users) }

            val catalog = client.get("/api/v1/public/classes")
            assertEquals(HttpStatusCode.OK, catalog.status, catalog.bodyAsText())
            val catalogBody = catalog.bodyAsText()
            assertTrue(catalogBody.contains(publicClass.id), catalogBody)
            assertFalse(catalogBody.contains(privateClass.id), catalogBody)
            assertFalse(catalogBody.contains(hiddenClass.id), catalogBody)
            assertFalse(catalogBody.contains(draftClass.id), catalogBody)

            val publicList = client.get("/api/v1/public/classes/${publicClass.id}/sessions")
            assertEquals(HttpStatusCode.OK, publicList.status, publicList.bodyAsText())
            assertTrue(publicList.bodyAsText().contains(publicSession.id))
            assertFalse(publicList.bodyAsText().contains(privateSession.id))
            assertFalse(publicList.bodyAsText().contains(draftSession.id))

            val publicDetail = client.get("/api/v1/public/classes/${publicClass.id}/sessions/${publicSession.id}")
            assertEquals(HttpStatusCode.OK, publicDetail.status, publicDetail.bodyAsText())
            assertTrue(publicDetail.bodyAsText().contains("متن عمومی"))

            val privateDetail = client.get("/api/v1/public/classes/${privateClass.id}/sessions/${privateSession.id}")
            assertEquals(HttpStatusCode.NotFound, privateDetail.status, privateDetail.bodyAsText())

            val wrongParent = client.get("/api/v1/public/classes/${publicClass.id}/sessions/${privateSession.id}")
            assertEquals(HttpStatusCode.NotFound, wrongParent.status, wrongParent.bodyAsText())
        }
    }
}
