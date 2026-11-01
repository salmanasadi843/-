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
    fun blankFirebaseTokenIsRejectedBeforeAnyRequest() = runBlocking {
        val fake = FakeApi()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DarsyarReadOnlyRepository(fake).loadSnapshot("  ") }
        }
        assertEquals(null, fake.authorizationForMe)
        assertEquals(null, fake.authorizationForClasses)
    }

    private class FakeApi : DarsyarReadOnlyApi {
        var authorizationForMe: String? = null
        var authorizationForClasses: String? = null

        override suspend fun getMe(authorization: String): Response<BackendMeDto> {
            authorizationForMe = authorization
            return Response.success(BackendMeDto("internal-user-1", listOf("student")))
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
