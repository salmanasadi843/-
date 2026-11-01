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
    fun teacherDirectoryUsesBearerTokenAndReturnsOnlyApiDisplayNames() = runBlocking {
        val fake = FakeApi()
        val teachers = DarsyarReadOnlyRepository(fake).loadTeachers("fresh-token")
        assertEquals("Bearer fresh-token", fake.authorizationForTeachers)
        assertEquals("استاد الف", teachers.single().displayName)
        assertEquals("teacher-1", teachers.single().userId)
    }

    @Test
    fun blankTokenForTeacherDirectoryIsRejectedBeforeRequest() = runBlocking {
        val fake = FakeApi()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DarsyarReadOnlyRepository(fake).loadTeachers("  ") }
        }
        assertEquals(null, fake.authorizationForTeachers)
    }

    @Test
    fun coursesAndSessionsUseBearerTokenAndSelectedClassId() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)

        assertEquals(emptyList<BackendCourseDto>(), repository.loadCourses("fresh-token", "class-42"))
        assertEquals(emptyList<BackendSessionSummaryDto>(), repository.loadSessions("fresh-token", "class-42"))

        assertEquals("Bearer fresh-token", fake.authorizationForCourses)
        assertEquals("Bearer fresh-token", fake.authorizationForSessions)
        assertEquals("class-42", fake.requestedClassIdForCourses)
        assertEquals("class-42", fake.requestedClassIdForSessions)

        val detail = repository.loadSessionDetail("fresh-token", "class-42", "session-7")
        assertEquals("full transcript", detail.transcript)
        assertEquals("Bearer fresh-token", fake.authorizationForSessionDetail)
        assertEquals("class-42", fake.requestedClassIdForSessionDetail)
        assertEquals("session-7", fake.requestedSessionIdForSessionDetail)
    }

    @Test
    fun blankSessionDetailArgumentsAreRejectedBeforeRequest() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.loadSessionDetail("token", "class-42", " ") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.loadSessionDetail(" ", "class-42", "session-7") }
        }
        assertEquals(null, fake.authorizationForSessionDetail)
    }

    @Test
    fun blankFirebaseTokenIsRejectedBeforeAnyRequest() = runBlocking {
        val fake = FakeApi()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DarsyarReadOnlyRepository(fake).loadSnapshot("  ") }
        }
        assertEquals(null, fake.authorizationForMe)
        assertEquals(null, fake.authorizationForClasses)
        assertEquals(null, fake.authorizationForTeachers)
        assertEquals(null, fake.authorizationForCourses)
        assertEquals(null, fake.authorizationForSessions)
        assertEquals(null, fake.authorizationForSessionDetail)
    }

    private class FakeApi : DarsyarReadOnlyApi {
        var authorizationForMe: String? = null
        var authorizationForClasses: String? = null
        var authorizationForTeachers: String? = null
        var authorizationForCourses: String? = null
        var authorizationForSessions: String? = null
        var requestedClassIdForCourses: String? = null
        var requestedClassIdForSessions: String? = null
        var authorizationForSessionDetail: String? = null
        var requestedClassIdForSessionDetail: String? = null
        var requestedSessionIdForSessionDetail: String? = null

        override suspend fun getMe(authorization: String): Response<BackendMeDto> {
            authorizationForMe = authorization
            return Response.success(BackendMeDto("internal-user-1", listOf("student")))
        }

        override suspend fun getTeachers(authorization: String): Response<List<BackendTeacherDto>> {
            authorizationForTeachers = authorization
            return Response.success(listOf(BackendTeacherDto("teacher-1", "استاد الف")))
        }

        override suspend fun getCourses(classId: String, authorization: String): Response<List<BackendCourseDto>> {
            authorizationForCourses = authorization
            requestedClassIdForCourses = classId
            return Response.success(emptyList())
        }

        override suspend fun getSessions(classId: String, authorization: String): Response<List<BackendSessionSummaryDto>> {
            authorizationForSessions = authorization
            requestedClassIdForSessions = classId
            return Response.success(emptyList())
        }

        override suspend fun getSessionDetail(
            classId: String,
            sessionId: String,
            authorization: String
        ): Response<BackendSessionDto> {
            authorizationForSessionDetail = authorization
            requestedClassIdForSessionDetail = classId
            requestedSessionIdForSessionDetail = sessionId
            return Response.success(
                BackendSessionDto(
                    id = sessionId, classId = classId, courseId = null, title = "Selected session",
                    courseName = "", professorName = "", dateMs = null, audioDurationMs = 0,
                    transcript = "full transcript", originalTranscript = "original",
                    aiSummary = null, aiKeyPoints = null, aiQuizJson = null, tags = "",
                    isPublished = true, lastEditedMs = null, sortOrder = 0, version = 1
                )
            )
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
