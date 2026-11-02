package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*

interface DarsyarCommunityApi {
    @GET("api/v1/classes/{classId}/forum/topics")
    suspend fun topics(@Path("classId") classId:String,@Header("Authorization") auth:String):Response<List<BackendForumTopicDto>>
    @POST("api/v1/classes/{classId}/forum/topics")
    suspend fun createTopic(@Path("classId") classId:String,@Body body:ForumTopicWriteDto,@Header("Authorization") auth:String):Response<BackendForumTopicDto>
    @GET("api/v1/forum/topics/{topicId}/posts")
    suspend fun posts(@Path("topicId") topicId:String,@Header("Authorization") auth:String):Response<List<BackendForumPostDto>>
    @POST("api/v1/forum/topics/{topicId}/posts")
    suspend fun reply(@Path("topicId") topicId:String,@Body body:ForumReplyWriteDto,@Header("Authorization") auth:String):Response<BackendForumPostDto>
    @PATCH("api/v1/forum/topics/{topicId}/moderation")
    suspend fun moderate(@Path("topicId") topicId:String,@Body body:ForumModerationDto,@Header("Authorization") auth:String):Response<Unit>
    @GET("api/v1/notifications")
    suspend fun notifications(@Query("unreadOnly") unreadOnly:Boolean,@Header("Authorization") auth:String):Response<List<BackendNotificationDto>>
    @POST("api/v1/notifications/{notificationId}/read")
    suspend fun markRead(@Path("notificationId") id:String,@Header("Authorization") auth:String):Response<Unit>
}

@JsonClass(generateAdapter=true)
data class BackendForumTopicDto(
    val id:String,val classId:String,val sessionId:String?,val authorUserId:String,val authorName:String,
    val title:String,val body:String,val isPinned:Boolean,val isLocked:Boolean,val replyCount:Int,
    val createdAtMs:Long,val updatedAtMs:Long
)
@JsonClass(generateAdapter=true)
data class BackendForumPostDto(
    val id:String,val topicId:String,val parentPostId:String?,val authorUserId:String,val authorName:String,
    val body:String,val createdAtMs:Long,val updatedAtMs:Long
)
@JsonClass(generateAdapter=true)
data class BackendNotificationDto(
    val id:String,val notificationType:String,val message:String,val classId:String?,val topicId:String?,
    val postId:String?,val actorUserId:String?,val isRead:Boolean,val createdAtMs:Long
)
@JsonClass(generateAdapter=true)
data class ForumTopicWriteDto(val sessionId:String?=null,val title:String,val body:String)
@JsonClass(generateAdapter=true)
data class ForumReplyWriteDto(val parentPostId:String?=null,val body:String)
@JsonClass(generateAdapter=true)
data class ForumModerationDto(val isPinned:Boolean,val isLocked:Boolean)

class DarsyarCommunityRepository(private val api:DarsyarCommunityApi) {
    private fun <T> checked(response:Response<T>,endpoint:String):T =
        if(response.isSuccessful) response.body() ?: throw IllegalStateException("پاسخ خالی از سرور")
        else throw BackendHttpException(response.code(),endpoint)

    suspend fun topics(token:String,classId:String)=checked(api.topics(classId,"Bearer $token"),"forum/topics")
    suspend fun createTopic(token:String,classId:String,sessionId:String?,title:String,body:String)=
        checked(api.createTopic(classId,ForumTopicWriteDto(sessionId,title,body),"Bearer $token"),"forum/topics")
    suspend fun posts(token:String,topicId:String)=checked(api.posts(topicId,"Bearer $token"),"forum/posts")
    suspend fun reply(token:String,topicId:String,parentId:String?,body:String)=
        checked(api.reply(topicId,ForumReplyWriteDto(parentId,body),"Bearer $token"),"forum/reply")
    suspend fun moderate(token:String,topicId:String,pinned:Boolean,locked:Boolean) {
        val response=api.moderate(topicId,ForumModerationDto(pinned,locked),"Bearer $token")
        if(!response.isSuccessful) throw BackendHttpException(response.code(),"forum/moderation")
    }
    suspend fun notifications(token:String,unreadOnly:Boolean=false)=
        checked(api.notifications(unreadOnly,"Bearer $token"),"notifications")
    suspend fun markRead(token:String,id:String) {
        val response=api.markRead(id,"Bearer $token")
        if(!response.isSuccessful) throw BackendHttpException(response.code(),"notifications/read")
    }
}

object DarsyarCommunityApiFactory {
    fun create(baseUrl:String=BackendEndpointConfig.requireBaseUrl()):DarsyarCommunityRepository {
        val normalized=normalizeBackendBaseUrl(baseUrl) ?: throw IllegalArgumentException("نشانی سرور تنظیم نشده است.")
        val moshi=Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val retrofit=Retrofit.Builder().baseUrl(normalized).addConverterFactory(MoshiConverterFactory.create(moshi)).build()
        return DarsyarCommunityRepository(retrofit.create(DarsyarCommunityApi::class.java))
    }
}
