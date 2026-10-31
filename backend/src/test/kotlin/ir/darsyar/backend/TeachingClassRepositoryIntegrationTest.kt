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

class TeachingClassRepositoryIntegrationTest {
    @Test
    fun `class visibility management and audit follow server roles and ownership`() {
        val configuredJdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!configuredJdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val jdbcUrl = requireNotNull(configuredJdbcUrl)
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "class_repo_${UUID.randomUUID().toString().replace("-", "")}"

        Flyway.configure()
            .dataSource(jdbcUrl, username, password)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = 3
            connectionInitSql = "SET search_path TO $schema"
        }
        HikariDataSource(config).use { dataSource ->
            val admin = UUID.randomUUID()
            val ownerA = UUID.randomUUID()
            val ownerB = UUID.randomUUID()
            val student = UUID.randomUUID()

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO app_users(id, status) VALUES (?, 'active'), (?, 'active'), (?, 'active'), (?, 'active')"
                ).use { statement ->
                    statement.setObject(1, admin)
                    statement.setObject(2, ownerA)
                    statement.setObject(3, ownerB)
                    statement.setObject(4, student)
                    statement.executeUpdate()
                }
            }

            val repository = TeachingClassRepository(dataSource)
            val publicClass = repository.create(
                ownerA.toString(),
                CreateTeachingClass(ownerA.toString(), "Public published", "", "", "public", true)
            )
            val privateClass = repository.create(
                ownerA.toString(),
                CreateTeachingClass(ownerA.toString(), "Private published", "", "", "private", true)
            )
            val hiddenClass = repository.create(
                ownerA.toString(),
                CreateTeachingClass(ownerA.toString(), "Hidden published", "", "", "public", true)
            ).let {
                repository.update(
                    ownerA.toString(), setOf(AppRole.TEACHER), it.id,
                    UpdateTeachingClass(it.title, it.description, it.term, it.visibility, true, true, it.sortOrder)
                )!!
            }
            val unpublishedClass = repository.create(
                ownerA.toString(),
                CreateTeachingClass(ownerA.toString(), "Unpublished", "", "", "private", false)
            )

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO class_memberships(class_id, student_user_id, status) VALUES (?::uuid, ?, 'active')"
                ).use { statement ->
                    statement.setString(1, privateClass.id)
                    statement.setObject(2, student)
                    statement.executeUpdate()
                }
            }

            val teacherView = repository.listVisible(ownerB.toString(), setOf(AppRole.TEACHER))
            assertEquals(
                setOf(publicClass.id, privateClass.id),
                teacherView.map { it.id }.toSet()
            )

            val studentView = repository.listVisible(student.toString(), setOf(AppRole.STUDENT))
            assertEquals(
                setOf(publicClass.id, privateClass.id),
                studentView.map { it.id }.toSet()
            )

            val ownerView = repository.listVisible(ownerA.toString(), setOf(AppRole.TEACHER))
            assertTrue(setOf(hiddenClass.id, unpublishedClass.id).all { id -> ownerView.any { it.id == id } })

            val adminView = repository.listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN))
            assertEquals(4, adminView.size)

            assertFailsWith<SecurityException> {
                repository.update(
                    ownerB.toString(), setOf(AppRole.TEACHER), privateClass.id,
                    UpdateTeachingClass("Unauthorized edit", "", "", "private", true, false, 0)
                )
            }

            val adminUpdated = repository.update(
                admin.toString(), setOf(AppRole.SUPER_ADMIN), privateClass.id,
                UpdateTeachingClass("Admin corrected title", "", "", "private", true, false, 0)
            )
            assertNotNull(adminUpdated)
            assertEquals("Admin corrected title", adminUpdated.title)

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT count(*) FROM audit_log WHERE entity_type = 'class'"
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(6, rows.getInt(1))
                    }
                }
            }
        }
    }
}
