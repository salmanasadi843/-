package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeachingAudioSourceRepositoryIntegrationTest {
    @Test
    fun `audio sources preserve portable metadata and enforce session ownership`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "audio_repo_${UUID.randomUUID().toString().replace("-", "")}"
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
            val admin = UUID.randomUUID()
            val otherTeacher = UUID.randomUUID()
            ds.connection.use { connection ->
                connection.prepareStatement("INSERT INTO app_users(id) VALUES (?), (?), (?)").use { st ->
                    st.setObject(1, owner)
                    st.setObject(2, admin)
                    st.setObject(3, otherTeacher)
                    st.executeUpdate()
                }
            }
            ds.connection.use { connection ->
                connection.prepareStatement("INSERT INTO app_roles(user_id,role_name) VALUES (?,'teacher')").use {
                    it.setObject(1, owner)
                    it.executeUpdate()
                }
            }
            val classRecord = TeachingClassRepository(ds).create(
                owner.toString(),
                CreateTeachingClass(owner.toString(), "Audio class", "", "", "public", true)
            )
            val session = TeachingSessionRepository(ds).create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(classId = classRecord.id, title = "Audio session")
            )
            val repository = TeachingAudioSourceRepository(ds)

            val direct = repository.create(
                owner.toString(), setOf(AppRole.TEACHER), session.id,
                CreateTeachingAudioSource(
                    sourceKind = "direct_url",
                    sourceUrl = "https://media.example.test/lecture.mp3",
                    title = "صدای جلسه",
                    sortOrder = 1
                )
            )
            assertEquals("https://media.example.test/lecture.mp3", direct.sourceUrl)
            assertNull(direct.storageKey)

            val uploaded = repository.create(
                owner.toString(), setOf(AppRole.TEACHER), session.id,
                CreateTeachingAudioSource(
                    sourceKind = "uploaded_asset",
                    storageKey = "audio/2026/11/session-object-01",
                    title = "فایل بارگذاری‌شده",
                    sortOrder = 2
                )
            )
            assertEquals("audio/2026/11/session-object-01", uploaded.storageKey)
            assertNull(uploaded.sourceUrl)

            assertEquals(2, repository.listVisible(otherTeacher.toString(), setOf(AppRole.TEACHER), session.id).size)
            assertEquals(2, repository.listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN), session.id).size)

            assertFailsWith<SecurityException> {
                repository.update(
                    otherTeacher.toString(), setOf(AppRole.TEACHER), session.id, direct.id,
                    UpdateTeachingAudioSource("https://media.example.test/changed.mp3", null, "forbidden", 0)
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.create(
                    owner.toString(), setOf(AppRole.TEACHER), session.id,
                    CreateTeachingAudioSource(sourceKind = "uploaded_asset", sourceUrl = "https://example.test/file")
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.create(
                    owner.toString(), setOf(AppRole.TEACHER), session.id,
                    CreateTeachingAudioSource(sourceKind = "direct_url", sourceUrl = "file:///private/device/path")
                )
            }

            val updated = repository.update(
                admin.toString(), setOf(AppRole.SUPER_ADMIN), session.id, direct.id,
                UpdateTeachingAudioSource("https://cdn.example.test/new.mp3", null, "نسخه جدید", 0)
            )
            assertNotNull(updated)
            assertEquals("https://cdn.example.test/new.mp3", updated.sourceUrl)
            assertTrue(repository.deactivate(owner.toString(), setOf(AppRole.TEACHER), session.id, uploaded.id))
            assertEquals(listOf(direct.id), repository.listVisible(owner.toString(), setOf(AppRole.TEACHER), session.id).map { it.id })

            ds.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT count(*) FROM audit_log WHERE entity_type = 'audio_source'"
                ).use { st ->
                    st.executeQuery().use { rs -> check(rs.next()); assertEquals(4, rs.getInt(1)) }
                }
            }
        }
    }
}
