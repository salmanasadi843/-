package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserSessionNoteRepositoryIntegrationTest {
    @Test
    fun `notes are private editable deletable and attached only to visible sessions`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "session_notes_${UUID.randomUUID().toString().replace("-", "")}"
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
                    st.setObject(1, owner); st.setObject(2, student); st.setObject(3, otherStudent)
                    st.executeUpdate()
                }
            }
            val classes = TeachingClassRepository(ds)
            val publicClass = classes.create(owner.toString(),
                CreateTeachingClass(owner.toString(), "عمومی", "", "", "public", true))
            val privateClass = classes.create(owner.toString(),
                CreateTeachingClass(owner.toString(), "خصوصی", "", "", "private", true))
            val sessions = TeachingSessionRepository(ds)
            val publicSession = sessions.create(owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(publicClass.id, title = "جلسه عمومی", transcript = "متن درس نمونه"))
            val privateSession = sessions.create(owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(privateClass.id, title = "جلسه خصوصی"))
            val repository = UserSessionNoteRepository(ds, sessions)

            assertTrue(repository.list(student.toString(), setOf(AppRole.STUDENT), publicClass.id, publicSession.id).isEmpty())
            val created = repository.create(
                student.toString(), setOf(AppRole.STUDENT), publicClass.id, publicSession.id,
                "درس نمونه", "نکته مهم", 0, 9
            )
            assertEquals("نکته مهم", created.noteText)
            assertEquals(0, created.startOffset)
            assertEquals(9, created.endOffset)

            val ownNotes = repository.list(student.toString(), setOf(AppRole.STUDENT), publicClass.id, publicSession.id)
            assertEquals(1, ownNotes.size)
            assertEquals(created.id, ownNotes.single().id)
            assertTrue(repository.list(otherStudent.toString(), setOf(AppRole.STUDENT), publicClass.id, publicSession.id).isEmpty())

            val globalNotes = repository.listAllVisible(student.toString(), setOf(AppRole.STUDENT))
            assertEquals(1, globalNotes.size)
            assertEquals(publicClass.id, globalNotes.single().classId)
            assertEquals("عمومی", globalNotes.single().classTitle)
            assertEquals("جلسه عمومی", globalNotes.single().sessionTitle)
            assertEquals(created.id, globalNotes.single().id)
            assertTrue(repository.listAllVisible(otherStudent.toString(), setOf(AppRole.STUDENT)).isEmpty())

            val updated = repository.update(
                student.toString(), setOf(AppRole.STUDENT), created.id,
                "درس نمونه", "نکته ویرایش‌شده", 0, 9
            )
            assertEquals("نکته ویرایش‌شده", updated.noteText)
            assertFailsWith<NoSuchElementException> {
                repository.update(otherStudent.toString(), setOf(AppRole.STUDENT), created.id,
                    "", "تلاش برای ویرایش یادداشت دیگران", null, null)
            }
            assertFalse(repository.delete(otherStudent.toString(), setOf(AppRole.STUDENT), created.id))
            assertEquals(1, repository.list(student.toString(), setOf(AppRole.STUDENT), publicClass.id, publicSession.id).size)
            assertTrue(repository.delete(student.toString(), setOf(AppRole.STUDENT), created.id))
            assertTrue(repository.list(student.toString(), setOf(AppRole.STUDENT), publicClass.id, publicSession.id).isEmpty())

            assertTrue(repository.list(otherStudent.toString(), setOf(AppRole.STUDENT), privateClass.id, privateSession.id).isEmpty())
            assertFailsWith<NoSuchElementException> {
                repository.create(otherStudent.toString(), setOf(AppRole.STUDENT), privateClass.id, privateSession.id,
                    "", "یادداشت غیرمجاز", null, null)
            }
            assertFailsWith<IllegalArgumentException> {
                repository.create(student.toString(), setOf(AppRole.STUDENT), publicClass.id, publicSession.id,
                    "", "بازه نامعتبر", 8, 2)
            }
        }
    }
}
