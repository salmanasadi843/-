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

class TeacherAdministrationRepositoryIntegrationTest {
    @Test
    fun `teacher role is granted only after admin approval and removed on revocation`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "teacher_admin_${UUID.randomUUID().toString().replace("-", "")}"
        Flyway.configure().dataSource(jdbcUrl, username, password)
            .schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load().migrate()

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = 2
            connectionInitSql = "SET search_path TO $schema"
        }
        HikariDataSource(config).use { dataSource ->
            val adminId = UUID.randomUUID()
            val teacherId = UUID.randomUUID()
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO app_users(id, display_name) VALUES (?, 'Admin'), (?, 'Teacher')"
                ).use { st ->
                    st.setObject(1, adminId); st.setObject(2, teacherId); st.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO app_roles(user_id, role_name) VALUES (?, 'super_admin'), (?, 'student')"
                ).use { st ->
                    st.setObject(1, adminId); st.setObject(2, teacherId); st.executeUpdate()
                }
            }

            val repository = TeacherAdministrationRepository(dataSource)
            val submitted = repository.submitApplication(teacherId.toString(), "فقه", "حوزه علمیه")
            assertEquals("pending", submitted.approvalStatus)
            assertEquals(1, repository.listPendingApplications().size)

            val rejected = assertNotNull(
                repository.decideApplication(adminId.toString(), teacherId.toString(), "rejected")
            )
            assertEquals("rejected", rejected.approvalStatus)

            val resubmitted = repository.submitApplication(teacherId.toString(), "اصول فقه", null)
            assertEquals("pending", resubmitted.approvalStatus)
            assertEquals(1, repository.listPendingApplications().size)

            val approved = assertNotNull(repository.decideApplication(adminId.toString(), teacherId.toString(), "approved"))
            assertEquals("approved", approved.approvalStatus)
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT count(*) FROM app_roles WHERE user_id = ? AND role_name = 'teacher'"
                ).use { st ->
                    st.setObject(1, teacherId)
                    st.executeQuery().use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                }
            }

            val revoked = assertNotNull(repository.decideApplication(adminId.toString(), teacherId.toString(), "revoked"))
            assertEquals("revoked", revoked.approvalStatus)
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT count(*) FROM app_roles WHERE user_id = ? AND role_name = 'teacher'"
                ).use { st ->
                    st.setObject(1, teacherId)
                    st.executeQuery().use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
                }
                connection.prepareStatement(
                    "SELECT count(*) FROM audit_log WHERE entity_type = 'teacher_profile' AND owner_user_id = ?"
                ).use { st ->
                    st.setObject(1, teacherId)
                    st.executeQuery().use { rs -> rs.next(); assertEquals(5, rs.getInt(1)) }
                }
            }
            assertFailsWith<IllegalStateException> {
                repository.submitApplication(teacherId.toString(), "فقه", null)
            }
        }
    }
}
