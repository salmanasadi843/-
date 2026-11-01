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

class TeachingCourseRepositoryIntegrationTest {
    @Test
    fun `courses remain optional class groupings with owner checks and safe deletion`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "course_repo_${UUID.randomUUID().toString().replace("-", "")}"
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
            val student = UUID.randomUUID()
            ds.connection.use { connection ->
                connection.prepareStatement("INSERT INTO app_users(id) VALUES (?), (?), (?), (?)").use { st ->
                    st.setObject(1, owner)
                    st.setObject(2, admin)
                    st.setObject(3, otherTeacher)
                    st.setObject(4, student)
                    st.executeUpdate()
                }
            }
            val classRecord = TeachingClassRepository(ds).create(
                owner.toString(),
                CreateTeachingClass(owner.toString(), "Public class", "", "", "public", true)
            )
            val repository = TeachingCourseRepository(ds)
            val course = repository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingCourse(classRecord.id, "مقدمات", "استاد", "توضیحات", 100L, 1)
            )
            assertEquals(1, repository.listVisible(owner.toString(), setOf(AppRole.TEACHER), classRecord.id).size)
            assertEquals(1, repository.listVisible(otherTeacher.toString(), setOf(AppRole.TEACHER), classRecord.id).size)
            assertEquals(1, repository.listVisible(student.toString(), setOf(AppRole.STUDENT), classRecord.id).size)
            assertEquals(1, repository.listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN), classRecord.id).size)

            assertFailsWith<SecurityException> {
                repository.update(
                    otherTeacher.toString(), setOf(AppRole.TEACHER), course.id,
                    UpdateTeachingCourse("تغییر غیرمجاز", "", "", null, 0)
                )
            }
            val updated = repository.update(
                admin.toString(), setOf(AppRole.SUPER_ADMIN), course.id,
                UpdateTeachingCourse("مقدمات اصلاح‌شده", "استاد", "شرح", 200L, 2)
            )
            assertEquals("مقدمات اصلاح‌شده", updated?.title)
            assertEquals(classRecord.id, updated?.classId)

            val session = TeachingSessionRepository(ds).create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(classId = classRecord.id, courseId = course.id, title = "جلسه اول")
            )
            assertEquals(course.id, session.courseId)
            assertFailsWith<IllegalStateException> {
                repository.delete(owner.toString(), setOf(AppRole.TEACHER), course.id)
            }

            val emptyCourse = repository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingCourse(classRecord.id, "دوره خالی")
            )
            assertTrue(repository.delete(owner.toString(), setOf(AppRole.TEACHER), emptyCourse.id))
            assertEquals(listOf(course.id), repository.listVisible(owner.toString(), setOf(AppRole.TEACHER), classRecord.id).map { it.id })

            ds.connection.use { connection ->
                connection.prepareStatement("SELECT count(*) FROM audit_log WHERE entity_type = 'course'").use { st ->
                    st.executeQuery().use { rs -> check(rs.next()); assertEquals(4, rs.getInt(1)) }
                }
            }
        }
    }
}
