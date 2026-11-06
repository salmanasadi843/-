package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header

/**
 * Authenticated Darsyar API contract. Authorization is supplied per call so Firebase token
 * refresh remains owned by FirebaseAuth; backend authorization remains authoritative.
 */
interface DarsyarReadOnlyApi {
    @GET("api/v1/public/classes")
    suspend fun getPublicClasses(): Response<List<BackendClassDto>>

    @GET("api/v1/public/classes/{classId}/sessions")
    suspend fun getPublicSessions(
        @retrofit2.http.Path("classId") classId: String
    ): Response<List<BackendSessionSummaryDto>>

    @GET("api/v1/public/classes/{classId}/sessions/{sessionId}")
    suspend fun getPublicSessionDetail(
        @retrofit2.http.Path("classId") classId: String,
        @retrofit2.http.Path("sessionId") sessionId: String
    ): Response<BackendSessionDto>

    @GET("api/v1/me")
    suspend fun getMe(
        @Header("Authorization") authorization: String
    ): Response<BackendMeDto>

    @POST("api/v1/classes")
    suspend fun createClass(
        @Header("Authorization") authorization: String,
        @Body request: BackendClassWriteRequest
    ): Response<BackendClassDto>

    @GET("api/v1/classes")
    suspend fun getClasses(
        @Header("Authorization") authorization: String
    ): Response<List<BackendClassDto>>

    @GET("api/v1/classes/deleted")
    suspend fun getDeletedClasses(
        @Header("Authorization") authorization: String
    ): Response<List<BackendClassDto>>

    @DELETE("api/v1/classes/{classId}")
    suspend fun deleteClass(
        @retrofit2.http.Path("classId") classId: String,
        @Header("Authorization") authorization: String
    ): Response<Unit>

    @POST("api/v1/classes/{classId}/restore")
    suspend fun restoreClass(
        @retrofit2.http.Path("classId") classId: String,
        @Header("Authorization") authorization: String
    ): Response<Unit>

    @POST("api/v1/classes/{classId}/sessions")
    suspend fun createSession(
        @retrofit2.http.Path("classId") classId: String,
        @Header("Authorization") authorization: String,
        @Body request: BackendSessionWriteRequest
    ): Response<BackendSessionDto>

    @PATCH("api/v1/classes/{classId}")
    suspend fun updateClass(
        @retrofit2.http.Path("classId") classId: String,
        @Header("Authorization") authorization: String,
        @Body request: BackendClassWriteRequest
    ): Response<BackendClassDto>

    @PATCH("api/v1/sessions/{sessionId}")
    suspend fun updateSession(
        @retrofit2.http.Path("sessionId") sessionId: String,
        @Header("Authorization") authorization: String,
        @Body request: BackendSessionWriteRequest
    ): Response<BackendSessionDto>

    @GET("api/v1/sessions/deleted")
    suspend fun getDeletedSessions(
        @Header("Authorization") authorization: String
    ): Response<List<BackendSessionSummaryDto>>

    @DELETE("api/v1/sessions/{sessionId}")
    suspend fun deleteSession(
        @retrofit2.http.Path("sessionId") sessionId: String,
        @Header("Authorization") authorization: String
    ): Response<Unit>

    @POST("api/v1/sessions/{sessionId}/restore")
    suspend fun restoreSession(
        @retrofit2.http.Path("sessionId") sessionId: String,
        @Header("Authorization") authorization: String
    ): Response<Unit>

    @POST("api/v1/sessions/{sessionId}/reorder")
    suspend fun reorderSession(
        @retrofit2.http.Path("sessionId") sessionId: String,
        @Header("Authorization") authorization: String,
        @Body request: BackendReorderSessionRequest
    ): Response<BackendSessionDto>

    @POST("api/v1/sessions/{sessionId}/move")
    suspend fun moveSession(
        @retrofit2.http.Path("sessionId") sessionId: String,
        @Header("Authorization") authorization: String,
        @Body request: BackendMoveSessionRequest
    ): Response<BackendSessionDto>

    @POST("api/v1/teacher-applications")
    suspend fun submitTeacherApplication(
        @Header("Authorization") authorization: String,
        @Body request: BackendTeacherApplicationWriteRequest
    ): Response<BackendTeacherApplicationDto>

