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

class TeacherFolderRepositoryIntegrationTest {
    @Test
    fun `folder ownership hierarchy visibility and deletion are enforced`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "folder_repo_${UUID.randomUUID().toString().replace("-", "")}"

        Flyway.configure().dataSource(jdbcUrl, username, password)
            .schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load().migrate()

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
            ds.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO app_users(id,status) VALUES (?, 'active'), (?, 'active'), (?, 'active'), (?, 'active')"
                ).use { s ->
                    s.setObject(1, admin); s.setObject(2, owner)
                    s.setObject(3, otherTeacher); s.setObject(4, student)
                    s.executeUpdate()
                }
            }

            val repo = TeacherFolderRepository(ds)
            val ownerRoles = setOf(AppRole.TEACHER)
            val root = repo.create(owner.toString(), ownerRoles,
                CreateTeacherFolder(owner.toString(), null, "اصول"))
            val child = repo.create(owner.toString(), ownerRoles,
                CreateTeacherFolder(owner.toString(), root.id, "جلسات"))
            val otherRoot = repo.create(otherTeacher.toString(), ownerRoles,
                CreateTeacherFolder(otherTeacher.toString(), null, "پوشه استاد دیگر"))

            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO teaching_classes(owner_user_id, folder_id, title, visibility, is_published, is_hidden)
                    VALUES (?, ?, 'کلاس عمومی', 'public', true, false)
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, owner)
                    statement.setObject(2, UUID.fromString(child.id))
                    statement.executeUpdate()
                }
            }
            val studentFolders = repo.listVisible(student.toString(), setOf(AppRole.STUDENT))
            assertTrue(studentFolders.any { it.id == root.id }, "Student must receive the parent folder of a visible nested class.")
            assertTrue(studentFolders.any { it.id == child.id }, "Student must receive the folder containing the visible class.")
            assertTrue(studentFolders.none { it.id == otherRoot.id }, "Student must not receive unrelated empty teacher folders.")

            assertFailsWith<IllegalArgumentException> {
                repo.create(owner.toString(), ownerRoles,
                    CreateTeacherFolder(owner.toString(), otherRoot.id, "پوشه نامعتبر"))
            }
            assertFailsWith<IllegalArgumentException> {
                repo.update(owner.toString(), ownerRoles, root.id,
                    UpdateTeacherFolder(otherRoot.id, "اصول", 0, false))
            }
            assertFailsWith<SecurityException> {
                repo.update(otherTeacher.toString(), ownerRoles, root.id,
                    UpdateTeacherFolder(null, "غیرمجاز", 0, false))
            }
            assertFailsWith<IllegalArgumentException> {
                repo.update(owner.toString(), ownerRoles, root.id,
                    UpdateTeacherFolder(child.id, "اصول", 0, false))
            }
            assertFailsWith<IllegalStateException> {
                repo.delete(owner.toString(), ownerRoles, root.id)
            }

            val adminUpdate = repo.update(admin.toString(), setOf(AppRole.SUPER_ADMIN), root.id,
                UpdateTeacherFolder(null, "اصول اصلاح‌شده", 2, true))
            assertEquals("اصول اصلاح‌شده", adminUpdate?.name)
            assertTrue(repo.listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).any { it.id == root.id })

            // A hidden parent hides its whole folder branch from other users, avoiding orphaned children.
            val studentFoldersAfterHide = repo.listVisible(student.toString(), setOf(AppRole.STUDENT))
            assertTrue(studentFoldersAfterHide.none { it.id == root.id || it.id == child.id },
                "Students must not receive descendants whose parent folder is hidden.")

            // The class must be moved out before its containing folder can be deleted.
            ds.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE teaching_classes SET folder_id = NULL WHERE owner_user_id = ? AND folder_id = ?::uuid"
                ).use { statement ->
                    statement.setObject(1, owner)
                    statement.setString(2, child.id)
                    statement.executeUpdate()
                }
            }
            assertTrue(repo.delete(owner.toString(), ownerRoles, child.id))
            assertTrue(repo.delete(owner.toString(), ownerRoles, root.id))
            assertTrue(repo.delete(otherTeacher.toString(), ownerRoles, otherRoot.id))
            assertTrue(repo.listVisible(owner.toString(), ownerRoles).isEmpty())

            ds.connection.use { c ->
                c.prepareStatement("SELECT count(*) FROM audit_log WHERE entity_type = 'teacher_folder'").use { s ->
                    s.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(7, rows.getInt(1))
                    }
                }
            }
        }
    }
}
