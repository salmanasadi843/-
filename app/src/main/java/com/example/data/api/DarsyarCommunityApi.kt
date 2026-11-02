package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*

interface DarsyarCommunityApi {
    @GET("api/v1/classes/{classId}/announcements")
    suspend fun announcements(@Path("classId") classId:String,@Header("Authorization") auth:String):Response<List<BackendAnnouncementDto>>
    @POST("api/v1/classes/{classId}/announcements")
    suspend fun createAnnouncement(@Path("classId") classId:String,@Body body:AnnouncementWriteDto,@Header("Authorization") auth:String):Response<BackendAnnouncementDto>
    @GET("api/v1/classes/{classId}/forum/topics")
    suspend fun topics(@Path("classId") classId:String,@Header("Authorization") auth:String):Response<List<BackendForumTopicDto>>
    @POST("api/v1/classes/{classId}/forum/topics")
    suspend fun createTopic(@Path("classId") classId:String,@Body body:ForumTopicWriteDto,@Header("Authorization") auth:String):Response<BackendForumTopicDto>
    @PATCH("api/v1/forum/topics/{topicId}")
    suspend fun updateTopic(@Path("topicId") topicId:String,@Body body:ForumTopicUpdateDto,@Header("Authorization") auth:String):Response<BackendForumTopicDto>
    @DELETE("api/v1/forum/topics/{topicId}")
    suspend fun deleteTopic(@Path("topicId") topicId:String,@Header("Authorization") auth:String):Response<Unit>
    @GET("api/v1/forum/topics/{topicId}/posts")
    suspend fun posts(@Path("topicId") topicId:String,@Header("Authorization") auth:String):Response<List<BackendForumPostDto>>
    @POST("api/v1/forum/topics/{topicId}/posts")
    suspend fun reply(@Path("topicId") topicId:String,@Body body:ForumReplyWriteDto,@Header("Authorization") auth:String):Response<BackendForumPostDto>
    @PATCH("api/v1/forum/posts/{postId}")
    suspend fun updatePost(@Path("postId") postId:String,@Body body:ForumPostUpdateDto,@Header("Authorization") auth:String):Response<BackendForumPostDto>
    @DELETE("api/v1/forum/posts/{postId}")
    suspend fun deletePost(@Path("postId") postId:String,@Header("Authorization") auth:String):Response<Unit>
    @PATCH("api/v1/forum/topics/{topicId}/moderation")
    suspend fun moderate(@Path("topicId") topicId:String,@Body body:ForumModerationDto,@Header("Authorization") auth:String):Response<Unit>
    @GET("api/v1/classes/{classId}/sessions/{sessionId}/notes")
    suspend fun sessionNotes(@Path("classId") classId:String,@Path("sessionId") sessionId:String,@Header("Authorization") auth:String):Response<List<BackendSessionNoteDto>>
    @POST("api/v1/classes/{classId}/sessions/{sessionId}/notes")
    suspend fun createSessionNote(@Path("classId") classId:String,@Path("sessionId") sessionId:String,@Body body:SessionNoteWriteDto,@Header("Authorization") auth:String):Response<BackendSessionNoteDto>
    @PATCH("api/v1/session-notes/{noteId}")
    suspend fun updateSessionNote(@Path("noteId") noteId:String,@Body body:SessionNoteWriteDto,@Header("Authorization") auth:String):Response<BackendSessionNoteDto>
    @DELETE("api/v1/session-notes/{noteId}")
    suspend fun deleteSessionNote(@Path("noteId") noteId:String,@Header("Authorization") auth:String):Response<Unit>
    @GET("api/v1/classes/{classId}/sessions/{sessionId}/state")
    suspend fun sessionState(@Path("classId") classId:String,@Path("sessionId") sessionId:String,@Header("Authorization") auth:String):Response<BackendSessionStateDto>
    @PUT("api/v1/classes/{classId}/sessions/{sessionId}/state")
    suspend fun saveSessionState(@Path("classId") classId:String,@Path("sessionId") sessionId:String,@Body body:SessionStateWriteDto,@Header("Authorization") auth:String):Response<BackendSessionStateDto>
    @GET("api/v1/notifications")
    suspend fun notifications(@Query("unreadOnly") unreadOnly:Boolean,@Header("Authorization") auth:String):Response<List<BackendNotificationDto>>
    @POST("api/v1/notifications/{notificationId}/read")
    suspend fun markRead(@Path("notificationId") id:String,@Header("Authorization") auth:String):Response<Unit>
}

