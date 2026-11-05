package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassAccessCodeRoutesIntegrationTest {
    @Test
    fun `access code HTTP routes enforce owner admin student and guest permissions`() = testApplication {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "access_code_routes_${UUID.randomUUID().toString().replace("-", "")}"

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
            val adminId = UUID.randomUUID()
            val ownerId = UUID.randomUUID()
            val otherTeacherId = UUID.randomUUID()
            val studentId = UUID.randomUUID()

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO app_users(id, display_name) VALUES (?, 'مدیر'), (?, 'استاد مالک'), (?, 'استاد دیگر'), (?, 'دانشجو')"
                ).use { statement ->
                    statement.setObject(1, adminId)
                    statement.setObject(2, ownerId)
                    statement.setObject(3, otherTeacherId)
                    statement.setObject(4, studentId)
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

            val classes = TeachingClassRepository(dataSource)
            val privateClass = classes.create(
                ownerId.toString(),
                CreateTeachingClass(ownerId.toString(), "کلاس خصوصی", "", "", "private", true)
            )
            val publicClass = classes.create(
                ownerId.toString(),
                CreateTeachingClass(ownerId.toString(), "کلاس عمومی", "", "", "public", true)
            )

            val identities = mapOf(
                "admin-token" to (adminId.toString() to setOf(AppRole.SUPER_ADMIN)),
                "owner-token" to (ownerId.toString() to setOf(AppRole.TEACHER)),
                "other-teacher-token" to (otherTeacherId.toString() to setOf(AppRole.TEACHER)),
                "student-token" to (studentId.toString() to setOf(AppRole.STUDENT))
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

            val ownerCreate = client.post("/api/v1/classes/${privateClass.id}/access-codes") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
                contentType(ContentType.Application.Json)
                setBody("""{"maxUses":1}""")
            }
            val ownerCreateBody = ownerCreate.bodyAsText()
            assertEquals(HttpStatusCode.Created, ownerCreate.status, ownerCreateBody)
            val ownerCodeId = Regex("""\"id\":\"([^\"]+)\"""").find(ownerCreateBody)?.groupValues?.get(1)
            val ownerCode = Regex("""\"code\":\"([^\"]+)\"""").find(ownerCreateBody)?.groupValues?.get(1)
            assertNotNull(ownerCodeId, ownerCreateBody)
            assertNotNull(ownerCode, ownerCreateBody)

            val ownerList = client.get("/api/v1/classes/${privateClass.id}/access-codes") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
            }
            val ownerListBody = ownerList.bodyAsText()
            assertEquals(HttpStatusCode.OK, ownerList.status, ownerListBody)
            assertTrue(ownerListBody.contains(ownerCodeId), ownerListBody)
            assertFalse(ownerListBody.contains(ownerCode), "A listed access code must never reveal its raw secret.")

            val foreignList = client.get("/api/v1/classes/${privateClass.id}/access-codes") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
            }
            assertEquals(HttpStatusCode.Forbidden, foreignList.status, foreignList.bodyAsText())

            val studentList = client.get("/api/v1/classes/${privateClass.id}/access-codes") {
                header(HttpHeaders.Authorization, "Bearer student-token")
            }
            assertEquals(HttpStatusCode.Forbidden, studentList.status, studentList.bodyAsText())

            val foreignCreate = client.post("/api/v1/classes/${privateClass.id}/access-codes") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
                contentType(ContentType.Application.Json)
                setBody("""{"maxUses":1}""")
            }
            assertEquals(HttpStatusCode.Forbidden, foreignCreate.status, foreignCreate.bodyAsText())

            val studentCreate = client.post("/api/v1/classes/${privateClass.id}/access-codes") {
                header(HttpHeaders.Authorization, "Bearer student-token")
                contentType(ContentType.Application.Json)
                setBody("""{"maxUses":1}""")
            }
            assertEquals(HttpStatusCode.Forbidden, studentCreate.status, studentCreate.bodyAsText())

            val foreignDeactivate = client.delete("/api/v1/access-codes/$ownerCodeId") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
            }
            assertEquals(HttpStatusCode.Forbidden, foreignDeactivate.status, foreignDeactivate.bodyAsText())

            val studentDeactivate = client.delete("/api/v1/access-codes/$ownerCodeId") {
                header(HttpHeaders.Authorization, "Bearer student-token")
            }
            assertEquals(HttpStatusCode.Forbidden, studentDeactivate.status, studentDeactivate.bodyAsText())

            val studentRedeem = client.post("/api/v1/class-memberships/redeem") {
                header(HttpHeaders.Authorization, "Bearer student-token")
                contentType(ContentType.Application.Json)
                setBody("""{"code":"$ownerCode"}""")
            }
            val studentRedeemBody = studentRedeem.bodyAsText()
            assertEquals(HttpStatusCode.OK, studentRedeem.status, studentRedeemBody)
            assertTrue(studentRedeemBody.contains("\"status\":\"active\""), studentRedeemBody)
            assertTrue(studentRedeemBody.contains("\"alreadyActive\":false"), studentRedeemBody)

            val repeatedRedeem = client.post("/api/v1/class-memberships/redeem") {
                header(HttpHeaders.Authorization, "Bearer student-token")
                contentType(ContentType.Application.Json)
                setBody("""{"code":"$ownerCode"}""")
            }
            val repeatedRedeemBody = repeatedRedeem.bodyAsText()
            assertEquals(HttpStatusCode.OK, repeatedRedeem.status, repeatedRedeemBody)
            assertTrue(repeatedRedeemBody.contains("\"alreadyActive\":true"), repeatedRedeemBody)

            val adminCreate = client.post("/api/v1/classes/${privateClass.id}/access-codes") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
                contentType(ContentType.Application.Json)
                setBody("""{"maxUses":1}""")
            }
            val adminCreateBody = adminCreate.bodyAsText()
            assertEquals(HttpStatusCode.Created, adminCreate.status, adminCreateBody)
            val adminCodeId = Regex("""\"id\":\"([^\"]+)\"""").find(adminCreateBody)?.groupValues?.get(1)
            val adminCode = Regex("""\"code\":\"([^\"]+)\"""").find(adminCreateBody)?.groupValues?.get(1)
            assertNotNull(adminCodeId, adminCreateBody)
            assertNotNull(adminCode, adminCreateBody)

            val adminList = client.get("/api/v1/classes/${privateClass.id}/access-codes") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
            }
            val adminListBody = adminList.bodyAsText()
            assertEquals(HttpStatusCode.OK, adminList.status, adminListBody)
            assertTrue(adminListBody.contains(ownerCodeId), adminListBody)
            assertTrue(adminListBody.contains(adminCodeId), adminListBody)
            assertFalse(adminListBody.contains(adminCode), "Listing must not reveal the admin-created raw secret.")

            val adminDeactivate = client.delete("/api/v1/access-codes/$adminCodeId") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
            }
            assertEquals(HttpStatusCode.NoContent, adminDeactivate.status, adminDeactivate.bodyAsText())

            val deactivatedRedeem = client.post("/api/v1/class-memberships/redeem") {
                header(HttpHeaders.Authorization, "Bearer student-token")
                contentType(ContentType.Application.Json)
                setBody("""{"code":"$adminCode"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, deactivatedRedeem.status, deactivatedRedeem.bodyAsText())

            val ownerDeactivate = client.delete("/api/v1/access-codes/$ownerCodeId") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
            }
            assertEquals(HttpStatusCode.NoContent, ownerDeactivate.status, ownerDeactivate.bodyAsText())

            val publicClassCode = client.post("/api/v1/classes/${publicClass.id}/access-codes") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
                contentType(ContentType.Application.Json)
                setBody("""{"maxUses":1}""")
            }
            assertEquals(HttpStatusCode.BadRequest, publicClassCode.status, publicClassCode.bodyAsText())

            val guestList = client.get("/api/v1/classes/${privateClass.id}/access-codes")
            assertEquals(HttpStatusCode.Unauthorized, guestList.status, guestList.bodyAsText())

            val guestCreate = client.post("/api/v1/classes/${privateClass.id}/access-codes") {
                contentType(ContentType.Application.Json)
                setBody("""{"maxUses":1}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, guestCreate.status, guestCreate.bodyAsText())

            val guestDeactivate = client.delete("/api/v1/access-codes/$ownerCodeId")
            assertEquals(HttpStatusCode.Unauthorized, guestDeactivate.status, guestDeactivate.bodyAsText())

            val guestRedeem = client.post("/api/v1/class-memberships/redeem") {
                contentType(ContentType.Application.Json)
                setBody("""{"code":"$ownerCode"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, guestRedeem.status, guestRedeem.bodyAsText())
        }
    }
}
