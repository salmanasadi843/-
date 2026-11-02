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
            val teacherWithoutClasses = UUID.randomUUID()

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO app_users(id, display_name, status) VALUES (?, 'مدیر', 'active'), (?, 'استاد الف', 'active'), (?, 'استاد ب', 'active'), (?, 'دانشجو', 'active'), (?, 'مهمان', 'active'), (?, 'استاد بدون کلاس', 'active')"
                ).use { statement ->
                    statement.setObject(1, admin)
                    statement.setObject(2, ownerA)
                    statement.setObject(3, ownerB)
                    statement.setObject(4, student)
                    statement.setObject(5, visitor)
                    statement.setObject(6, teacherWithoutClasses)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO app_roles(user_id, role_name) VALUES (?, 'teacher'), (?, 'teacher'), (?, 'teacher')"
                ).use { statement ->
                    statement.setObject(1, ownerA)
                    statement.setObject(2, ownerB)
                    statement.setObject(3, teacherWithoutClasses)
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

            val teacherDirectory = repository.listVisibleTeachers(ownerB.toString(), setOf(AppRole.TEACHER))
            assertEquals(listOf(ownerA.toString()), teacherDirectory.map { it.userId })
            assertEquals("استاد الف", teacherDirectory.single().displayName)

            val studentDirectory = repository.listVisibleTeachers(student.toString(), setOf(AppRole.STUDENT))
            assertEquals(listOf(ownerA.toString()), studentDirectory.map { it.userId })
            val adminDirectory = repository.listVisibleTeachers(admin.toString(), setOf(AppRole.SUPER_ADMIN))
            assertEquals(
                setOf(ownerA.toString(), ownerB.toString(), teacherWithoutClasses.toString()),
                adminDirectory.map { it.userId }.toSet()
            )
            assertEquals(
                "استاد بدون کلاس",
                adminDirectory.single { it.userId == teacherWithoutClasses.toString() }.displayName
            )

            // A regular teacher remains read-only for another teacher's class,
            // while the super-admin can manage that same class.
            assertFailsWith<SecurityException> {
                repository.update(
                    ownerB.toString(), setOf(AppRole.TEACHER), publicClass.id,
                    UpdateTeachingClass("Unauthorized edit", "", "", "public", true, false, 0)
                )
            }
            val adminUpdatedClass = repository.update(
                admin.toString(), setOf(AppRole.SUPER_ADMIN), publicClass.id,
                UpdateTeachingClass("Admin managed class", "", "", "public", true, false, 0)
            )
            assertEquals("Admin managed class", adminUpdatedClass?.title)
            assertEquals(ownerA.toString(), adminUpdatedClass?.ownerUserId)

            val studentView = repository.listVisible(student.toString(), setOf(AppRole.STUDENT))
            assertEquals(
                setOf(publicClass.id, privateClass.id),
                studentView.map { it.id }.toSet()
            )
            val visitorView = repository.listVisible(visitor.toString(), emptySet())
            assertEquals(setOf(publicClass.id), visitorView.map { it.id }.toSet())

            // Anonymous catalog must never include private, hidden, or unpublished classes.
            assertEquals(setOf(publicClass.id), repository.listAnonymousPublic().map { it.id }.toSet())

            val ownerView = repository.listVisible(ownerA.toString(), setOf(AppRole.TEACHER))
            assertTrue(setOf(hiddenClass.id, unpublishedClass.id).all { id -> ownerView.any { it.id == id } })


            // Nested records remain attached to the class while it is in the recovery area.
            val nestedCourseId = UUID.randomUUID()
            val nestedSessionId = UUID.randomUUID()
            val publicSessionId = UUID.randomUUID()
            val unpublishedPublicSessionId = UUID.randomUUID()
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO teaching_sessions(id, class_id, title, transcript, original_transcript, is_published) VALUES (?, ?::uuid, 'Public session', 'public text', 'original public text', true), (?, ?::uuid, 'Unpublished session', 'secret text', 'secret original', false)"
                ).use { st ->
                    st.setObject(1, publicSessionId); st.setString(2, publicClass.id)
                    st.setObject(3, unpublishedPublicSessionId); st.setString(4, publicClass.id)
                    st.executeUpdate()
                }
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
            val publicSessionRepository = TeachingSessionRepository(dataSource)
            assertEquals(listOf(publicSessionId.toString()), publicSessionRepository.listAnonymousPublic(publicClass.id).map { it.id })
            assertEquals("public text", publicSessionRepository.findAnonymousPublic(publicClass.id, publicSessionId.toString())?.transcript)
            assertTrue(publicSessionRepository.findAnonymousPublic(publicClass.id, unpublishedPublicSessionId.toString()) == null)
            assertTrue(publicSessionRepository.findAnonymousPublic(privateClass.id, nestedSessionId.toString()) == null)
            assertTrue(publicSessionRepository.findAnonymousPublic(publicClass.id, nestedSessionId.toString()) == null)

            assertEquals(true, repository.setDeleted(admin.toString(), setOf(AppRole.SUPER_ADMIN), privateClass.id, false))
            assertEquals(false, repository.setDeleted(admin.toString(), setOf(AppRole.SUPER_ADMIN), privateClass.id, false))
            assertTrue(repository.listVisible(ownerA.toString(), setOf(AppRole.TEACHER)).any { it.id == privateClass.id })
            assertTrue(TeachingSessionRepository(dataSource).listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).any { it.id == nestedSessionId.toString() })
            assertTrue(TeachingCourseRepository(dataSource).listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).any { it.id == nestedCourseId.toString() })

            // A super-admin can manage nested content owned by another teacher;
            // an ordinary teacher can only manage content in their own classes.
            val courseRepository = TeachingCourseRepository(dataSource)
            assertFailsWith<SecurityException> {
                courseRepository.update(
                    ownerB.toString(), setOf(AppRole.TEACHER), nestedCourseId.toString(),
                    UpdateTeachingCourse("Unauthorized course edit", "", "", null, 0)
                )
            }
            val adminCourseUpdate = courseRepository.update(
                admin.toString(), setOf(AppRole.SUPER_ADMIN), nestedCourseId.toString(),
                UpdateTeachingCourse("Admin updated course", "استاد الف", "", null, 0)
            )
            assertNotNull(adminCourseUpdate)
            assertEquals("Admin updated course", adminCourseUpdate.title)

            val sessionRepository = TeachingSessionRepository(dataSource)
            assertFailsWith<SecurityException> {
                sessionRepository.update(
                    ownerB.toString(), setOf(AppRole.TEACHER), nestedSessionId.toString(),
                    UpdateTeachingSession(
                        nestedCourseId.toString(), "Unauthorized session edit", "", "", null, 0,
                        "bad edit", "original", null, null, null, "", true, null, 0
                    )
                )
            }
            val adminSessionUpdate = sessionRepository.update(
                admin.toString(), setOf(AppRole.SUPER_ADMIN), nestedSessionId.toString(),
                UpdateTeachingSession(
                    nestedCourseId.toString(), "Admin updated session", "Nested course", "استاد الف",
                    null, 0, "admin edited transcript", "original", "admin summary", "key points",
                    null, "", true, null, 0
                )
            )
            assertNotNull(adminSessionUpdate)
            assertEquals("Admin updated session", adminSessionUpdate.title)
            assertEquals("admin edited transcript", adminSessionUpdate.transcript)
            assertEquals("original", adminSessionUpdate.originalTranscript)
            assertFailsWith<SecurityException> {
                sessionRepository.setDeleted(ownerB.toString(), setOf(AppRole.TEACHER), nestedSessionId.toString(), true)
            }
            assertEquals(true, sessionRepository.setDeleted(admin.toString(), setOf(AppRole.SUPER_ADMIN), nestedSessionId.toString(), true))
            assertTrue(sessionRepository.listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).none { it.id == nestedSessionId.toString() })
            assertEquals(true, sessionRepository.setDeleted(admin.toString(), setOf(AppRole.SUPER_ADMIN), nestedSessionId.toString(), false))
            assertTrue(sessionRepository.listVisible(admin.toString(), setOf(AppRole.SUPER_ADMIN)).any { it.id == nestedSessionId.toString() })

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
                        assertEquals(9, rows.getInt(1))
                    }
                }
            }
        }
    }
}
