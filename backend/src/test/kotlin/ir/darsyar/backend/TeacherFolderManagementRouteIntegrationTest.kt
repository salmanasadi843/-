package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.delete
import io.ktor.client.request.get
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

class TeacherFolderManagementRouteIntegrationTest {
    @Test
    fun `admin can manage another teachers folder over HTTP but another teacher cannot`() = testApplication {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "folder_route_${UUID.randomUUID().toString().replace("-", "")}"

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

            val folders = TeacherFolderRepository(dataSource)
            val folder = folders.create(
                ownerId.toString(),
                setOf(AppRole.TEACHER),
                CreateTeacherFolder(ownerId.toString(), null, "پوشه استاد")
            )

            val childFolder = folders.create(
                ownerId.toString(),
                setOf(AppRole.TEACHER),
                CreateTeacherFolder(ownerId.toString(), folder.id, "زیرپوشه")
            )
            val publicClassId = dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """INSERT INTO teaching_classes(owner_user_id, folder_id, title, visibility, is_published, is_hidden)
                       VALUES (?, ?, 'کلاس عمومی در زیرپوشه', 'public', true, false)
                       RETURNING id::text"""
                ).use { statement ->
                    statement.setObject(1, ownerId)
                    statement.setObject(2, UUID.fromString(childFolder.id))
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getString(1)
                    }
                }
            }

            val identities = mapOf(
                "admin-token" to (adminId.toString() to setOf(AppRole.SUPER_ADMIN)),
                "owner-token" to (ownerId.toString() to setOf(AppRole.TEACHER)),
                "other-teacher-token" to (otherTeacherId.toString() to setOf(AppRole.TEACHER)),
                "student-token" to (UUID.randomUUID().toString() to setOf(AppRole.STUDENT))
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

            val ownerList = client.get("/api/v1/teacher-folders") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
            }
            val ownerListBody = ownerList.bodyAsText()
            assertEquals(HttpStatusCode.OK, ownerList.status, ownerListBody)
            assertTrue(ownerListBody.contains(folder.id), ownerListBody)

            val adminList = client.get("/api/v1/teacher-folders") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
            }
            val adminListBody = adminList.bodyAsText()
            assertEquals(HttpStatusCode.OK, adminList.status, adminListBody)
            assertTrue(adminListBody.contains(folder.id), adminListBody)

            val otherTeacherList = client.get("/api/v1/teacher-folders") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
            }
            val otherTeacherListBody = otherTeacherList.bodyAsText()
            assertEquals(HttpStatusCode.OK, otherTeacherList.status, otherTeacherListBody)
            // A non-owner teacher may browse folders that contain published public classes,
            // but this is read-only access (mutation is asserted forbidden below).
            assertTrue(otherTeacherListBody.contains(folder.id), otherTeacherListBody)
            assertTrue(otherTeacherListBody.contains(childFolder.id), otherTeacherListBody)

            val anonymousList = client.get("/api/v1/teacher-folders")
            assertEquals(HttpStatusCode.Unauthorized, anonymousList.status, anonymousList.bodyAsText())

            val studentList = client.get("/api/v1/teacher-folders") {
                header(HttpHeaders.Authorization, "Bearer student-token")
            }
            val studentListBody = studentList.bodyAsText()
            assertEquals(HttpStatusCode.OK, studentList.status, studentListBody)
            assertTrue(studentListBody.contains(folder.id), studentListBody)
            assertTrue(studentListBody.contains(childFolder.id), studentListBody)

            val updateBody = """{
                "name":"پوشه ویرایش‌شده توسط مدیر",
                "parentFolderId":null,
                "sortOrder":3,
                "isHidden":true
            }""".trimIndent()

            val forbiddenUpdate = client.patch("/api/v1/teacher-folders/${folder.id}") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
                contentType(ContentType.Application.Json)
                setBody(updateBody)
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenUpdate.status, forbiddenUpdate.bodyAsText())

            val adminUpdate = client.patch("/api/v1/teacher-folders/${folder.id}") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
                contentType(ContentType.Application.Json)
                setBody(updateBody)
            }
            val updateResponse = adminUpdate.bodyAsText()
            assertEquals(HttpStatusCode.OK, adminUpdate.status, updateResponse)
            assertTrue(updateResponse.contains("پوشه ویرایش‌شده توسط مدیر"), updateResponse)
            assertTrue(updateResponse.contains("\"isHidden\":true"), updateResponse)

            val ownerHiddenList = client.get("/api/v1/teacher-folders") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
            }
            val ownerHiddenBody = ownerHiddenList.bodyAsText()
            assertEquals(HttpStatusCode.OK, ownerHiddenList.status, ownerHiddenBody)
            assertTrue(ownerHiddenBody.contains(folder.id), ownerHiddenBody)

            val adminHiddenList = client.get("/api/v1/teacher-folders") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
            }
            val adminHiddenBody = adminHiddenList.bodyAsText()
            assertEquals(HttpStatusCode.OK, adminHiddenList.status, adminHiddenBody)
            assertTrue(adminHiddenBody.contains(folder.id), adminHiddenBody)

            val studentHiddenList = client.get("/api/v1/teacher-folders") {
                header(HttpHeaders.Authorization, "Bearer student-token")
            }
            val studentHiddenBody = studentHiddenList.bodyAsText()
            assertEquals(HttpStatusCode.OK, studentHiddenList.status, studentHiddenBody)
            assertTrue(!studentHiddenBody.contains(folder.id), studentHiddenBody)
            assertTrue(!studentHiddenBody.contains(childFolder.id), studentHiddenBody)

            // Hiding the folder tree must not revoke the independently public class catalog entry.
            val anonymousPublicClasses = client.get("/api/v1/public/classes")
            val anonymousPublicClassesBody = anonymousPublicClasses.bodyAsText()
            assertEquals(HttpStatusCode.OK, anonymousPublicClasses.status, anonymousPublicClassesBody)
            assertTrue(anonymousPublicClassesBody.contains(publicClassId), anonymousPublicClassesBody)

            val forbiddenDelete = client.delete("/api/v1/teacher-folders/${folder.id}") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenDelete.status)

            val adminDelete = client.delete("/api/v1/teacher-folders/${folder.id}") {
                header(HttpHeaders.Authorization, "Bearer admin-token")
            }
            // A parent with a child folder/class cannot be deleted; admin permissions do not bypass this safety rule.
            assertEquals(HttpStatusCode.Conflict, adminDelete.status, adminDelete.bodyAsText())
            assertTrue(folders.listVisible(adminId.toString(), setOf(AppRole.SUPER_ADMIN)).any { it.id == folder.id })
        }
    }

    @Test
    fun `teacher cannot create a folder for another owner over HTTP`() = testApplication {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "folder_create_route_${UUID.randomUUID().toString().replace("-", "")}"

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
            val ownerId = UUID.randomUUID()
            val otherTeacherId = UUID.randomUUID()
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO app_users(id, display_name) VALUES (?, 'استاد مالک'), (?, 'استاد دیگر')"
                ).use { statement ->
                    statement.setObject(1, ownerId)
                    statement.setObject(2, otherTeacherId)
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

            val identities = mapOf(
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

            val forbiddenCreate = client.post("/api/v1/teacher-folders") {
                header(HttpHeaders.Authorization, "Bearer other-teacher-token")
                contentType(ContentType.Application.Json)
                setBody("""{"ownerUserId":"$ownerId","name":"پوشه غیرمجاز","sortOrder":0}""")
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenCreate.status, forbiddenCreate.bodyAsText())

            val allowedCreate = client.post("/api/v1/teacher-folders") {
                header(HttpHeaders.Authorization, "Bearer owner-token")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"پوشه شخصی","sortOrder":0}""")
            }
            assertEquals(HttpStatusCode.Created, allowedCreate.status, allowedCreate.bodyAsText())
        }
    }
}
