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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CourseManagementRouteIntegrationTest {
    @Test
    fun `admin can manage another teachers courses over HTTP but another teacher cannot`() = testApplication {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "course_route_${UUID.randomUUID().toString().replace("-", "")}"

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

            val classRecord = TeachingClassRepository(dataSource).create(
                ownerId.toString(),
                CreateTeachingClass(ownerId.toString(), "کلاس استاد", "", "", "private", true)
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

            val foreignCreate = client.post("/api/v1/classes/${classRecord.id}/courses") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"دوره غیرمجاز","teacherName":"استاد دیگر","description":"","createdAtMs":null,"sortOrder":0}""")
            }
            assertEquals(HttpStatusCode.Forbidden, foreignCreate.status, foreignCreate.bodyAsText())

            val adminCreate = client.post("/api/v1/classes/${classRecord.id}/courses") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"دوره ایجادشده توسط مدیر","teacherName":"استاد","description":"","createdAtMs":null,"sortOrder":1}""")
            }
            val adminCreateBody = adminCreate.bodyAsText()
            assertEquals(HttpStatusCode.Created, adminCreate.status, adminCreateBody)
            val courseId = Regex(""""id":"([^"]+)"""").find(adminCreateBody)?.groupValues?.get(1)
            assertTrue(!courseId.isNullOrBlank(), adminCreateBody)

            val updateBody = """{"title":"دوره ویرایش‌شده توسط مدیر","teacherName":"استاد","description":"مدیریت بین‌استادی","createdAtMs":10,"sortOrder":2}"""
            val forbiddenUpdate = client.patch("/api/v1/courses/$courseId") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
                contentType(ContentType.Application.Json)
                setBody(updateBody)
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenUpdate.status, forbiddenUpdate.bodyAsText())

            val adminUpdate = client.patch("/api/v1/courses/$courseId") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
                contentType(ContentType.Application.Json)
                setBody(updateBody)
            }
            val adminUpdateBody = adminUpdate.bodyAsText()
            assertEquals(HttpStatusCode.OK, adminUpdate.status, adminUpdateBody)
            assertTrue(adminUpdateBody.contains("دوره ویرایش‌شده توسط مدیر"), adminUpdateBody)

            val forbiddenDelete = client.delete("/api/v1/courses/$courseId") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenDelete.status)

            val adminDelete = client.delete("/api/v1/courses/$courseId") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
            }
            assertEquals(HttpStatusCode.NoContent, adminDelete.status)

            val ownerCreate = client.post("/api/v1/classes/${classRecord.id}/courses") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"دوره استاد","teacherName":"استاد","description":"","createdAtMs":null,"sortOrder":0}""")
            }
            val ownerCreateBody = ownerCreate.bodyAsText()
            assertEquals(HttpStatusCode.Created, ownerCreate.status, ownerCreateBody)
            val ownerCourseId = Regex(""""id":"([^"]+)"""").find(ownerCreateBody)?.groupValues?.get(1)
            assertTrue(!ownerCourseId.isNullOrBlank(), ownerCreateBody)

            val ownerUpdate = client.patch("/api/v1/courses/$ownerCourseId") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
                contentType(ContentType.Application.Json)
                setBody("""{"title":"دوره ویرایش‌شده استاد","teacherName":"استاد","description":"","createdAtMs":null,"sortOrder":1}""")
            }
            assertEquals(HttpStatusCode.OK, ownerUpdate.status, ownerUpdate.bodyAsText())

            val ownerDelete = client.delete("/api/v1/courses/$ownerCourseId") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
            }
            assertEquals(HttpStatusCode.NoContent, ownerDelete.status, ownerDelete.bodyAsText())
        }
    }
}
