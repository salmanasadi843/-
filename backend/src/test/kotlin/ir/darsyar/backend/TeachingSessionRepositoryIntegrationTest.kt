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
import kotlin.test.assertTrue

class TeachingSessionRepositoryIntegrationTest {
    @Test
    fun `session visibility ownership and full content are enforced`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "session_repo_${UUID.randomUUID().toString().replace("-", "")}"
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
            val admin = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val otherTeacher = UUID.randomUUID()
            val student = UUID.randomUUID()
            val visitor = UUID.randomUUID()
            ds.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO app_users(id) VALUES (?), (?), (?), (?), (?)"
                ).use { st ->
                    st.setObject(1, admin); st.setObject(2, owner)
                    st.setObject(3, otherTeacher); st.setObject(4, student); st.setObject(5, visitor)
                    st.executeUpdate()
                }
            }
            val classes = TeachingClassRepository(ds)
            val publicClass = classes.create(
                owner.toString(),
                CreateTeachingClass(owner.toString(), "Public", "", "", "public", true)
            )
            val privateClass = classes.create(
                owner.toString(),
                CreateTeachingClass(owner.toString(), "Private", "", "", "private", true)
            )
            ds.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO class_memberships(class_id, student_user_id, status) VALUES (?, ?, 'active')"
                ).use { st ->
                    st.setObject(1, privateClass.id.let(UUID::fromString))
                    st.setObject(2, student)
                    st.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO teaching_courses(class_id, title) VALUES (?, 'اختیاری')"
                ).use { st ->
                    st.setObject(1, publicClass.id.let(UUID::fromString))
                    st.executeUpdate()
                }
            }
            val courseId = ds.connection.use { connection ->
                connection.prepareStatement("SELECT id::text FROM teaching_courses WHERE class_id = ?::uuid").use { st ->
                    st.setString(1, publicClass.id)
                    st.executeQuery().use { rs -> check(rs.next()); rs.getString(1) }
                }
            }
            val repository = TeachingSessionRepository(ds)
            val published = repository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(
                    classId = publicClass.id, courseId = courseId, title = "جلسه منتشرشده",
                    dateMs = 123456L, audioDurationMs = 9000L,
                    transcript = "متن ویرایش‌شده", originalTranscript = "متن اصلی",
                    aiSummary = "خلاصه", aiKeyPoints = "کلیدواژه‌ها",
                    aiQuizJson = """{"items":[]}""", tags = "اصول,فقه", isPublished = true
                )
            )
            val unpublished = repository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(classId = privateClass.id, title = "پیش‌نویس", isPublished = false)
            )
            val privatePublished = repository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(classId = privateClass.id, title = "خصوصی", isPublished = true)
            )
            assertEquals("متن اصلی", published.originalTranscript)
            assertEquals("متن ویرایش‌شده", published.transcript)
            assertEquals("خلاصه", published.aiSummary)
            assertEquals(courseId, published.courseId)

            val teacherView = repository.listVisible(otherTeacher.toString(), setOf(AppRole.TEACHER))
            assertEquals(setOf(published.id), teacherView.map { it.id }.toSet())
            val studentView = repository.listVisible(student.toString(), setOf(AppRole.STUDENT))
            assertEquals(setOf(published.id, privatePublished.id), studentView.map { it.id }.toSet())
            val visitorView = repository.listVisible(visitor.toString(), emptySet())
            assertEquals(setOf(published.id), visitorView.map { it.id }.toSet())
            val ownerView = repository.listVisible(owner.toString(), setOf(AppRole.TEACHER))
            assertTrue(ownerView.any { it.id == unpublished.id })
            assertEquals(3, repository.listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).size)

            // Detail reads return full content only for a session visible to this actor
            // and only when both the requested class and session IDs match.
            assertEquals(
                "متن اصلی",
                repository.findVisible(otherTeacher.toString(), setOf(AppRole.TEACHER), publicClass.id, published.id)?.originalTranscript
            )
            assertEquals(
                null,
                repository.findVisible(visitor.toString(), emptySet(), privateClass.id, privatePublished.id)
            )
            assertEquals(
                null,
                repository.findVisible(otherTeacher.toString(), setOf(AppRole.TEACHER), privateClass.id, published.id)
            )
            assertEquals(
                null,
                repository.findVisible(student.toString(), setOf(AppRole.STUDENT), privateClass.id, unpublished.id)
            )
            assertEquals(
                "متن اصلی",
                repository.findVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN), publicClass.id, published.id)?.originalTranscript
            )

            assertFailsWith<SecurityException> {
                repository.update(
                    otherTeacher.toString(), setOf(AppRole.TEACHER), published.id,
                    UpdateTeachingSession(null, "تغییر غیرمجاز", "", "", null, 0, "", "", null, null, null, "", true, null, 0)
                )
            }
            val updated = repository.update(
                admin.toString(), setOf(AppRole.SUPER_ADMIN), published.id,
                UpdateTeachingSession(courseId, "ویرایش مدیر", "", "", 123456L, 9000L,
                    "متن ویرایش‌شده", "متن اصلی", "خلاصه", "کلیدواژه‌ها",
                    """{"items":[]}""", "اصول,فقه", true, 123456L, 2)
            )
            assertNotNull(updated)
            assertEquals("ویرایش مدیر", updated.title)
            assertEquals(2L, updated.version)

            assertFailsWith<SecurityException> {
                repository.setDeleted(otherTeacher.toString(), setOf(AppRole.TEACHER), published.id, true)
            }
            assertEquals(true, repository.setDeleted(admin.toString(), setOf(AppRole.SUPER_ADMIN), published.id, true))
            assertTrue(repository.listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).none { it.id == published.id })
            assertEquals(setOf(published.id), repository.listDeleted(admin.toString(), setOf(AppRole.SUPER_ADMIN)).map { it.id }.toSet())
            assertEquals(setOf(published.id), repository.listDeleted(owner.toString(), setOf(AppRole.TEACHER)).map { it.id }.toSet())
            assertTrue(repository.listDeleted(otherTeacher.toString(), setOf(AppRole.TEACHER)).isEmpty())
            assertFailsWith<SecurityException> {
                repository.listDeleted(student.toString(), setOf(AppRole.STUDENT))
            }
            assertEquals(false, repository.setDeleted(admin.toString(), setOf(AppRole.SUPER_ADMIN), published.id, true))
            assertEquals(true, repository.setDeleted(admin.toString(), setOf(AppRole.SUPER_ADMIN), published.id, false))
            assertTrue(repository.listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).any { it.id == published.id })
            ds.connection.use { connection ->
                connection.prepareStatement("SELECT count(*) FROM audit_log WHERE entity_type = 'session'").use { st ->
                    st.executeQuery().use { rs -> check(rs.next()); assertEquals(6, rs.getInt(1)) }
                }
            }
        }
    }
}