    @GET("api/v1/admin/teacher-applications")
    suspend fun getPendingTeacherApplications(
        @Header("Authorization") authorization: String
    ): Response<List<BackendTeacherApplicationDto>>

    @PATCH("api/v1/admin/teacher-applications/{userId}")
    suspend fun decideTeacherApplication(
        @retrofit2.http.Path("userId") userId: String,
        @Header("Authorization") authorization: String,
        @Body request: BackendTeacherApplicationDecisionRequest
    ): Response<BackendTeacherApplicationDto>

    @GET("api/v1/teachers")
    suspend fun getTeachers(
        @Header("Authorization") authorization: String
    ): Response<List<BackendTeacherDto>>

    @GET("api/v1/teacher-folders")
    suspend fun getTeacherFolders(
        @Header("Authorization") authorization: String
    ): Response<List<BackendTeacherFolderDto>>

    @POST("api/v1/teacher-folders")
    suspend fun createTeacherFolder(
        @Header("Authorization") authorization: String,
        @Body request: BackendTeacherFolderWriteRequest
    ): Response<BackendTeacherFolderDto>

    @PATCH("api/v1/teacher-folders/{folderId}")
    suspend fun updateTeacherFolder(
        @retrofit2.http.Path("folderId") folderId: String,
        @Header("Authorization") authorization: String,
        @Body request: BackendTeacherFolderWriteRequest
    ): Response<BackendTeacherFolderDto>

