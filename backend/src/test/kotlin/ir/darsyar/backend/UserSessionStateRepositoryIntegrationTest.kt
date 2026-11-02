package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UserSessionStateRepositoryIntegrationTest {
    @Test
    fun `bookmark and playback state are private per user and require session visibility`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "session_state_${UUID.randomUUID().toString().replace("-", "")}"
        Flyway.configure().dataSource(jdbcUrl, username, password)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = 3
            connectionInitSql = "SET search_path TO $schema"
        }
        HikariDataSource(config).use { ds ->
            val owner = UUID.randomUUID()
            val student = UUID.randomUUID()
            val otherStudent = UUID.randomUUID()
            ds.connection.use { connection ->
                connection.prepareStatement("INSERT INTO app_users(id) VALUES (?), (?), (?)").use { st ->
                    st.setObject(1, owner)
                    st.setObject(2, student)
                    st.setObject(3, otherStudent)
                    st.executeUpdate()
                }
            }

            val classes = TeachingClassRepository(ds)
            val publicClass = classes.create(
                owner.toString(),
                CreateTeachingClass(owner.toString(), "عمومی", "", "", "public", true)
            )
            val privateClass = classes.create(
                owner.toString(),
                CreateTeachingClass(owner.toString(), "خصوصی", "", "", "private", true)
            )
            val sessions = TeachingSessionRepository(ds)
            val publicSession = sessions.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(publicClass.id, title = "جلسه عمومی")
            )
            val privateSession = sessions.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(privateClass.id, title = "جلسه خصوصی")
            )
            val repository = UserSessionStateRepository(ds, sessions)

            val initial = repository.get(
                student.toString(), setOf(AppRole.STUDENT), publicClass.id, publicSession.id
            )
            assertEquals(false, initial?.isFavorite)
            assertEquals(0L, initial?.lastPlaybackPositionMs)

            val saved = repository.put(
                student.toString(), setOf(AppRole.STUDENT), publicClass.id, publicSession.id,
                isFavorite = true, lastPlaybackPositionMs = 125_000L
            )
            assertTrue(saved.isFavorite)
            assertEquals(125_000L, saved.lastPlaybackPositionMs)

            val restored = repository.get(
                student.toString(), setOf(AppRole.STUDENT), publicClass.id, publicSession.id
            )
            assertEquals(saved.isFavorite, restored?.isFavorite)
            assertEquals(saved.lastPlaybackPositionMs, restored?.lastPlaybackPositionMs)

            val otherUserState = repository.get(
                otherStudent.toString(), setOf(AppRole.STUDENT), publicClass.id, publicSession.id
            )
            assertEquals(false, otherUserState?.isFavorite)
            assertEquals(0L, otherUserState?.lastPlaybackPositionMs)

            assertEquals(
                null,
                repository.get(otherStudent.toString(), setOf(AppRole.STUDENT), privateClass.id, privateSession.id)
            )
            assertFailsWith<NoSuchElementException> {
                repository.put(
                    otherStudent.toString(), setOf(AppRole.STUDENT), privateClass.id, privateSession.id,
                    isFavorite = true, lastPlaybackPositionMs = 1L
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.put(
                    student.toString(), setOf(AppRole.STUDENT), publicClass.id, publicSession.id,
                    isFavorite = false, lastPlaybackPositionMs = -1L
                )
            }
        }
    }
}
