package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.bearerAuth
import io.ktor.http.contentType
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TeachingSessionRoutesAuthorizationIntegrationTest {
    @Test
    fun `non-owner teacher can read public sessions but cannot mutate them over HTTP`() {
        val jdbcUrl = System.getenv("AUTH_REPOSITORY_TEST_DB_URL")
        assumeTrue(!jdbcUrl.isNullOrBlank(), "PostgreSQL integration database is not configured")
        val username = System.getenv("AUTH_REPOSITORY_TEST_DB_USER") ?: "postgres"
        val password = System.getenv("AUTH_REPOSITORY_TEST_DB_PASSWORD") ?: ""
        val schema = "session_http_${UUID.randomUUID().toString().replace("-", "")}"

        Flyway.configure().dataSource(jdbcUrl, username, password)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()

        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = 4
            connectionInitSql = "SET search_path TO $schema"
        }

        HikariDataSource(config).use { dataSource ->
            val admin = UUID.randomUUID()
            val owner = UUID.randomUUID()
            val otherTeacher = UUID.randomUUID()
            val student = UUID.randomUUID()
            dataSource.connection.use { connection ->
                connection.prepareStatement("INSERT INTO app_users(id) VALUES (?), (?), (?), (?)").use { statement ->
                    statement.setObject(1, admin)
                    statement.setObject(2, owner)
                    statement.setObject(3, otherTeacher)
                    statement.setObject(4, student)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO app_roles(user_id, role_name) VALUES (?, 'super_admin'), (?, 'teacher'), (?, 'teacher'), (?, 'student')"
                ).use { statement ->
                    statement.setObject(1, admin)
                    statement.setObject(2, owner)
                    statement.setObject(3, otherTeacher)
                    statement.setObject(4, student)
                    statement.executeUpdate()
                }
            }

            val classRepository = TeachingClassRepository(dataSource)
            val ownerClass = classRepository.create(
                owner.toString(),
                CreateTeachingClass(owner.toString(), "Owner public class", "", "", "public", true)
            )
            val otherTeacherClass = classRepository.create(
                otherTeacher.toString(),
                CreateTeachingClass(otherTeacher.toString(), "Other teacher class", "", "", "private", true)
            )
            val memberPrivateClass = classRepository.create(
                otherTeacher.toString(),
                CreateTeachingClass(otherTeacher.toString(), "Member-only private class", "", "", "private", true)
            )
            val hiddenPublicClass = classRepository.create(
                otherTeacher.toString(),
                CreateTeachingClass(otherTeacher.toString(), "Hidden public class", "", "", "public", true)
            )
            classRepository.update(
                otherTeacher.toString(), setOf(AppRole.TEACHER), hiddenPublicClass.id,
                UpdateTeachingClass("Hidden public class", "", "", "public", true, true, 0)
            )
            val unpublishedPublicClass = classRepository.create(
                otherTeacher.toString(),
                CreateTeachingClass(otherTeacher.toString(), "Unpublished public class", "", "", "public", false)
            )
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO class_memberships(class_id, student_user_id, status) VALUES (?::uuid, ?, 'active')"
                ).use { statement ->
                    statement.setString(1, memberPrivateClass.id)
                    statement.setObject(2, student)
                    statement.executeUpdate()
                }
            }
            val folderRepository = TeacherFolderRepository(dataSource)
            val ownerFolder = folderRepository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeacherFolder(owner.toString(), null, "پوشه استاد مالک")
            )
            val foreignFolder = folderRepository.create(
                otherTeacher.toString(), setOf(AppRole.TEACHER),
                CreateTeacherFolder(otherTeacher.toString(), null, "پوشه استاد دیگر")
            )
            val courseRepository = TeachingCourseRepository(dataSource)
            val ownerCourse = courseRepository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingCourse(ownerClass.id, "دوره مالک")
            )
            val foreignCourse = courseRepository.create(
                otherTeacher.toString(), setOf(AppRole.TEACHER),
                CreateTeachingCourse(otherTeacherClass.id, "دوره استاد دیگر")
            )
            val sessionRepository = TeachingSessionRepository(dataSource)
            val activeSession = sessionRepository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(ownerClass.id, title = "Published session")
            )
            val deletedSession = sessionRepository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(ownerClass.id, title = "Deleted session")
            )
            sessionRepository.setDeleted(owner.toString(), setOf(AppRole.TEACHER), deletedSession.id, true)
            val foreignSession = sessionRepository.create(
                otherTeacher.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(otherTeacherClass.id, title = "Foreign teacher session")
            )
            val memberPrivateSession = sessionRepository.create(
                otherTeacher.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(memberPrivateClass.id, title = "Member-only private session")
            )
            val hiddenPublicSession = sessionRepository.create(
                otherTeacher.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(hiddenPublicClass.id, title = "Hidden public session")
            )
            val unpublishedPublicSession = sessionRepository.create(
                otherTeacher.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(unpublishedPublicClass.id, title = "Unpublished class session")
            )
            val unpublishedSession = sessionRepository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(ownerClass.id, title = "Unpublished session", isPublished = false)
            )
            val ownerManagedSession = sessionRepository.create(
                owner.toString(), setOf(AppRole.TEACHER),
                CreateTeachingSession(ownerClass.id, title = "Owner managed session")
            )
            val seededAudioSource = TeachingAudioSourceRepository(dataSource).create(
                owner.toString(), setOf(AppRole.TEACHER), activeSession.id,
                CreateTeachingAudioSource(
                    sourceKind = "direct_url",
                    sourceUrl = "https://media.example.test/seeded-session-audio.mp3",
                    title = "صدای آزمون HTTP"
                )
            )

            testApplication {
                application {
                    module(
                        dataSource = dataSource,
                        tokenVerifier = IdentityTokenVerifier { token ->
                            VerifiedIdentity(provider = "firebase", subject = token)
                        },
                        userRepository = AuthenticatedUserRepository { provider, subject ->
                            check(provider == "firebase")
                            when (subject) {
                                "admin-token" -> AuthenticatedUserRecord(
                                    userId = admin.toString(), status = "active",
                                    roles = setOf(AppRole.SUPER_ADMIN)
                                )
                                "owner-token" -> AuthenticatedUserRecord(
                                    userId = owner.toString(), status = "active",
                                    roles = setOf(AppRole.TEACHER)
                                )
                                "other-teacher-token" -> AuthenticatedUserRecord(
                                    userId = otherTeacher.toString(), status = "active",
                                    roles = setOf(AppRole.TEACHER)
                                )
                                "student-token" -> AuthenticatedUserRecord(
                                    userId = student.toString(), status = "active",
                                    roles = setOf(AppRole.STUDENT)
                                )
                                else -> null
                            }
                        }
                    )
                }

                suspend fun verifyAudioSourceAuthorization() {
                    val path = "/api/v1/sessions/${activeSession.id}/audio-sources"

                    val guestList = client.get(path)
                    assertEquals(HttpStatusCode.Unauthorized, guestList.status)

                    val studentList = client.get(path) { bearerAuth("student-token") }
                    assertEquals(HttpStatusCode.OK, studentList.status)
                    assertContains(studentList.bodyAsText(), seededAudioSource.id)

                    val foreignTeacherList = client.get(path) { bearerAuth("other-teacher-token") }
                    assertEquals(HttpStatusCode.OK, foreignTeacherList.status)
                    assertContains(foreignTeacherList.bodyAsText(), seededAudioSource.id)

                    val studentCreate = client.post(path) {
                        bearerAuth("student-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"sourceKind":"direct_url","sourceUrl":"https://media.example.test/student.mp3"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, studentCreate.status)

                    val foreignTeacherCreate = client.post(path) {
                        bearerAuth("other-teacher-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"sourceKind":"direct_url","sourceUrl":"https://media.example.test/foreign.mp3"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignTeacherCreate.status)

                    val studentUpdate = client.patch("$path/${seededAudioSource.id}") {
                        bearerAuth("student-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"sourceUrl":"https://media.example.test/student-edit.mp3","title":"ویرایش دانشجو","sortOrder":1}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, studentUpdate.status)

                    val foreignTeacherUpdate = client.patch("$path/${seededAudioSource.id}") {
                        bearerAuth("other-teacher-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"sourceUrl":"https://media.example.test/foreign-edit.mp3","title":"ویرایش استاد دیگر","sortOrder":1}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignTeacherUpdate.status)

                    val studentDelete = client.delete("$path/${seededAudioSource.id}") {
                        bearerAuth("student-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, studentDelete.status)

                    val foreignTeacherDelete = client.delete("$path/${seededAudioSource.id}") {
                        bearerAuth("other-teacher-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignTeacherDelete.status)

                    val ownerUpdate = client.patch("$path/${seededAudioSource.id}") {
                        bearerAuth("owner-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"sourceUrl":"https://media.example.test/owner-edit.mp3","title":"ویرایش مالک","sortOrder":2}""")
                    }
                    assertEquals(HttpStatusCode.OK, ownerUpdate.status)

                    val adminUpdate = client.patch("$path/${seededAudioSource.id}") {
                        bearerAuth("admin-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"sourceUrl":"https://media.example.test/admin-edit.mp3","title":"ویرایش مدیر","sortOrder":3}""")
                    }
                    assertEquals(HttpStatusCode.OK, adminUpdate.status)

                    val ownerDelete = client.delete("$path/${seededAudioSource.id}") {
                        bearerAuth("owner-token")
                    }
                    assertEquals(HttpStatusCode.NoContent, ownerDelete.status)
                }

                suspend fun verifyAnnouncementAuthorization() {
                    val ownerPath = "/api/v1/classes/${ownerClass.id}/announcements"

                    val guestList = client.get(ownerPath)
                    assertEquals(HttpStatusCode.Unauthorized, guestList.status)

                    val guestCreate = client.post(ownerPath) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"اعلان مهمان","body":"نباید منتشر شود"}""")
                    }
                    assertEquals(HttpStatusCode.Unauthorized, guestCreate.status)

                    val foreignTeacherCreate = client.post(ownerPath) {
                        bearerAuth("other-teacher-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"اعلان غیرمجاز","body":"استاد غیرمالک"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignTeacherCreate.status)

                    val studentCreate = client.post(ownerPath) {
                        bearerAuth("student-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"اعلان دانشجو","body":"نباید منتشر شود"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, studentCreate.status)

                    val ownerCreate = client.post(ownerPath) {
                        bearerAuth("owner-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"اطلاعیه استاد مالک","body":"متن اطلاعیه عمومی"}""")
                    }
                    val ownerBody = ownerCreate.bodyAsText()
                    assertEquals(HttpStatusCode.Created, ownerCreate.status, ownerBody)
                    val ownerAnnouncementId = Regex(""""id":"([^"]+)"""")
                        .find(ownerBody)?.groupValues?.get(1)
                    assertFalse(ownerAnnouncementId.isNullOrBlank(), ownerBody)

                    val ownerList = client.get(ownerPath) { bearerAuth("owner-token") }
                    assertEquals(HttpStatusCode.OK, ownerList.status)
                    assertContains(ownerList.bodyAsText(), ownerAnnouncementId!!)

                    val foreignTeacherPublicList = client.get(ownerPath) {
                        bearerAuth("other-teacher-token")
                    }
                    assertEquals(HttpStatusCode.OK, foreignTeacherPublicList.status)
                    assertContains(foreignTeacherPublicList.bodyAsText(), ownerAnnouncementId)

                    val studentPublicList = client.get(ownerPath) { bearerAuth("student-token") }
                    assertEquals(HttpStatusCode.OK, studentPublicList.status)
                    assertContains(studentPublicList.bodyAsText(), ownerAnnouncementId)

                    val foreignTeacherDelete = client.delete("$ownerPath/$ownerAnnouncementId") {
                        bearerAuth("other-teacher-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignTeacherDelete.status)

                    val studentDelete = client.delete("$ownerPath/$ownerAnnouncementId") {
                        bearerAuth("student-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, studentDelete.status)

                    val ownerDelete = client.delete("$ownerPath/$ownerAnnouncementId") {
                        bearerAuth("owner-token")
                    }
                    assertEquals(HttpStatusCode.NoContent, ownerDelete.status)

                    val adminPrivateCreate = client.post("/api/v1/classes/${otherTeacherClass.id}/announcements") {
                        bearerAuth("admin-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"اطلاعیه مدیر در کلاس خصوصی","body":"فقط اعضا"}""")
                    }
                    val adminPrivateBody = adminPrivateCreate.bodyAsText()
                    assertEquals(HttpStatusCode.Created, adminPrivateCreate.status, adminPrivateBody)
                    val privateAnnouncementId = Regex(""""id":"([^"]+)"""")
                        .find(adminPrivateBody)?.groupValues?.get(1)
                    assertFalse(privateAnnouncementId.isNullOrBlank(), adminPrivateBody)

                    val nonMemberPrivateList = client.get("/api/v1/classes/${otherTeacherClass.id}/announcements") {
                        bearerAuth("student-token")
                    }
                    assertEquals(HttpStatusCode.OK, nonMemberPrivateList.status)
                    assertFalse(nonMemberPrivateList.bodyAsText().contains(privateAnnouncementId!!))

                    val adminMemberClassCreate = client.post("/api/v1/classes/${memberPrivateClass.id}/announcements") {
                        bearerAuth("admin-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"اطلاعیه برای عضو","body":"اعضای کلاس می‌بینند"}""")
                    }
                    val memberBody = adminMemberClassCreate.bodyAsText()
                    assertEquals(HttpStatusCode.Created, adminMemberClassCreate.status, memberBody)
                    val memberAnnouncementId = Regex(""""id":"([^"]+)"""")
                        .find(memberBody)?.groupValues?.get(1)
                    assertFalse(memberAnnouncementId.isNullOrBlank(), memberBody)

                    val memberList = client.get("/api/v1/classes/${memberPrivateClass.id}/announcements") {
                        bearerAuth("student-token")
                    }
                    assertEquals(HttpStatusCode.OK, memberList.status)
                    assertContains(memberList.bodyAsText(), memberAnnouncementId!!)

                    val adminDelete = client.delete("/api/v1/classes/${otherTeacherClass.id}/announcements/$privateAnnouncementId") {
                        bearerAuth("admin-token")
                    }
                    assertEquals(HttpStatusCode.NoContent, adminDelete.status)
                }

                suspend fun verifySessionMutationPermissions() {
                    val readResponse = client.get("/api/v1/classes/${ownerClass.id}/sessions") {
                        bearerAuth("other-teacher-token")
                    }
                    assertEquals(HttpStatusCode.OK, readResponse.status)

                    val createResponse = client.post("/api/v1/classes/${ownerClass.id}/sessions") {
                        bearerAuth("other-teacher-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Unauthorized HTTP session"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, createResponse.status)

                    val updateResponse = client.patch("/api/v1/sessions/${activeSession.id}") {
                        bearerAuth("other-teacher-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Unauthorized update"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, updateResponse.status)

                    val reorderResponse = client.post("/api/v1/sessions/${activeSession.id}/reorder") {
                        bearerAuth("other-teacher-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"direction":"up"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, reorderResponse.status)

                    val moveResponse = client.post("/api/v1/sessions/${activeSession.id}/move") {
                        bearerAuth("other-teacher-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"targetClassId":"${otherTeacherClass.id}"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, moveResponse.status)

                    val deleteResponse = client.delete("/api/v1/sessions/${activeSession.id}") {
                        bearerAuth("other-teacher-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, deleteResponse.status)

                    val restoreResponse = client.post("/api/v1/sessions/${deletedSession.id}/restore") {
                        bearerAuth("other-teacher-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, restoreResponse.status)

                    // The owner may perform the same mutations on their own content.
                    val ownerCreate = client.post("/api/v1/classes/${ownerClass.id}/sessions") {
                        bearerAuth("owner-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Owner-created session"}""")
                    }
                    assertEquals(HttpStatusCode.Created, ownerCreate.status)

                    val ownerUpdate = client.patch("/api/v1/sessions/${ownerManagedSession.id}") {
                        bearerAuth("owner-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Owner updated session"}""")
                    }
                    assertEquals(HttpStatusCode.OK, ownerUpdate.status)

                    val ownerReorder = client.post("/api/v1/sessions/${ownerManagedSession.id}/reorder") {
                        bearerAuth("owner-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"direction":"up"}""")
                    }
                    assertEquals(HttpStatusCode.OK, ownerReorder.status)

                    val ownerDelete = client.delete("/api/v1/sessions/${ownerManagedSession.id}") {
                        bearerAuth("owner-token")
                    }
                    assertEquals(HttpStatusCode.NoContent, ownerDelete.status)

                    val ownerRestore = client.post("/api/v1/sessions/${ownerManagedSession.id}/restore") {
                        bearerAuth("owner-token")
                    }
                    assertEquals(HttpStatusCode.NoContent, ownerRestore.status)

                    // A student is authenticated but cannot create or mutate teacher content.
                    val studentCreate = client.post("/api/v1/classes/${ownerClass.id}/sessions") {
                        bearerAuth("student-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Student-created session"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, studentCreate.status)

                    val studentUpdate = client.patch("/api/v1/sessions/${activeSession.id}") {
                        bearerAuth("student-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Student update"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, studentUpdate.status)

                    val studentDelete = client.delete("/api/v1/sessions/${activeSession.id}") {
                        bearerAuth("student-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, studentDelete.status)

                    // The main admin may manage another teacher's content, including moving
                    // it across teacher ownership while preserving the session identity.
                    val adminMove = client.post("/api/v1/sessions/${foreignSession.id}/move") {
                        bearerAuth("admin-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"targetClassId":"${ownerClass.id}"}""")
                    }
                    assertEquals(HttpStatusCode.OK, adminMove.status)
                    val movedSessionJson = adminMove.bodyAsText()
                    assertContains(movedSessionJson, foreignSession.id)
                    assertContains(movedSessionJson, ownerClass.id)

                    val adminUpdate = client.patch("/api/v1/sessions/${foreignSession.id}") {
                        bearerAuth("admin-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Admin updated foreign session"}""")
                    }
                    assertEquals(HttpStatusCode.OK, adminUpdate.status)

                    val adminDelete = client.delete("/api/v1/sessions/${foreignSession.id}") {
                        bearerAuth("admin-token")
                    }
                    assertEquals(HttpStatusCode.NoContent, adminDelete.status)

                    val adminRestore = client.post("/api/v1/sessions/${foreignSession.id}/restore") {
                        bearerAuth("admin-token")
                    }
                    assertEquals(HttpStatusCode.NoContent, adminRestore.status)

                    // No bearer token represents a guest; protected mutation endpoints reject it.
                    val guestCreate = client.post("/api/v1/classes/${ownerClass.id}/sessions") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Guest-created session"}""")
                    }
                    assertEquals(HttpStatusCode.Unauthorized, guestCreate.status)
                    

                }

                suspend fun verifyPublicReadVisibility() {
                    // HTTP read visibility: anonymous users see only public, published,
                    // non-hidden content; authenticated students need active membership
                    // for private classes, while teachers retain ownership boundaries.
                    val anonymousClasses = client.get("/api/v1/public/classes").bodyAsText()
                    assertContains(anonymousClasses, ownerClass.id)
                    assertFalse(anonymousClasses.contains(otherTeacherClass.id))
                    assertFalse(anonymousClasses.contains(memberPrivateClass.id))
                    assertFalse(anonymousClasses.contains(hiddenPublicClass.id))
                    assertFalse(anonymousClasses.contains(unpublishedPublicClass.id))

                    val anonymousPublicSessions = client.get(
                        "/api/v1/public/classes/${ownerClass.id}/sessions"
                    )
                    assertEquals(HttpStatusCode.OK, anonymousPublicSessions.status)
                    assertContains(anonymousPublicSessions.bodyAsText(), activeSession.id)

                    val anonymousPublicDetail = client.get(
                        "/api/v1/public/classes/${ownerClass.id}/sessions/${activeSession.id}"
                    )
                    assertEquals(HttpStatusCode.OK, anonymousPublicDetail.status)

                    val anonymousPrivateDetail = client.get(
                        "/api/v1/public/classes/${otherTeacherClass.id}/sessions/${foreignSession.id}"
                    )
                    assertEquals(HttpStatusCode.NotFound, anonymousPrivateDetail.status)

                    // A valid public class ID must not make a session from another class public.
                    val anonymousMismatchedParentDetail = client.get(
                        "/api/v1/public/classes/${ownerClass.id}/sessions/${foreignSession.id}"
                    )
                    assertEquals(HttpStatusCode.NotFound, anonymousMismatchedParentDetail.status, "PUBLIC_CROSS_CLASS_DETAIL: a session must not be readable through a different public class ID")

                    val anonymousHiddenSessions = client.get(
                        "/api/v1/public/classes/${hiddenPublicClass.id}/sessions"
                    )
                    assertEquals(HttpStatusCode.OK, anonymousHiddenSessions.status)
                    assertFalse(anonymousHiddenSessions.bodyAsText().contains(hiddenPublicSession.id))

                    val anonymousUnpublishedSessions = client.get(
                        "/api/v1/public/classes/${unpublishedPublicClass.id}/sessions"
                    )
                    assertEquals(HttpStatusCode.OK, anonymousUnpublishedSessions.status)
                    assertFalse(anonymousUnpublishedSessions.bodyAsText().contains(unpublishedPublicSession.id))

                    val anonymousUnpublishedSessionDetail = client.get(
                        "/api/v1/public/classes/${ownerClass.id}/sessions/${unpublishedSession.id}"
                    )
                    assertEquals(HttpStatusCode.NotFound, anonymousUnpublishedSessionDetail.status)

                    val ownerClasses = client.get("/api/v1/classes") {
                        bearerAuth("owner-token")
                    }.bodyAsText()
                    assertContains(ownerClasses, ownerClass.id)
                    assertFalse(ownerClasses.contains(otherTeacherClass.id))

                    val otherTeacherClasses = client.get("/api/v1/classes") {
                        bearerAuth("other-teacher-token")
                    }.bodyAsText()
                    assertContains(otherTeacherClasses, ownerClass.id)
                    assertContains(otherTeacherClasses, otherTeacherClass.id)
                    // The owning teacher retains access to their own hidden and unpublished classes.
                    assertContains(otherTeacherClasses, hiddenPublicClass.id)
                    assertContains(otherTeacherClasses, unpublishedPublicClass.id)

                    val studentClasses = client.get("/api/v1/classes") {
                        bearerAuth("student-token")
                    }.bodyAsText()
                    assertContains(studentClasses, ownerClass.id)
                    assertContains(studentClasses, memberPrivateClass.id)
                    assertFalse(studentClasses.contains(otherTeacherClass.id))
                    assertFalse(studentClasses.contains(hiddenPublicClass.id))
                    assertFalse(studentClasses.contains(unpublishedPublicClass.id))

                    val studentPrivateSessions = client.get(
                        "/api/v1/classes/${memberPrivateClass.id}/sessions"
                    ) {
                        bearerAuth("student-token")
                    }
                    assertEquals(HttpStatusCode.OK, studentPrivateSessions.status)
                    assertContains(studentPrivateSessions.bodyAsText(), memberPrivateSession.id)

                    val studentPrivateSessionDetail = client.get(
                        "/api/v1/classes/${memberPrivateClass.id}/sessions/${memberPrivateSession.id}"
                    ) {
                        bearerAuth("student-token")
                    }
                    check(studentPrivateSessionDetail.status == HttpStatusCode.OK) { "ACTIVE_MEMBER_DETAIL: expected 200, got ${studentPrivateSessionDetail.status}" }
                    assertContains(studentPrivateSessionDetail.bodyAsText(), memberPrivateSession.id)

                    // An active membership to one class must not authorize another private class.
                    val studentForeignPrivateSessionDetail = client.get(
                        "/api/v1/classes/${otherTeacherClass.id}/sessions/${foreignSession.id}"
                    ) {
                        bearerAuth("student-token")
                    }
                    check(studentForeignPrivateSessionDetail.status == HttpStatusCode.NotFound) { "FOREIGN_PRIVATE_DETAIL: expected 404, got ${studentForeignPrivateSessionDetail.status}" }

                    val studentUnjoinedPrivateSessions = client.get(
                        "/api/v1/classes/${otherTeacherClass.id}/sessions"
                    ) {
                        bearerAuth("student-token")
                    }
                    assertEquals(HttpStatusCode.OK, studentUnjoinedPrivateSessions.status)
                    assertFalse(studentUnjoinedPrivateSessions.bodyAsText().contains(foreignSession.id))

                    val studentHiddenSessions = client.get(
                        "/api/v1/classes/${hiddenPublicClass.id}/sessions"
                    ) {
                        bearerAuth("student-token")
                    }
                    assertEquals(HttpStatusCode.OK, studentHiddenSessions.status)
                    assertFalse(studentHiddenSessions.bodyAsText().contains(hiddenPublicSession.id))

                    val studentUnpublishedSessions = client.get(
                        "/api/v1/classes/${unpublishedPublicClass.id}/sessions"
                    ) {
                        bearerAuth("student-token")
                    }
                    assertEquals(HttpStatusCode.OK, studentUnpublishedSessions.status)
                    assertFalse(studentUnpublishedSessions.bodyAsText().contains(unpublishedPublicSession.id))


                }

                suspend fun verifyMembershipAuthorization() {
                    // Membership endpoints are restricted to the class owner or main admin.
                    val ownerMemberships = client.get(
                        "/api/v1/classes/${memberPrivateClass.id}/memberships"
                    ) {
                        bearerAuth("other-teacher-token")
                    }
                    assertEquals(HttpStatusCode.OK, ownerMemberships.status)
                    assertContains(ownerMemberships.bodyAsText(), student.toString())

                    val adminMemberships = client.get(
                        "/api/v1/classes/${memberPrivateClass.id}/memberships"
                    ) {
                        bearerAuth("admin-token")
                    }
                    assertEquals(HttpStatusCode.OK, adminMemberships.status)
                    assertContains(adminMemberships.bodyAsText(), student.toString())

                    val foreignTeacherMemberships = client.get(
                        "/api/v1/classes/${memberPrivateClass.id}/memberships"
                    ) {
                        bearerAuth("owner-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignTeacherMemberships.status)

                    val studentMemberships = client.get(
                        "/api/v1/classes/${memberPrivateClass.id}/memberships"
                    ) {
                        bearerAuth("student-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, studentMemberships.status)

                    val guestMemberships = client.get(
                        "/api/v1/classes/${memberPrivateClass.id}/memberships"
                    )
                    assertEquals(HttpStatusCode.Unauthorized, guestMemberships.status)

                    val foreignTeacherRevoke = client.delete(
                        "/api/v1/classes/${memberPrivateClass.id}/memberships/${student}"
                    ) {
                        bearerAuth("owner-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignTeacherRevoke.status)

                    val adminRevoke = client.delete(
                        "/api/v1/classes/${memberPrivateClass.id}/memberships/${student}"
                    ) {
                        bearerAuth("admin-token")
                    }
                    assertEquals(HttpStatusCode.NoContent, adminRevoke.status)

                    val studentAfterRevokeClasses = client.get("/api/v1/classes") {
                        bearerAuth("student-token")
                    }.bodyAsText()
                    assertFalse(studentAfterRevokeClasses.contains(memberPrivateClass.id))
                    assertContains(studentAfterRevokeClasses, ownerClass.id)

                    val studentAfterRevokeSessions = client.get(
                        "/api/v1/classes/${memberPrivateClass.id}/sessions"
                    ) {
                        bearerAuth("student-token")
                    }
                    assertEquals(HttpStatusCode.OK, studentAfterRevokeSessions.status)
                    assertFalse(studentAfterRevokeSessions.bodyAsText().contains(memberPrivateSession.id))

                    val studentAfterRevokeSessionDetail = client.get(
                        "/api/v1/classes/${memberPrivateClass.id}/sessions/${memberPrivateSession.id}"
                    ) {
                        bearerAuth("student-token")
                    }
                    check(studentAfterRevokeSessionDetail.status == HttpStatusCode.NotFound) { "REVOKED_MEMBER_DETAIL: expected 404, got ${studentAfterRevokeSessionDetail.status}" }

                    val adminClasses = client.get("/api/v1/classes") {
                        bearerAuth("admin-token")
                    }.bodyAsText()
                    assertContains(adminClasses, ownerClass.id)
                    assertContains(adminClasses, otherTeacherClass.id)

                    

                }

                suspend fun verifyClassAuthorization() {
                    // Class-level HTTP authorization: teachers manage only their own classes;
                    // the main admin may manage classes owned by any teacher.
                    val foreignClassUpdateByTeacher = client.patch("/api/v1/classes/${ownerClass.id}") {
                        bearerAuth("other-teacher-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Unauthorized class edit","description":"","term":"","visibility":"public","isPublished":true,"isHidden":false,"sortOrder":0}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignClassUpdateByTeacher.status)

                    val foreignClassDeleteByTeacher = client.delete("/api/v1/classes/${ownerClass.id}") {
                        bearerAuth("other-teacher-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignClassDeleteByTeacher.status)

                    val studentClassCreate = client.post("/api/v1/classes") {
                        bearerAuth("student-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Student class","visibility":"private"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, studentClassCreate.status)

                    val teacherCannotCreateForAnotherOwner = client.post("/api/v1/classes") {
                        bearerAuth("other-teacher-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"ownerUserId":"${owner}","title":"Impersonated class","visibility":"private"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, teacherCannotCreateForAnotherOwner.status)

                    val ownerClassUpdate = client.patch("/api/v1/classes/${ownerClass.id}") {
                        bearerAuth("owner-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Owner updated class","description":"","term":"","visibility":"public","isPublished":true,"isHidden":false,"sortOrder":0}""")
                    }
                    assertEquals(HttpStatusCode.OK, ownerClassUpdate.status)

                    val adminClassUpdate = client.patch("/api/v1/classes/${otherTeacherClass.id}") {
                        bearerAuth("admin-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Admin updated other teacher class","description":"","term":"","visibility":"private","isPublished":true,"isHidden":false,"sortOrder":0}""")
                    }
                    assertEquals(HttpStatusCode.OK, adminClassUpdate.status)

                    val adminClassDelete = client.delete("/api/v1/classes/${otherTeacherClass.id}") {
                        bearerAuth("admin-token")
                    }
                    assertEquals(HttpStatusCode.NoContent, adminClassDelete.status)

                    val adminClassRestore = client.post("/api/v1/classes/${otherTeacherClass.id}/restore") {
                        bearerAuth("admin-token")
                    }
                    assertEquals(HttpStatusCode.NoContent, adminClassRestore.status)



                }

                suspend fun verifyFolderAuthorization() {
                    // Folder HTTP routes: owners manage their folders; other teachers cannot.
                    val foreignFolderUpdate = client.patch("/api/v1/teacher-folders/${ownerFolder.id}") {
                        bearerAuth("other-teacher-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"تغییر غیرمجاز","sortOrder":1,"isHidden":false}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignFolderUpdate.status)

                    val foreignFolderDelete = client.delete("/api/v1/teacher-folders/${ownerFolder.id}") {
                        bearerAuth("other-teacher-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignFolderDelete.status)

                    val ownerFolderUpdate = client.patch("/api/v1/teacher-folders/${ownerFolder.id}") {
                        bearerAuth("owner-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"پوشه اصلاح‌شده","sortOrder":1,"isHidden":false}""")
                    }
                    assertEquals(HttpStatusCode.OK, ownerFolderUpdate.status)

                    val adminFolderUpdate = client.patch("/api/v1/teacher-folders/${foreignFolder.id}") {
                        bearerAuth("admin-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"پوشه اصلاح‌شده توسط مدیر","sortOrder":2,"isHidden":false}""")
                    }
                    assertEquals(HttpStatusCode.OK, adminFolderUpdate.status)

                    val adminFolderDelete = client.delete("/api/v1/teacher-folders/${foreignFolder.id}") {
                        bearerAuth("admin-token")
                    }
                    assertEquals(HttpStatusCode.NoContent, adminFolderDelete.status)

                    val guestFolderCreate = client.post("/api/v1/teacher-folders") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"پوشه مهمان"}""")
                    }
                    assertEquals(HttpStatusCode.Unauthorized, guestFolderCreate.status)

                    val teacherFolderImpersonation = client.post("/api/v1/teacher-folders") {
                        bearerAuth("other-teacher-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"ownerUserId":"${owner}","name":"پوشه جعل مالکیت"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, teacherFolderImpersonation.status)


                }

                suspend fun verifyCourseAuthorization() {
                    // Course HTTP routes follow the class owner boundary; admin is cross-owner.
                    val foreignCourseUpdate = client.patch("/api/v1/courses/${ownerCourse.id}") {
                        bearerAuth("other-teacher-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"دوره غیرمجاز"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignCourseUpdate.status)

                    val foreignCourseDelete = client.delete("/api/v1/courses/${ownerCourse.id}") {
                        bearerAuth("other-teacher-token")
                    }
                    assertEquals(HttpStatusCode.Forbidden, foreignCourseDelete.status)

                    val ownerCourseUpdate = client.patch("/api/v1/courses/${ownerCourse.id}") {
                        bearerAuth("owner-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"دوره اصلاح‌شده مالک"}""")
                    }
                    assertEquals(HttpStatusCode.OK, ownerCourseUpdate.status)

                    val adminCourseUpdate = client.patch("/api/v1/courses/${foreignCourse.id}") {
                        bearerAuth("admin-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"دوره اصلاح‌شده مدیر"}""")
                    }
                    assertEquals(HttpStatusCode.OK, adminCourseUpdate.status)

                    // Admin can delete an empty course owned by another teacher.
                    val adminCourseDelete = client.delete("/api/v1/courses/${foreignCourse.id}") {
                        bearerAuth("admin-token")
                    }
                    assertEquals(HttpStatusCode.NoContent, adminCourseDelete.status)

                    val studentCourseCreate = client.post("/api/v1/classes/${ownerClass.id}/courses") {
                        bearerAuth("student-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"دوره دانشجو"}""")
                    }
                    assertEquals(HttpStatusCode.Forbidden, studentCourseCreate.status)

                    val guestCourseCreate = client.post("/api/v1/classes/${ownerClass.id}/courses") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"دوره مهمان"}""")
                    }
                    assertEquals(HttpStatusCode.Unauthorized, guestCourseCreate.status)

                    // Folder creation must preserve owner scoping; only admins may target another owner.
                    val ownerFolderCreate = client.post("/api/v1/teacher-folders") {
                        bearerAuth("owner-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"پوشه جدید مالک","sortOrder":3}""")
                    }
                    assertEquals(HttpStatusCode.Created, ownerFolderCreate.status)

                    val adminFolderCreate = client.post("/api/v1/teacher-folders") {
                        bearerAuth("admin-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"ownerUserId":"${otherTeacher}","name":"پوشه ساخته‌شده مدیر"}""")
                    }
                    assertEquals(HttpStatusCode.Created, adminFolderCreate.status)

                    val adminFolderCreateWithoutOwner = client.post("/api/v1/teacher-folders") {
                        bearerAuth("admin-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"پوشه بدون مالک"}""")
                    }
                    assertEquals(HttpStatusCode.BadRequest, adminFolderCreateWithoutOwner.status)

                    // Both an owner and an admin can create courses within the authorized class.
                    val ownerCourseCreate = client.post("/api/v1/classes/${ownerClass.id}/courses") {
                        bearerAuth("owner-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"دوره تازه مالک"}""")
                    }
                    assertEquals(HttpStatusCode.Created, ownerCourseCreate.status)

                    val adminCourseCreate = client.post("/api/v1/classes/${otherTeacherClass.id}/courses") {
                        bearerAuth("admin-token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"دوره تازه مدیر"}""")
                    }
                    assertEquals(HttpStatusCode.Created, adminCourseCreate.status)

                    val guestClassCreate = client.post("/api/v1/classes") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"Guest class","visibility":"public"}""")
                    }
                    assertEquals(HttpStatusCode.Unauthorized, guestClassCreate.status)

                }

                // Verify the original public/private visibility before the admin move
                // changes foreignSession's parent class later in the mutation scenarios.
                verifyAudioSourceAuthorization()
                verifyAnnouncementAuthorization()
                verifyPublicReadVisibility()
                verifySessionMutationPermissions()
                verifyMembershipAuthorization()
                verifyClassAuthorization()
                verifyFolderAuthorization()
                verifyCourseAuthorization()
            }
        }
    }
}