    @DELETE("api/v1/teacher-folders/{folderId}")
    suspend fun deleteTeacherFolder(
        @retrofit2.http.Path("folderId") folderId: String,
        @Header("Authorization") authorization: String
    ): Response<Unit>

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
data class BackendTeacherApplicationDto(
    val userId: String,
    val displayName: String,
    val email: String?,
    val teachingField: String?,
    val institutionName: String?,
    val approvalStatus: String
)

@JsonClass(generateAdapter = true)
data class BackendTeacherApplicationWriteRequest(
    val teachingField: String? = null,
    val institutionName: String? = null
)

@JsonClass(generateAdapter = true)
data class BackendTeacherApplicationDecisionRequest(
    val decision: String
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
data class BackendClassWriteRequest(
    val title: String,
    val ownerUserId: String? = null,
    val description: String = "",
    val term: String = "",
    val visibility: String = "private",
    val isPublished: Boolean = false,
    val isHidden: Boolean = false,
    val sortOrder: Int = 0,
    val folderId: String? = null
)

@JsonClass(generateAdapter = true)
data class BackendTeacherFolderDto(
    val id: String,
    val ownerUserId: String,
    val parentFolderId: String?,
    val name: String,
    val sortOrder: Int,
    val isHidden: Boolean,
    val version: Long
)

@JsonClass(generateAdapter = true)
data class BackendTeacherFolderWriteRequest(
    val ownerUserId: String? = null,
    val parentFolderId: String? = null,
    val name: String,
    val sortOrder: Int = 0,
    val isHidden: Boolean = false
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
data class BackendSessionWriteRequest(
    val courseId: String? = null,
    val title: String,
    val courseName: String = "",
    val professorName: String = "",
    val dateMs: Long? = null,
    val audioDurationMs: Long = 0,
    val transcript: String = "",
    val originalTranscript: String = "",
    val aiSummary: String? = null,
    val aiKeyPoints: String? = null,
    val aiQuizJson: String? = null,
    val tags: String = "",
    val isPublished: Boolean = true,
    val lastEditedMs: Long? = null,
    val sortOrder: Int = 0
)

@JsonClass(generateAdapter = true)
data class BackendReorderSessionRequest(val direction: String)

@JsonClass(generateAdapter = true)
data class BackendMoveSessionRequest(val targetClassId: String)

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

    suspend fun loadDeletedClasses(firebaseIdToken: String): List<BackendClassDto> {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        val response = api.getDeletedClasses("Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/classes/deleted")
        return response.body() ?: throw IllegalStateException("Backend returned an empty deleted classes response.")
    }

    suspend fun deleteClass(firebaseIdToken: String, classId: String) {
        require(firebaseIdToken.isNotBlank() && classId.isNotBlank()) { "Token and class ID are required." }
        val response = api.deleteClass(classId, "Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/classes/{classId}")
    }

    suspend fun restoreClass(firebaseIdToken: String, classId: String) {
        require(firebaseIdToken.isNotBlank() && classId.isNotBlank()) { "Token and class ID are required." }
        val response = api.restoreClass(classId, "Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/classes/{classId}/restore")
    }

    /** Creates a class for the authenticated teacher; super-admin may specify its owner. */
    suspend fun createClass(
        firebaseIdToken: String,
        request: BackendClassWriteRequest
    ): BackendClassDto {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(request.title.isNotBlank()) { "Class title is required." }
        require(request.visibility in setOf("public", "private", "unlisted")) { "Unsupported class visibility." }
        val response = api.createClass("Bearer $firebaseIdToken", request)
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/classes")
        return response.body() ?: throw IllegalStateException("Backend returned an empty created class response.")
    }

    /** Creates a session in the selected class; server enforces ownership/admin access. */
    suspend fun createSession(
        firebaseIdToken: String,
        classId: String,
        request: BackendSessionWriteRequest
    ): BackendSessionDto {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(classId.isNotBlank()) { "Class ID is required." }
        require(request.title.isNotBlank()) { "Session title is required." }
        val response = api.createSession(classId, "Bearer $firebaseIdToken", request)
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/classes/{classId}/sessions")
        return response.body() ?: throw IllegalStateException("Backend returned an empty created session response.")
    }

    /** Class mutations are authorized and audited by the backend (owner or super-admin). */
    suspend fun updateClass(
        firebaseIdToken: String,
        classId: String,
        request: BackendClassWriteRequest
    ): BackendClassDto {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(classId.isNotBlank()) { "Class ID is required." }
        val response = api.updateClass(classId, "Bearer $firebaseIdToken", request)
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/classes/{classId}")
        return response.body() ?: throw IllegalStateException("Backend returned an empty class response.")
    }

    /** Updates session content; the backend enforces owner/super-admin permissions. */
    suspend fun updateSession(
        firebaseIdToken: String,
        sessionId: String,
        request: BackendSessionWriteRequest
    ): BackendSessionDto {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(sessionId.isNotBlank()) { "Session ID is required." }
        val response = api.updateSession(sessionId, "Bearer $firebaseIdToken", request)
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/sessions/{sessionId}")
        return response.body() ?: throw IllegalStateException("Backend returned an empty updated session response.")
    }

    suspend fun loadDeletedSessions(firebaseIdToken: String): List<BackendSessionSummaryDto> {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        val response = api.getDeletedSessions("Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/sessions/deleted")
        return response.body() ?: throw IllegalStateException("Backend returned an empty deleted sessions response.")
    }

    suspend fun deleteSession(firebaseIdToken: String, sessionId: String) {
        require(firebaseIdToken.isNotBlank() && sessionId.isNotBlank()) { "Token and session ID are required." }
        val response = api.deleteSession(sessionId, "Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/sessions/{sessionId}")
    }

    suspend fun restoreSession(firebaseIdToken: String, sessionId: String) {
        require(firebaseIdToken.isNotBlank() && sessionId.isNotBlank()) { "Token and session ID are required." }
        val response = api.restoreSession(sessionId, "Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/sessions/{sessionId}/restore")
    }

    /** Swaps a session with its adjacent sibling in the same class. */
    suspend fun reorderSession(firebaseIdToken: String, sessionId: String, direction: String): BackendSessionDto {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(sessionId.isNotBlank()) { "Session ID is required." }
        require(direction == "up" || direction == "down") { "Direction must be up or down." }
        val response = api.reorderSession(
            sessionId,
            "Bearer $firebaseIdToken",
            BackendReorderSessionRequest(direction)
        )
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/sessions/{sessionId}/reorder")
        return response.body() ?: throw IllegalStateException("Backend returned an empty reordered session response.")
    }

    /** Moves a session atomically; the backend enforces owner/super-admin permissions. */
    suspend fun moveSession(
        firebaseIdToken: String,
        sessionId: String,
        targetClassId: String
    ): BackendSessionDto {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(sessionId.isNotBlank() && targetClassId.isNotBlank()) { "Session and target class IDs are required." }
        val response = api.moveSession(
            sessionId,
            "Bearer $firebaseIdToken",
            BackendMoveSessionRequest(targetClassId)
        )
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/sessions/{sessionId}/move")
        return response.body() ?: throw IllegalStateException("Backend returned an empty moved session response.")
    }

    suspend fun loadTeacherFolders(firebaseIdToken: String): List<BackendTeacherFolderDto> {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        val response = api.getTeacherFolders("Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/teacher-folders")
        return response.body() ?: throw IllegalStateException("Backend returned an empty teacher folders response.")
    }

    suspend fun createTeacherFolder(
        firebaseIdToken: String,
        request: BackendTeacherFolderWriteRequest
    ): BackendTeacherFolderDto {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        val response = api.createTeacherFolder("Bearer $firebaseIdToken", request)
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/teacher-folders")
        return response.body() ?: throw IllegalStateException("Backend returned an empty teacher folder response.")
    }

    suspend fun updateTeacherFolder(
        firebaseIdToken: String,
        folderId: String,
        request: BackendTeacherFolderWriteRequest
    ): BackendTeacherFolderDto {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(folderId.isNotBlank()) { "Folder ID is required." }
        val response = api.updateTeacherFolder(folderId, "Bearer $firebaseIdToken", request)
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/teacher-folders/{folderId}")
        return response.body() ?: throw IllegalStateException("Backend returned an empty teacher folder response.")
    }

    suspend fun deleteTeacherFolder(firebaseIdToken: String, folderId: String) {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(folderId.isNotBlank()) { "Folder ID is required." }
        val response = api.deleteTeacherFolder(folderId, "Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/teacher-folders/{folderId}")
    }

    suspend fun submitTeacherApplication(
        firebaseIdToken: String,
        request: BackendTeacherApplicationWriteRequest
    ): BackendTeacherApplicationDto {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(!request.teachingField.isNullOrBlank() || !request.institutionName.isNullOrBlank()) {
            "At least one teacher application detail is required."
        }
        val response = api.submitTeacherApplication("Bearer $firebaseIdToken", request)
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/teacher-applications")
        return response.body() ?: throw IllegalStateException("Backend returned an empty teacher application response.")
    }

    suspend fun loadPendingTeacherApplications(firebaseIdToken: String): List<BackendTeacherApplicationDto> {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        val response = api.getPendingTeacherApplications("Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/admin/teacher-applications")
        return response.body() ?: throw IllegalStateException("Backend returned an empty teacher applications response.")
    }

    suspend fun decideTeacherApplication(
        firebaseIdToken: String,
        userId: String,
        decision: String
    ): BackendTeacherApplicationDto {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        require(userId.isNotBlank()) { "Teacher user ID is required." }
        require(decision in setOf("approved", "rejected", "revoked")) { "Unsupported teacher application decision." }
        val response = api.decideTeacherApplication(
            userId, "Bearer $firebaseIdToken", BackendTeacherApplicationDecisionRequest(decision)
        )
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/admin/teacher-applications/{userId}")
        return response.body() ?: throw IllegalStateException("Backend returned an empty teacher application decision response.")
    }

    suspend fun loadTeachers(firebaseIdToken: String): List<BackendTeacherDto> {
        require(firebaseIdToken.isNotBlank()) { "Firebase ID token is required." }
        val response = api.getTeachers("Bearer $firebaseIdToken")
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/teachers")
        return response.body() ?: throw IllegalStateException("Backend returned an empty teachers response.")
    }

    /** Public catalog endpoints deliberately require no Firebase token or membership. */
    suspend fun loadPublicClasses(): List<BackendClassDto> {
        val response = api.getPublicClasses()
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/public/classes")
        return response.body() ?: throw IllegalStateException("فهرست کلاس‌های عمومی خالی دریافت شد.")
    }

    suspend fun loadPublicSessions(classId: String): List<BackendSessionSummaryDto> {
        require(classId.isNotBlank()) { "شناسه کلاس لازم است." }
        val response = api.getPublicSessions(classId)
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/public/classes/{classId}/sessions")
        return response.body() ?: throw IllegalStateException("فهرست جلسات عمومی خالی دریافت شد.")
    }

    suspend fun loadPublicSessionDetail(classId: String, sessionId: String): BackendSessionDto {
        require(classId.isNotBlank() && sessionId.isNotBlank()) { "شناسه کلاس و جلسه لازم است." }
        val response = api.getPublicSessionDetail(classId, sessionId)
        if (!response.isSuccessful) throw BackendHttpException(response.code(), "/api/v1/public/classes/{classId}/sessions/{sessionId}")
        return response.body() ?: throw IllegalStateException("جزئیات جلسه عمومی خالی دریافت شد.")
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
