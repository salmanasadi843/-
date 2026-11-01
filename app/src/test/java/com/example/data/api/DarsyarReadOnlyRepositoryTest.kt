package com.example.data.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Response

class DarsyarReadOnlyRepositoryTest {
    @Test
    fun snapshotUsesBearerTokenAndReturnsAccountAndClasses() = runBlocking {
        val fake = FakeApi()
        val result = DarsyarReadOnlyRepository(fake).loadSnapshot("firebase-token")

        assertEquals("Bearer firebase-token", fake.authorizationForMe)
        assertEquals("Bearer firebase-token", fake.authorizationForClasses)
        assertEquals("internal-user-1", result.account.userId)
        assertEquals(1, result.classes.size)
        assertEquals("Public class", result.classes.single().title)
    }

    @Test
    fun coursesAndSessionsUseBearerTokenAndSelectedClassId() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)

        assertEquals(emptyList<BackendCourseDto>(), repository.loadCourses("fresh-token", "class-42"))
        assertEquals(emptyList<BackendSessionDto>(), repository.loadSessions("fresh-token", "class-42"))

        assertEquals("Bearer fresh-token", fake.authorizationForCourses)
        assertEquals("Bearer fresh-token", fake.authorizationForSessions)
        assertEquals("class-42", fake.requestedClassIdForCourses)
        assertEquals("class-42", fake.requestedClassIdForSessions)
    }

    @Test
    fun blankFirebaseTokenIsRejectedBeforeAnyRequest() = runBlocking {
        val fake = FakeApi()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DarsyarReadOnlyRepository(fake).loadSnapshot("  ") }
        }
        assertEquals(null, fake.authorizationForMe)
        assertEquals(null, fake.authorizationForClasses)
        assertEquals(null, fake.authorizationForCourses)
        assertEquals(null, fake.authorizationForSessions)
    }

    private class FakeApi : DarsyarReadOnlyApi {
        var authorizationForMe: String? = null
        var authorizationForClasses: String? = null
        var authorizationForCourses: String? = null
        var authorizationForSessions: String? = null
        var requestedClassIdForCourses: String? = null
        var requestedClassIdForSessions: String? = null

        override suspend fun getMe(authorization: String): Response<BackendMeDto> {
            authorizationForMe = authorization
            return Response.success(BackendMeDto("internal-user-1", listOf("student")))
        }

        override suspend fun getCourses(classId: String, authorization: String): Response<List<BackendCourseDto>> {
            authorizationForCourses = authorization
            requestedClassIdForCourses = classId
            return Response.success(emptyList())
        }

        override suspend fun getSessions(classId: String, authorization: String): Response<List<BackendSessionDto>> {
            authorizationForSessions = authorization
            requestedClassIdForSessions = classId
            return Response.success(emptyList())
        }

        override suspend fun getClasses(authorization: String): Response<List<BackendClassDto>> {
            authorizationForClasses = authorization
            return Response.success(
                listOf(
                    BackendClassDto(
                        id = "class-1",
                        ownerUserId = "teacher-1",
                        title = "Public class",
                        description = "",
                        term = "1405",
                        visibility = "public",
                        isPublished = true,
                        isHidden = false,
                        sortOrder = 0,
                        folderId = null
                    )
                )
            )
        }
    }
}
