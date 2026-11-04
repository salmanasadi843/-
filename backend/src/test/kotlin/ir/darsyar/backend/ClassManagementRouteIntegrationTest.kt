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

class ClassManagementRouteIntegrationTest {
    @Test
    fun `admin can manage another teachers class over HTTP but another teacher cannot`() = testApplication {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "class_route_${UUID.randomUUID().toString().replace("-", "")}"

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

            val classes = TeachingClassRepository(dataSource)
            val folderRepository = TeacherFolderRepository(dataSource)
            val ownerFolder = folderRepository.create(
                ownerId.toString(), setOf(AppRole.TEACHER),
                CreateTeacherFolder(ownerId.toString(), null, "پوشه اول")
            )
            val secondOwnerFolder = folderRepository.create(
                ownerId.toString(), setOf(AppRole.TEACHER),
                CreateTeacherFolder(ownerId.toString(), null, "پوشه دوم")
            )
            val ownedClass = classes.create(
                ownerId.toString(),
                CreateTeachingClass(ownerId.toString(), "کلاس اولیه", "", "", "private", true)
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

            // The owner teacher can move their own class between their folders.
            val ownerMove = client.patch("/api/v1/classes/${ownedClass.id}") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
                contentType(ContentType.Application.Json)
                setBody("""{
                    "title":"کلاس اولیه",
                    "description":"",
                    "term":"",
                    "visibility":"private",
                    "isPublished":true,
                    "isHidden":false,
                    "sortOrder":1,
                    "folderId":"${ownerFolder.id}"
                }""".trimIndent())
            }
            val ownerMoveResponse = ownerMove.bodyAsText()
            assertEquals(HttpStatusCode.OK, ownerMove.status, ownerMoveResponse)
            assertTrue(ownerMoveResponse.contains(ownerFolder.id), ownerMoveResponse)

            val updateBody = """{
                "title":"ویرایش مدیر",
                "description":"مدیریت‌شده توسط مدیر اصلی",
                "term":"",
                "visibility":"private",
                "isPublished":true,
                "isHidden":false,
                "sortOrder":2,
                "folderId":"${secondOwnerFolder.id}"
            }""".trimIndent()

            val forbiddenUpdate = client.patch("/api/v1/classes/${ownedClass.id}") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
                contentType(ContentType.Application.Json)
                setBody(updateBody)
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenUpdate.status, forbiddenUpdate.bodyAsText())

            val adminUpdate = client.patch("/api/v1/classes/${ownedClass.id}") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
                contentType(ContentType.Application.Json)
                setBody(updateBody)
            }
            val updateResponse = adminUpdate.bodyAsText()
            assertEquals(HttpStatusCode.OK, adminUpdate.status, updateResponse)
            assertTrue(updateResponse.contains("ویرایش مدیر"), updateResponse)

            assertTrue(updateResponse.contains(secondOwnerFolder.id), updateResponse)

            // Reordering is one server-side transaction, scoped to the same teacher and folder.
            val siblingClass = classes.create(
                ownerId.toString(),
                CreateTeachingClass(
                    ownerId.toString(), "کلاس هم‌گروه", "", "", "private", true,
                    folderId = secondOwnerFolder.id
                )
            )
            val forbiddenReorder = client.post("/api/v1/classes/${ownedClass.id}/reorder") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
                contentType(ContentType.Application.Json)
                setBody("""{"direction":"up"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenReorder.status, forbiddenReorder.bodyAsText())

            val adminReorder = client.post("/api/v1/classes/${ownedClass.id}/reorder") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
                contentType(ContentType.Application.Json)
                setBody("""{"direction":"up"}""")
            }
            val adminReorderBody = adminReorder.bodyAsText()
            assertEquals(HttpStatusCode.OK, adminReorder.status, adminReorderBody)
            assertTrue(
                adminReorderBody.indexOf("\"id\":\"${ownedClass.id}\"") <
                    adminReorderBody.indexOf("\"id\":\"${siblingClass.id}\""),
                adminReorderBody
            )

            val ownerReorder = client.post("/api/v1/classes/${ownedClass.id}/reorder") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
                contentType(ContentType.Application.Json)
                setBody("""{"direction":"down"}""")
            }
            val ownerReorderBody = ownerReorder.bodyAsText()
            assertEquals(HttpStatusCode.OK, ownerReorder.status, ownerReorderBody)
            assertTrue(
                ownerReorderBody.indexOf("\"id\":\"${siblingClass.id}\"") <
                    ownerReorderBody.indexOf("\"id\":\"${ownedClass.id}\""),
                ownerReorderBody
            )

            val invalidReorder = client.post("/api/v1/classes/${ownedClass.id}/reorder") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
                contentType(ContentType.Application.Json)
                setBody("""{"direction":"sideways"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, invalidReorder.status, invalidReorder.bodyAsText())

            val forbiddenDelete = client.delete("/api/v1/classes/${ownedClass.id}") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenDelete.status)

            val adminDelete = client.delete("/api/v1/classes/${ownedClass.id}") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
            }
            assertEquals(HttpStatusCode.NoContent, adminDelete.status)
            assertEquals(
                setOf(ownedClass.id),
                classes.listDeleted(adminId.toString(), setOf(AppRole.SUPER_ADMIN)).map { it.id }.toSet()
            )
        }
    }
}
