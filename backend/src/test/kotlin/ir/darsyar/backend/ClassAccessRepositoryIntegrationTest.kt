package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClassAccessRepositoryIntegrationTest {
    @Test
    fun `private class code creates active membership and enforces owner student and use limits`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "class_access_${UUID.randomUUID().toString().replace("-", "")}"
        Flyway.configure().dataSource(jdbcUrl, username, password)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = 4
            connectionInitSql = "SET search_path TO $schema"
        }
        HikariDataSource(config).use { ds ->
            val owner = UUID.randomUUID()
            val otherTeacher = UUID.randomUUID()
            val studentA = UUID.randomUUID()
            val studentB = UUID.randomUUID()
            ds.connection.use { connection ->
                connection.prepareStatement("INSERT INTO app_users(id) VALUES (?), (?), (?), (?)").use { st ->
                    st.setObject(1, owner)
                    st.setObject(2, otherTeacher)
                    st.setObject(3, studentA)
                    st.setObject(4, studentB)
                    st.executeUpdate()
                }
            }
            ds.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO app_roles(user_id, role_name) VALUES (?, 'teacher'), (?, 'teacher')"
                ).use { st ->
                    st.setObject(1, owner)
                    st.setObject(2, otherTeacher)
                    st.executeUpdate()
                }
            }
            val classRecord = TeachingClassRepository(ds).create(
                owner.toString(),
                CreateTeachingClass(owner.toString(), "Private class", "", "", "private", true)
            )
            val repository = ClassAccessRepository(ds)
            assertFailsWith<SecurityException> {
                repository.createCode(otherTeacher.toString(), setOf(AppRole.TEACHER), classRecord.id)
            }
            val created = repository.createCode(
                owner.toString(), setOf(AppRole.TEACHER), classRecord.id,
                Instant.now().plusSeconds(3600), 1
            )
            assertEquals(10, created.code.length)
            assertNotEquals(created.code, created.code.lowercase())

            val firstJoin = repository.redeem(studentA.toString(), setOf(AppRole.STUDENT), created.code)
            assertEquals("active", firstJoin.membership.status)
            assertEquals(false, firstJoin.alreadyActive)

            val repeatJoin = repository.redeem(studentA.toString(), setOf(AppRole.STUDENT), created.code)
            assertTrue(repeatJoin.alreadyActive)

            assertFailsWith<IllegalArgumentException> {
                repository.redeem(studentB.toString(), setOf(AppRole.STUDENT), created.code)
            }
            assertFailsWith<SecurityException> {
                repository.redeem(otherTeacher.toString(), setOf(AppRole.TEACHER), created.code)
            }

            val listedCodes = repository.listCodes(owner.toString(), setOf(AppRole.TEACHER), classRecord.id)
            val listedCreatedCode = listedCodes.single { it.id == created.id }
            assertEquals(1, listedCreatedCode.useCount)
            assertEquals(1, listedCreatedCode.maxUses)
            assertTrue(listedCreatedCode.isActive)
            assertEquals(created.expiresAt, listedCreatedCode.expiresAt)
            assertFailsWith<SecurityException> {
                repository.listCodes(otherTeacher.toString(), setOf(AppRole.TEACHER), classRecord.id)
            }

            val members = repository.listMembers(owner.toString(), setOf(AppRole.TEACHER), classRecord.id)
            assertEquals(1, members.count { it.status == "active" && it.studentUserId == studentA.toString() })
            assertFailsWith<SecurityException> {
                repository.listMembers(otherTeacher.toString(), setOf(AppRole.TEACHER), classRecord.id)
            }
            assertFailsWith<SecurityException> {
                repository.revokeMembership(otherTeacher.toString(), setOf(AppRole.TEACHER), classRecord.id, studentA.toString())
            }

            val listedForStudent = TeachingClassRepository(ds).listVisible(
                studentA.toString(), setOf(AppRole.STUDENT)
            )
            assertTrue(listedForStudent.any { it.id == classRecord.id })
            assertTrue(TeachingClassRepository(ds).listVisible(
                studentB.toString(), setOf(AppRole.STUDENT)
            ).none { it.id == classRecord.id })
            assertTrue(repository.revokeMembership(owner.toString(), setOf(AppRole.TEACHER), classRecord.id, studentA.toString()))
            assertTrue(!repository.revokeMembership(owner.toString(), setOf(AppRole.TEACHER), classRecord.id, studentA.toString()))
            assertTrue(TeachingClassRepository(ds).listVisible(
                studentA.toString(), setOf(AppRole.STUDENT)
            ).none { it.id == classRecord.id })

            val unlimited = repository.createCode(owner.toString(), setOf(AppRole.TEACHER), classRecord.id)
            assertTrue(repository.deactivateCode(owner.toString(), setOf(AppRole.TEACHER), unlimited.id))
            assertFailsWith<IllegalArgumentException> {
                repository.redeem(studentB.toString(), setOf(AppRole.STUDENT), unlimited.code)
            }
            assertFailsWith<SecurityException> {
                repository.deactivateCode(otherTeacher.toString(), setOf(AppRole.TEACHER), created.id)
            }

            ds.connection.use { connection ->
                connection.prepareStatement("SELECT count(*) FROM audit_log WHERE entity_type IN ('class_access_code','class_membership')").use { st ->
                    st.executeQuery().use { rs -> check(rs.next()); assertEquals(5, rs.getInt(1)) }
                }
                connection.prepareStatement("SELECT code_hash FROM class_access_codes WHERE id = ?").use { st ->
                    st.setObject(1, UUID.fromString(created.id))
                    st.executeQuery().use { rs ->
                        check(rs.next())
                        assertNotEquals(created.code, rs.getString(1))
                        assertEquals(64, rs.getString(1).length)
                    }
                }
            }
        }
    }
}
