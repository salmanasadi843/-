package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionSearchRouteIntegrationTest {
    @Test
    fun `session search endpoint returns only sessions visible to each authenticated role`() = testApplication {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "session_search_route_${UUID.randomUUID().toString().replace("-", "")}"

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
            val memberId = UUID.randomUUID()
            val visitorId = UUID.randomUUID()

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO app_users(id) VALUES (?), (?), (?), (?), (?)"
                ).use { statement ->
                    listOf(adminId, ownerId, otherTeacherId, memberId, visitorId).forEachIndexed { index, id ->
                        statement.setObject(index + 1, id)
                    }
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
            val publicClass = classRepository.create(
                ownerId.toString(),
                CreateTeachingClass(ownerId.toString(), "Public search class", "", "", "public", true)
            )
            val privateClass = classRepository.create(
                ownerId.toString(),
                CreateTeachingClass(ownerId.toString(), "Private search class", "", "", "private", true)
            )

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO class_memberships(class_id, student_user_id, status) VALUES (?::uuid, ?::uuid, 'active')"
                ).use { statement ->
                    statement.setString(1, privateClass.id)
                    statement.setString(2, memberId.toString())
                    statement.executeUpdate()
                }
            }

            val sessionRepository = TeachingSessionRepository(dataSource)
            sessionRepository.create(
                ownerId.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(
                    classId = publicClass.id,
                    title = "جلسه عمومی کلیدواژه مشترک",
                    transcript = "متن عمومی کلیدواژه مشترک",
                    isPublished = true
                )
            )
            sessionRepository.create(
                ownerId.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(
                    classId = privateClass.id,
                    title = "جلسه خصوصی کلیدواژه مشترک",
                    transcript = "متن خصوصی کلیدواژه مشترک",
                    isPublished = true
                )
            )
            sessionRepository.create(
                ownerId.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(
                    classId = privateClass.id,
                    title = "پیش‌نویس کلیدواژه مشترک",
                    transcript = "این مورد نباید برای دانشجو دیده شود",
                    isPublished = false
                )
            )

            val ids = mapOf(
                "admin" to adminId.toString(),
                "owner" to ownerId.toString(),
                "other-teacher" to otherTeacherId.toString(),
                "member" to memberId.toString(),
                "visitor" to visitorId.toString()
            )
            val roles = mapOf(
                "admin" to setOf(AppRole.SUPER_ADMIN),
                "owner" to setOf(AppRole.TEACHER),
                "other-teacher" to setOf(AppRole.TEACHER),
                "member" to setOf(AppRole.STUDENT),
                "visitor" to setOf(AppRole.STUDENT)
            )
            val verifier = IdentityTokenVerifier { token ->
                check(token in ids)
                VerifiedIdentity(provider = "firebase", subject = token)
            }
            val userRepository = AuthenticatedUserRepository { provider, subject ->
                check(provider == "firebase")
                AuthenticatedUserRecord(
                    userId = ids.getValue(subject),
                    status = "active",
                    roles = roles.getValue(subject)
                )
            }

            application {
                module(dataSource, verifier, userRepository)
            }

            suspend fun searchAs(token: String): String {
                val response = client.get("/api/v1/sessions/search?q=کلیدواژه&mode=all") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.OK, response.status, "Search failed for token=$token: $body")
                return body
            }

            val adminResults = searchAs("admin")
            assertTrue(adminResults.contains("جلسه عمومی"))
            assertTrue(adminResults.contains("جلسه خصوصی"))
            // Super-admin can search all owned and cross-teacher content, including drafts.
            assertTrue(adminResults.contains("پیش‌نویس"))

            val ownerResults = searchAs("owner")
            assertTrue(ownerResults.contains("جلسه عمومی"))
            assertTrue(ownerResults.contains("جلسه خصوصی"))
            // Owners can search their own drafts; drafts remain hidden from other roles.
            assertTrue(ownerResults.contains("پیش‌نویس"))

            val otherTeacherResults = searchAs("other-teacher")
            assertTrue(otherTeacherResults.contains("جلسه عمومی"))
            assertFalse(otherTeacherResults.contains("جلسه خصوصی"))
            assertFalse(otherTeacherResults.contains("پیش‌نویس"))

            val memberResults = searchAs("member")
            assertTrue(memberResults.contains("جلسه عمومی"))
            assertTrue(memberResults.contains("جلسه خصوصی"))
            assertFalse(memberResults.contains("پیش‌نویس"))

            val visitorResults = searchAs("visitor")
            assertTrue(visitorResults.contains("جلسه عمومی"))
            assertFalse(visitorResults.contains("جلسه خصوصی"))
            assertFalse(visitorResults.contains("پیش‌نویس"))

            suspend fun searchQuery(token: String, query: String, mode: String = "all"): String {
                val response = client.get("/api/v1/sessions/search?q=$query&mode=$mode") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                return response.bodyAsText()
            }
            // Exact phrase must match contiguous text, not merely all separate terms.
            val exactPhrase = searchQuery("visitor", "متن%20عمومی%20کلیدواژه", "phrase")
            assertTrue(exactPhrase.contains("جلسه عمومی"))
            assertFalse(exactPhrase.contains("جلسه خصوصی"))
            // Persian/Arabic letter variants are normalized by the route.
            val normalized = searchQuery("visitor", "%DA%A9%D9%84%DB%8C%D8%AF%D9%88%D8%A7%DA%98%D9%87")
            assertTrue(normalized.contains("جلسه عمومی"))
            assertFalse(normalized.contains("جلسه خصوصی"))
            // Phrase with the same words in a different order must not match.
            val reorderedPhrase = searchQuery("visitor", "کلیدواژه%20متن", "phrase")
            assertFalse(reorderedPhrase.contains("جلسه عمومی"))
        }
    }
}
