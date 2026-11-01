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
            val visitor = UUID.randomUUID()

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO app_users(id, status) VALUES (?, 'active'), (?, 'active'), (?, 'active'), (?, 'active'), (?, 'active')"
                ).use { statement ->
                    statement.setObject(1, admin)
                    statement.setObject(2, ownerA)
                    statement.setObject(3, ownerB)
                    statement.setObject(4, student)
                    statement.setObject(5, visitor)
                    statement.executeUpdate()
                }
            }

            val repository = TeachingClassRepository(dataSource)
            val folderRepository = TeacherFolderRepository(dataSource)
            val ownerFolder = folderRepository.create(
                ownerA.toString(), setOf(AppRole.TEACHER),
                CreateTeacherFolder(ownerA.toString(), null, "اصول")
            )
            val hiddenFolder = folderRepository.create(
                ownerA.toString(), setOf(AppRole.TEACHER),
                CreateTeacherFolder(ownerA.toString(), null, "مخفی")
            ).let {
                folderRepository.update(
                    ownerA.toString(), setOf(AppRole.TEACHER), it.id,
                    UpdateTeacherFolder(null, it.name, it.sortOrder, true)
                )!!
            }
            val publicClass = repository.create(
                ownerA.toString(),
                CreateTeachingClass(ownerA.toString(), "Public published", "", "", "public", true, ownerFolder.id)
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

            assertEquals(ownerFolder.id, publicClass.folderId)

            val teacherFolders = folderRepository.listVisible(ownerB.toString(), setOf(AppRole.TEACHER))
            assertTrue(teacherFolders.any { it.id == ownerFolder.id })
            assertTrue(teacherFolders.none { it.id == hiddenFolder.id })
            val studentFolders = folderRepository.listVisible(student.toString(), setOf(AppRole.STUDENT))
            assertTrue(studentFolders.any { it.id == ownerFolder.id })
            assertTrue(studentFolders.none { it.id == hiddenFolder.id })

            val teacherView = repository.listVisible(ownerB.toString(), setOf(AppRole.TEACHER))
            assertEquals(
                setOf(publicClass.id),
                teacherView.map { it.id }.toSet()
            )

            val studentView = repository.listVisible(student.toString(), setOf(AppRole.STUDENT))
            assertEquals(
                setOf(publicClass.id, privateClass.id),
                studentView.map { it.id }.toSet()
            )
            val visitorView = repository.listVisible(visitor.toString(), emptySet())
            assertEquals(setOf(publicClass.id), visitorView.map { it.id }.toSet())

            val ownerView = repository.listVisible(ownerA.toString(), setOf(AppRole.TEACHER))
            assertTrue(setOf(hiddenClass.id, unpublishedClass.id).all { id -> ownerView.any { it.id == id } })


            // Nested records remain attached to the class while it is in the recovery area.
            val nestedCourseId = UUID.randomUUID()
            val nestedSessionId = UUID.randomUUID()
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO teaching_courses(id, class_id, title) VALUES (?, ?::uuid, 'Nested course')"
                ).use { st ->
                    st.setObject(1, nestedCourseId); st.setString(2, privateClass.id); st.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO teaching_sessions(id, class_id, course_id, title, transcript, original_transcript) VALUES (?, ?::uuid, ?, 'Nested session', 'edited', 'original')"
                ).use { st ->
                    st.setObject(1, nestedSessionId); st.setString(2, privateClass.id)
                    st.setObject(3, nestedCourseId); st.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO session_audio_sources(session_id, source_kind, source_url) VALUES (?, 'direct_url', 'https://media.example.test/a.mp3')"
                ).use { st -> st.setObject(1, nestedSessionId); st.executeUpdate() }
                connection.prepareStatement(
                    "INSERT INTO class_access_codes(class_id, code_hash, created_by) VALUES (?::uuid, ?, ?)"
                ).use { st -> st.setString(1, privateClass.id); st.setString(2, "a".repeat(64)); st.setObject(3, ownerA); st.executeUpdate() }
            }

            assertFailsWith<SecurityException> {
                repository.setDeleted(ownerB.toString(), setOf(AppRole.TEACHER), privateClass.id, true)
            }
            assertEquals(true, repository.setDeleted(ownerA.toString(), setOf(AppRole.TEACHER), privateClass.id, true))
            assertEquals(false, repository.setDeleted(ownerA.toString(), setOf(AppRole.TEACHER), privateClass.id, true))
            assertTrue(repository.listVisible(ownerA.toString(), setOf(AppRole.TEACHER)).none { it.id == privateClass.id })
            assertTrue(repository.listVisible(student.toString(), setOf(AppRole.STUDENT)).none { it.id == privateClass.id })
            assertTrue(repository.listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).none { it.id == privateClass.id })
            assertEquals(listOf(privateClass.id), repository.listDeleted(ownerA.toString(), setOf(AppRole.TEACHER)).map { it.id })
            assertTrue(repository.listDeleted(ownerB.toString(), setOf(AppRole.TEACHER)).isEmpty())
            assertTrue(TeachingSessionRepository(dataSource).listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).none { it.id == nestedSessionId.toString() })
            assertTrue(TeachingCourseRepository(dataSource).listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).none { it.id == nestedCourseId.toString() })
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT
                      (SELECT count(*) FROM teaching_courses WHERE id = ?) +
                      (SELECT count(*) FROM teaching_sessions WHERE id = ?) +
                      (SELECT count(*) FROM session_audio_sources WHERE session_id = ?) +
                      (SELECT count(*) FROM class_memberships WHERE class_id = ?::uuid) +
                      (SELECT count(*) FROM class_access_codes WHERE class_id = ?::uuid)
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, nestedCourseId); st.setObject(2, nestedSessionId)
                    st.setObject(3, nestedSessionId); st.setString(4, privateClass.id)
                    st.setString(5, privateClass.id)
                    st.executeQuery().use { rs -> assertTrue(rs.next()); assertEquals(5, rs.getInt(1)) }
                }
            }
            assertEquals(true, repository.setDeleted(admin.toString(), setOf(AppRole.SUPER_ADMIN), privateClass.id, false))
            assertEquals(false, repository.setDeleted(admin.toString(), setOf(AppRole.SUPER_ADMIN), privateClass.id, false))
            assertTrue(repository.listVisible(ownerA.toString(), setOf(AppRole.TEACHER)).any { it.id == privateClass.id })
            assertTrue(TeachingSessionRepository(dataSource).listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).any { it.id == nestedSessionId.toString() })
            assertTrue(TeachingCourseRepository(dataSource).listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).any { it.id == nestedCourseId.toString() })

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
                        assertEquals(8, rows.getInt(1))
                    }
                }
            }
        }
    }
}
