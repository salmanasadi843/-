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
}

@JsonClass(generateAdapter = true)
data class BackendMeDto(
    val userId: String,
    val roles: List<String>
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
