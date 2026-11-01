package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header

/**
 * Read-only staging API contract. No write endpoint is exposed by this client.
 * Authorization is supplied per call so Firebase token refresh remains owned by FirebaseAuth.
 */
interface DarsyarReadOnlyApi {
    @GET("api/v1/me")
    suspend fun getMe(
        @Header("Authorization") authorization: String
    ): Response<BackendMeDto>

    @GET("api/v1/classes")
    suspend fun getClasses(
        @Header("Authorization") authorization: String
    ): Response<List<BackendClassDto>>

    @GET("api/v1/teachers")
    suspend fun getTeachers(
        @Header("Authorization") authorization: String
    ): Response<List<BackendTeacherDto>>

    @GET("api/v1/classes/{classId}/courses")
    suspend fun getCourses(
        @retrofit2.http.Path("classId") classId: String,
        @Header("Authorization") authorization: String
    ): Response<List<BackendCourseDto>>

    @GET("api/v1/classes/{classId}/sessions")
    suspend fun getSessions(
        @retrofit2.http.Path("classId") classId: String,
        @Header("Authorization") authorization: String
    ): Response<List<BackendSessionSummaryDto>>

    @GET("api/v1/classes/{classId}/sessions/{sessionId}")
    suspend fun getSessionDetail(
        @retrofit2.http.Path("classId") classId: String,
        @retrofit2.http.Path("sessionId") sessionId: String,
        @Header("Authorization") authorization: String
    ): Response<BackendSessionDto>
}

@JsonClass(generateAdapter = true)
data class BackendMeDto(
    val userId: String,
    val roles: List<String>
)

@JsonClass(generateAdapter = true)
data class BackendTeacherDto(
    val userId: String,
    val displayName: String
)

@JsonClass(generateAdapter = true)
data class BackendClassDto(
    val id: String,
    val ownerUserId: String,
    val title: String,
    val description: String,
    val term: String,
    val visibility: String,
    val isPublished: Boolean,
    val isHidden: Boolean,
    val sortOrder: Int,
    val folderId: String?
)

@JsonClass(generateAdapter = true)
data class BackendCourseDto(
    val id: String,
    val classId: String,
    val title: String,
    val teacherName: String,
    val description: String,
    val createdAtMs: Long?,
    val sortOrder: Int,
    val version: Long
)

@JsonClass(generateAdapter = true)
data class BackendSessionSummaryDto(
    val id: String,
    val classId: String,
    val courseId: String?,
    val title: String,
    val courseName: String,
    val professorName: String,
    val dateMs: Long?,
    val audioDurationMs: Long,
    val tags: String,
    val isPublished: Boolean,
    val lastEditedMs: Long?,
    val sortOrder: Int,
    val version: Long
)

@JsonClass(generateAdapter = true)
data class BackendSessionDto(
    val id: String,
    val classId: String,
    val courseId: String?,
    val title: String,
    val courseName: String,
    val professorName: String,
    val dateMs: Long?,
    val audioDurationMs: Long,
    val transcript: String,
    val originalTranscript: String,
    val aiSummary: String?,
    val aiKeyPoints: String?,
    val aiQuizJson: String?,
    val tags: String,
    val isPublished: Boolean,
    val lastEditedMs: Long?,
    val sortOrder: Int,
    val version: Long
)

data class BackendReadOnlySnapshot(
    val account: BackendMeDto,
    val classes: List<BackendClassDto>
)

class BackendHttpException(
    val statusCode: Int,
    endpoint: String
) : IllegalStateException("Backend request failed: $endpoint (HTTP $statusCode)")

class DarsyarReadOnlyRepository(
    private val api: DarsyarReadOnlyApi
) {
    /**
     * Reads the authenticated account and visible classes only.
     * It deliberately does not write to Room, Firestore, or the backend.
     */
    suspend fun loadCourses(firebaseIdToken: String, classId: String): List<BackendCourseDto> {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(classId.isNotBlank()) { "Class ID is required." }
        val response = api.getCourses(classId, "Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/classes/{classId}/courses")
        return response.body() ?: throw IllegalStateException("Backend returned an empty courses response.")
    }

    suspend fun loadSessions(firebaseIdToken: String, classId: String): List<BackendSessionSummaryDto> {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(classId.isNotBlank()) { "Class ID is required." }
        val response = api.getSessions(classId, "Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/classes/{classId}/sessions")
        return response.body() ?: throw IllegalStateException("Backend returned an empty sessions response.")
    }

    suspend fun loadTeachers(firebaseIdToken: String): List<BackendTeacherDto> {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        val response = api.getTeachers("Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/teachers")
        return response.body() ?: throw IllegalStateException("Backend returned an empty teachers response.")
    }

    /** Loads full transcript/summary data only for the session the user opened. */
    suspend fun loadSessionDetail(
        firebaseIdToken: String,
        classId: String,
        sessionId: String
    ): BackendSessionDto {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(classId.isNotBlank()) { "Class ID is required." }
        require(sessionId.isNotBlank()) { "Session ID is required." }
        val response = api.getSessionDetail(classId, sessionId, "Bearer $firebaseIdToken")
        if (!response.isSuccessful) {
            throw BackendHttpException(response.code(), "/api/v1/classes/{classId}/sessions/{sessionId}")
        }
        return response.body() ?: throw IllegalStateException("Backend returned an empty session response.")
    }

    suspend fun loadSnapshot(firebaseIdToken: String): BackendReadOnlySnapshot {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        val authorization = "Bearer $firebaseIdToken"

        val meResponse = api.getMe(authorization)
        if (!meResponse.isSuccessful) {
            throw BackendHttpException(meResponse.code(), "/api/v1/me")
        }
        val account = meResponse.body()
            ?: throw IllegalStateException("Backend returned an empty account response.")

        val classesResponse = api.getClasses(authorization)
        if (!classesResponse.isSuccessful) {
            throw BackendHttpException(classesResponse.code(), "/api/v1/classes")
        }
        val classes = classesResponse.body()
            ?: throw IllegalStateException("Backend returned an empty classes response.")

        return BackendReadOnlySnapshot(account, classes)
    }
}

object DarsyarReadOnlyApiFactory {
    fun create(baseUrl: String = BackendEndpointConfig.requireBaseUrl()): DarsyarReadOnlyRepository {
        val normalizedUrl = normalizeBackendBaseUrl(baseUrl)
            ?: throw IllegalArgumentException("Backend base URL is not configured.")

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        return DarsyarReadOnlyRepository(retrofit.create(DarsyarReadOnlyApi::class.java))
    }
}