@JsonClass(generateAdapter=true)
data class BackendAnnouncementDto(
    val id:String,val classId:String,val authorUserId:String,val authorName:String,
    val title:String,val body:String,val isPinned:Boolean,val publishedAtMs:Long
)
@JsonClass(generateAdapter=true)
data class AnnouncementWriteDto(val title:String,val body:String)
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
data class BackendSessionStateDto(
    val userId:String,val sessionId:String,val isFavorite:Boolean,
    val lastPlaybackPositionMs:Long,val updatedAtMs:Long
)
@JsonClass(generateAdapter=true)
data class SessionStateWriteDto(val isFavorite:Boolean,val lastPlaybackPositionMs:Long=0L)
@JsonClass(generateAdapter=true)
data class BackendSessionNoteDto(
    val id:String,val userId:String,val sessionId:String,val quotedText:String,val noteText:String,
    val startOffset:Int?,val endOffset:Int?,val createdAtMs:Long,val updatedAtMs:Long
)
@JsonClass(generateAdapter=true)
data class SessionNoteWriteDto(
    val quotedText:String="",val noteText:String,val startOffset:Int?=null,val endOffset:Int?=null
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
data class ForumTopicUpdateDto(val title:String,val body:String)
@JsonClass(generateAdapter=true)
data class ForumPostUpdateDto(val body:String)
@JsonClass(generateAdapter=true)
data class ForumModerationDto(val isPinned:Boolean,val isLocked:Boolean)

class DarsyarCommunityRepository(private val api:DarsyarCommunityApi) {
    private fun <T> checked(response:Response<T>,endpoint:String):T =
        if(response.isSuccessful) response.body() ?: throw IllegalStateException("پاسخ خالی از سرور")
        else throw BackendHttpException(response.code(),endpoint)

    suspend fun announcements(token:String,classId:String)=checked(api.announcements(classId,"Bearer $token"),"announcements")
    suspend fun createAnnouncement(token:String,classId:String,title:String,body:String)=
        checked(api.createAnnouncement(classId,AnnouncementWriteDto(title,body),"Bearer $token"),"announcements/create")
    suspend fun topics(token:String,classId:String)=checked(api.topics(classId,"Bearer $token"),"forum/topics")
    suspend fun createTopic(token:String,classId:String,sessionId:String?,title:String,body:String)=
        checked(api.createTopic(classId,ForumTopicWriteDto(sessionId,title,body),"Bearer $token"),"forum/topics")
    suspend fun updateTopic(token:String,topicId:String,title:String,body:String)=
        checked(api.updateTopic(topicId,ForumTopicUpdateDto(title,body),"Bearer $token"),"forum/topics/update")
    suspend fun deleteTopic(token:String,topicId:String) {
        val response=api.deleteTopic(topicId,"Bearer $token")
        if(!response.isSuccessful) throw BackendHttpException(response.code(),"forum/topics/delete")
    }
    suspend fun posts(token:String,topicId:String)=checked(api.posts(topicId,"Bearer $token"),"forum/posts")
    suspend fun reply(token:String,topicId:String,parentId:String?,body:String)=
        checked(api.reply(topicId,ForumReplyWriteDto(parentId,body),"Bearer $token"),"forum/reply")
    suspend fun updatePost(token:String,postId:String,body:String)=
        checked(api.updatePost(postId,ForumPostUpdateDto(body),"Bearer $token"),"forum/posts/update")
    suspend fun deletePost(token:String,postId:String) {
        val response=api.deletePost(postId,"Bearer $token")
        if(!response.isSuccessful) throw BackendHttpException(response.code(),"forum/posts/delete")
    }
    suspend fun moderate(token:String,topicId:String,pinned:Boolean,locked:Boolean) {
        val response=api.moderate(topicId,ForumModerationDto(pinned,locked),"Bearer $token")
        if(!response.isSuccessful) throw BackendHttpException(response.code(),"forum/moderation")
    }
    suspend fun sessionNotes(token:String,classId:String,sessionId:String)=
        checked(api.sessionNotes(classId,sessionId,"Bearer $token"),"session/notes")
    suspend fun createSessionNote(token:String,classId:String,sessionId:String,quotedText:String,noteText:String,startOffset:Int?=null,endOffset:Int?=null)=
        checked(api.createSessionNote(classId,sessionId,SessionNoteWriteDto(quotedText,noteText,startOffset,endOffset),"Bearer $token"),"session/notes/create")
    suspend fun updateSessionNote(token:String,noteId:String,quotedText:String,noteText:String,startOffset:Int?=null,endOffset:Int?=null)=
        checked(api.updateSessionNote(noteId,SessionNoteWriteDto(quotedText,noteText,startOffset,endOffset),"Bearer $token"),"session/notes/update")
    suspend fun deleteSessionNote(token:String,noteId:String) {
        val response=api.deleteSessionNote(noteId,"Bearer $token")
        if(!response.isSuccessful) throw BackendHttpException(response.code(),"session/notes/delete")
    }
    suspend fun sessionState(token:String,classId:String,sessionId:String)=
        checked(api.sessionState(classId,sessionId,"Bearer $token"),"session/state")
    suspend fun saveSessionState(token:String,classId:String,sessionId:String,isFavorite:Boolean,positionMs:Long)=
        checked(api.saveSessionState(classId,sessionId,SessionStateWriteDto(isFavorite,positionMs),"Bearer $token"),"session/state/save")
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
