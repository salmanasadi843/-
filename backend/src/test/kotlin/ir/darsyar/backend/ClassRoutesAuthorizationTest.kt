package ir.darsyar.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.patch
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.lang.reflect.Proxy
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ClassRoutesAuthorizationTest {
    private fun unusedDataSource(): DataSource =
        Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java)
        ) { _, method, _ ->
            throw UnsupportedOperationException("Database must not be accessed by this authorization test: ${method.name}")
        } as DataSource

    private fun verifier() = IdentityTokenVerifier { token ->
        check(token == "test-token")
        VerifiedIdentity(provider = "firebase", subject = "firebase-test-subject")
    }

    private fun usersWith(vararg roles: AppRole) = AuthenticatedUserRepository { provider, subject ->
        check(provider == "firebase")
        check(subject == "firebase-test-subject")
        AuthenticatedUserRecord(
            userId = "11111111-1111-4111-8111-111111111111",
            status = "active",
            roles = roles.toSet()
        )
    }

    @Test
    fun `class listing rejects unauthenticated requests`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT))
        }

        val response = client.get("/api/v1/classes")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `student cannot create a class`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT))
        }

        val response = client.post("/api/v1/classes") {
            header(HttpHeaders.Authorization, "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Forbidden class"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `teacher cannot create a class for another owner`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.TEACHER))
        }

        val response = client.post("/api/v1/classes") {
            header(HttpHeaders.Authorization, "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"ownerUserId":"22222222-2222-4222-8222-222222222222","title":"Forbidden class"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `super admin must explicitly identify the class owner`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.SUPER_ADMIN))
        }

        val response = client.post("/api/v1/classes") {
            header(HttpHeaders.Authorization, "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Owner is missing"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
    @Test
    fun `session listing rejects unauthenticated requests`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT))
        }

        val response = client.get("/api/v1/classes/11111111-1111-4111-8111-111111111111/sessions")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `course listing rejects unauthenticated requests`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT))
        }
        val response = client.get("/api/v1/classes/11111111-1111-4111-8111-111111111111/courses")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `student cannot create a course`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT))
        }
        val response = client.post("/api/v1/classes/11111111-1111-4111-8111-111111111111/courses") {
            header(HttpHeaders.Authorization, "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Forbidden course"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `access code creation rejects unauthenticated requests`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.TEACHER))
        }
        val response = client.post("/api/v1/classes/11111111-1111-4111-8111-111111111111/access-codes")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `access code listing rejects unauthenticated requests`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.TEACHER))
        }
        val response = client.get("/api/v1/classes/11111111-1111-4111-8111-111111111111/access-codes")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `teacher cannot redeem a student access code`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.TEACHER))
        }
        val response = client.post("/api/v1/class-memberships/redeem") {
            header(HttpHeaders.Authorization, "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"code":"ABCDEFGH23"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `membership listing rejects unauthenticated requests`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT))
        }
        val response = client.get("/api/v1/classes/11111111-1111-4111-8111-111111111111/memberships")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `membership revocation rejects unauthenticated requests`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT))
        }
        val response = client.delete("/api/v1/classes/11111111-1111-4111-8111-111111111111/memberships/22222222-2222-4222-8222-222222222222")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `audio source listing rejects unauthenticated requests`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT))
        }

        val response = client.get("/api/v1/sessions/11111111-1111-4111-8111-111111111111/audio-sources")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `student cannot create an audio source`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT))
        }

        val response = client.post("/api/v1/sessions/11111111-1111-4111-8111-111111111111/audio-sources") {
            header(HttpHeaders.Authorization, "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"sourceKind":"direct_url","sourceUrl":"https://media.example.test/audio.mp3"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }


    @Test
    fun `class deletion rejects unauthenticated requests`() = testApplication {
        application { module(unusedDataSource(), verifier(), usersWith(AppRole.TEACHER)) }
        val response = client.delete("/api/v1/classes/11111111-1111-4111-8111-111111111111")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `class recovery rejects unauthenticated requests`() = testApplication {
        application { module(unusedDataSource(), verifier(), usersWith(AppRole.TEACHER)) }
        val response = client.post("/api/v1/classes/11111111-1111-4111-8111-111111111111/restore")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `deleted class listing rejects unauthenticated requests`() = testApplication {
        application { module(unusedDataSource(), verifier(), usersWith(AppRole.TEACHER)) }
        val response = client.get("/api/v1/classes/deleted")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `student cannot create a session`() = testApplication {
        application {
            module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT))
        }

        val response = client.post("/api/v1/classes/11111111-1111-4111-8111-111111111111/sessions") {
            header(HttpHeaders.Authorization, "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"title":"Forbidden session"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student cannot inspect teacher applications`() = testApplication {
        application { module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT)) }
        val response = client.get("/api/v1/admin/teacher-applications") {
            header(HttpHeaders.Authorization, "Bearer test-token")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `teacher cannot decide teacher applications`() = testApplication {
        application { module(unusedDataSource(), verifier(), usersWith(AppRole.TEACHER)) }
        val response = client.patch("/api/v1/admin/teacher-applications/22222222-2222-4222-8222-222222222222") {
            header(HttpHeaders.Authorization, "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"approved"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `teacher application submission requires authentication`() = testApplication {
        application { module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT)) }
        val response = client.post("/api/v1/teacher-applications") {
            contentType(ContentType.Application.Json)
            setBody("""{"teachingField":"فقه"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }


    @Test
    fun `personal notes saved state forum and notifications require authentication`() = testApplication {
        application { module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT)) }

        val classId = "11111111-1111-4111-8111-111111111111"
        val sessionId = "22222222-2222-4222-8222-222222222222"

        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/api/v1/classes/$classId/sessions/$sessionId/notes").status)
        assertEquals(HttpStatusCode.Unauthorized,
            client.post("/api/v1/classes/$classId/sessions/$sessionId/notes") {
                contentType(ContentType.Application.Json)
                setBody("""{"quotedText":"متن","noteText":"یادداشت"}""")
            }.status)
        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/api/v1/classes/$classId/sessions/$sessionId/state").status)
        assertEquals(HttpStatusCode.Unauthorized,
            client.put("/api/v1/classes/$classId/sessions/$sessionId/state") {
                contentType(ContentType.Application.Json)
                setBody("""{"isFavorite":true,"lastPlaybackPositionMs":0}""")
            }.status)
        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/api/v1/classes/$classId/forum/topics").status)
        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/api/v1/notifications?unreadOnly=false").status)
        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/api/v1/me/notes").status)
    }


    @Test
    fun aiAnswerEndpointRejectsUnauthenticatedRequests() = testApplication {
        application { module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT)) }
        val response = client.post(
            "/api/v1/classes/11111111-1111-4111-8111-111111111111/sessions/22222222-2222-4222-8222-222222222222/ai/answer"
        ) {
            contentType(ContentType.Application.Json)
            setBody("""{"question":"این جلسه چه می‌گوید؟"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun aiAnswerEndpointValidatesSessionIdentifiersBeforeDatabaseAccess() = testApplication {
        application { module(unusedDataSource(), verifier(), usersWith(AppRole.STUDENT)) }
        val response = client.post("/api/v1/classes/not-a-uuid/sessions/not-a-uuid/ai/answer") {
            header(HttpHeaders.Authorization, "Bearer test-token")
            contentType(ContentType.Application.Json)
            setBody("""{"question":"این جلسه چه می‌گوید؟"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
