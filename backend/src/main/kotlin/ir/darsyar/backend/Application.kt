package ir.darsyar.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.Principal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import javax.sql.DataSource

data class DarsyarPrincipal(
    val userId: String,
    val roles: Set<AppRole>
) : Principal

@Serializable
data class ClassWriteRequest(
    val ownerUserId: String? = null,
    val title: String,
    val description: String = "",
    val term: String = "",
    val visibility: String = "private",
    val isPublished: Boolean = false,
    val isHidden: Boolean = false,
    val folderId: String? = null,
    val sortOrder: Int = 0
)

@Serializable
data class ClassApiRecord(
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

@Serializable
data class TeacherDirectoryApiRecord(
    val userId: String,
    val displayName: String
)

@Serializable
data class FolderWriteRequest(
    val ownerUserId: String? = null,
    val parentFolderId: String? = null,
    val name: String,
    val sortOrder: Int = 0,
    val isHidden: Boolean = false
)

@Serializable
data class FolderApiRecord(
    val id: String,
    val ownerUserId: String,
    val parentFolderId: String?,
    val name: String,
    val sortOrder: Int,
    val isHidden: Boolean,
    val version: Long
)

@Serializable
data class AccessCodeCreateRequest(
    val expiresAtMs: Long? = null,
    val maxUses: Int? = null
)

@Serializable
data class AccessCodeApiRecord(
    val id: String,
    val classId: String,
    val code: String,
    val expiresAtMs: Long?,
    val maxUses: Int?
)

@Serializable
data class AccessCodeListApiRecord(
    val id: String,
    val classId: String,
    val expiresAtMs: Long?,
    val maxUses: Int?,
    val useCount: Int,
    val isActive: Boolean,
    val createdAtMs: Long
)

@Serializable
data class RedeemAccessCodeRequest(val code: String)

@Serializable
data class MembershipApiRecord(
    val classId: String,
    val studentUserId: String,
    val status: String,
    val joinedAtMs: Long?,
    val alreadyActive: Boolean
)

@Serializable
data class CourseWriteRequest(
    val title: String,
    val teacherName: String = "",
    val description: String = "",
    val createdAtMs: Long? = null,
    val sortOrder: Int = 0
)

@Serializable
data class CourseApiRecord(
    val id: String,
    val classId: String,
    val title: String,
    val teacherName: String,
    val description: String,
    val createdAtMs: Long?,
    val sortOrder: Int,
    val version: Long
)

@Serializable
data class MoveSessionRequest(val targetClassId: String)

@Serializable
data class ReorderSessionRequest(val direction: String)

@Serializable
data class SessionWriteRequest(
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

@Serializable
data class SessionSummaryApiRecord(
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

@Serializable
data class SessionApiRecord(
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

@Serializable
data class SessionNoteWriteRequest(
    val quotedText: String = "",
    val noteText: String,
    val startOffset: Int? = null,
    val endOffset: Int? = null
)

@Serializable
data class SessionNoteApiRecord(
    val id: String,
    val userId: String,
    val sessionId: String,
    val quotedText: String,
    val noteText: String,
    val startOffset: Int?,
    val endOffset: Int?,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

private fun UserSessionNoteRecord.toApiRecord() = SessionNoteApiRecord(
    id, userId, sessionId, quotedText, noteText, startOffset, endOffset, createdAtMs, updatedAtMs
)

@Serializable
data class SessionNoteSummaryApiRecord(
    val id: String,
    val userId: String,
    val sessionId: String,
    val classId: String,
    val classTitle: String,
    val sessionTitle: String,
    val courseName: String,
    val teacherName: String,
    val dateMs: Long?,
    val quotedText: String,
    val noteText: String,
    val startOffset: Int?,
    val endOffset: Int?,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

private fun UserSessionNoteSummaryRecord.toSummaryApiRecord() = SessionNoteSummaryApiRecord(
    id, userId, sessionId, classId, classTitle, sessionTitle, courseName, teacherName,
    dateMs, quotedText, noteText, startOffset, endOffset, createdAtMs, updatedAtMs
)

@Serializable
data class SessionStateWriteRequest(
    val isFavorite: Boolean,
    val lastPlaybackPositionMs: Long = 0
)

@Serializable
data class SessionStateApiRecord(
    val userId: String,
    val sessionId: String,
    val isFavorite: Boolean,
    val lastPlaybackPositionMs: Long,
    val updatedAtMs: Long
)

private fun UserSessionStateRecord.toApiRecord() = SessionStateApiRecord(
    userId, sessionId, isFavorite, lastPlaybackPositionMs, updatedAtMs
)

@Serializable
data class AudioSourceWriteRequest(
    val sourceKind: String? = null,
    val sourceUrl: String? = null,
    val storageKey: String? = null,
    val title: String? = null,
    val sortOrder: Int = 0
)

@Serializable
data class AudioSourceApiRecord(
    val id: String,
    val sessionId: String,
    val sourceKind: String,
    val sourceUrl: String?,
    val storageKey: String?,
    val title: String?,
    val sortOrder: Int,
    val isActive: Boolean
)

@Serializable
data class ClassAnnouncementWriteRequest(val title:String,val body:String)
@Serializable
data class ClassAnnouncementApiRecord(
    val id:String,val classId:String,val authorUserId:String,val authorName:String,
    val title:String,val body:String,val isPinned:Boolean,val publishedAtMs:Long
)
private fun ClassAnnouncementRecord.toApiRecord() = ClassAnnouncementApiRecord(
    id,classId,authorUserId,authorName,title,body,isPinned,publishedAtMs
)

@Serializable
data class ForumTopicWriteRequest(val sessionId: String? = null, val title: String, val body: String)
@Serializable
data class ForumTopicApiRecord(
    val id: String, val classId: String, val sessionId: String?, val authorUserId: String,
    val authorName: String, val title: String, val body: String, val isPinned: Boolean,
    val isLocked: Boolean, val replyCount: Int, val createdAtMs: Long, val updatedAtMs: Long
)
@Serializable
data class ForumReplyWriteRequest(val parentPostId: String? = null, val body: String)
@Serializable
data class ForumPostApiRecord(
    val id: String, val topicId: String, val parentPostId: String?, val authorUserId: String,
    val authorName: String, val body: String, val createdAtMs: Long, val updatedAtMs: Long
)
@Serializable
data class ForumModerationRequest(val isPinned: Boolean, val isLocked: Boolean)

@Serializable
data class ForumTopicUpdateRequest(val title: String, val body: String)
@Serializable
data class ForumPostUpdateRequest(val body: String)
@Serializable
data class NotificationApiRecord(
    val id: String, val notificationType: String, val message: String, val classId: String?,
    val topicId: String?, val postId: String?, val actorUserId: String?, val isRead: Boolean,
    val createdAtMs: Long
)
private fun ForumTopicRecord.toApiRecord() = ForumTopicApiRecord(
    id,classId,sessionId,authorUserId,authorName,title,body,isPinned,isLocked,replyCount,createdAtMs,updatedAtMs
)
private fun ForumPostRecord.toApiRecord() = ForumPostApiRecord(
    id,topicId,parentPostId,authorUserId,authorName,body,createdAtMs,updatedAtMs
)
private fun UserNotificationRecord.toApiRecord() = NotificationApiRecord(
    id,notificationType,message,classId,topicId,postId,actorUserId,isRead,createdAtMs
)

@Serializable
data class ApiError(val error: String)

@Serializable
data class AiAnswerWriteRequest(
    val question: String,
    val selectedText: String? = null
)

@Serializable
data class AiCitationApiRecord(
    val startOffset: Int,
    val endOffset: Int,
    val excerpt: String
)

@Serializable
data class AiAnswerApiRecord(
    val answer: String,
    val citations: List<AiCitationApiRecord> = emptyList()
)

@Serializable
data class MeApiResponse(val userId: String, val roles: List<String>)

@Serializable
data class TeacherApplicationWriteRequest(
    val teachingField: String? = null,
    val institutionName: String? = null
)

@Serializable
data class TeacherApplicationDecisionRequest(val decision: String)

@Serializable
data class TeacherApplicationApiRecord(
    val userId: String,
    val displayName: String,
    val email: String?,
    val teachingField: String?,
    val institutionName: String?,
    val approvalStatus: String
)

private fun TeacherApplicationRecord.toApiRecord() = TeacherApplicationApiRecord(
    userId, displayName, email, teachingField, institutionName, approvalStatus
)

private fun TeacherFolderRecord.toApiRecord() = FolderApiRecord(
    id, ownerUserId, parentFolderId, name, sortOrder, isHidden, version
)

private fun TeachingClassRecord.toApiRecord() = ClassApiRecord(
    id, ownerUserId, title, description, term, visibility,
    isPublished, isHidden, sortOrder, folderId
)

private fun TeachingCourseRecord.toApiRecord() = CourseApiRecord(
    id, classId, title, teacherName, description, createdAtMs, sortOrder, version
)

private fun TeachingAudioSourceRecord.toApiRecord() = AudioSourceApiRecord(
    id, sessionId, sourceKind, sourceUrl, storageKey, title, sortOrder, isActive
)

private fun TeachingSessionRecord.toSummaryApiRecord() = SessionSummaryApiRecord(
    id, classId, courseId, title, courseName, professorName, dateMs,
    audioDurationMs, tags, isPublished, lastEditedMs, sortOrder, version
)

private fun TeachingSessionRecord.toApiRecord() = SessionApiRecord(
    id, classId, courseId, title, courseName, professorName, dateMs,
    audioDurationMs, transcript, originalTranscript, aiSummary, aiKeyPoints,
    aiQuizJson, tags, isPublished, lastEditedMs, sortOrder, version
)

fun main() {
    val dataSource = Database.connectAndMigrate()
    val server = io.ktor.server.engine.embeddedServer(
        io.ktor.server.netty.Netty,
        host = System.getenv("HOST") ?: "0.0.0.0",
        port = (System.getenv("PORT") ?: "8080").toInt()
    ) { module(dataSource) }
    Runtime.getRuntime().addShutdownHook(Thread { dataSource.close() })
    server.start(wait = true)
}

fun Application.module(dataSource: DataSource) {
    val tokenVerifier = IdentityTokenVerifier { idToken ->
        val projectId = System.getenv("FIREBASE_PROJECT_ID")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Firebase authentication is not configured.")
        FirebaseAdminTokenVerifier(projectId).verify(idToken)
    }
    module(dataSource, tokenVerifier, PostgresAuthenticatedUserRepository(dataSource))
}

/**
 * Injectable boundary for isolated HTTP authorization tests.
 * Production always calls the overload above, which verifies Firebase tokens
 * and resolves application roles from PostgreSQL.
 */
fun Application.module(
    dataSource: DataSource,
    tokenVerifier: IdentityTokenVerifier,
    userRepository: AuthenticatedUserRepository,
    aiAnswerService: AiAnswerService = DisabledAiAnswerService
) {
    val authenticationService = AuthenticationService(tokenVerifier, userRepository)
    val classRepository = TeachingClassRepository(dataSource)
    val folderRepository = TeacherFolderRepository(dataSource)
    val sessionRepository = TeachingSessionRepository(dataSource)
    val sessionStateRepository = UserSessionStateRepository(dataSource, sessionRepository)
    val sessionNoteRepository = UserSessionNoteRepository(dataSource, sessionRepository)
    val audioSourceRepository = TeachingAudioSourceRepository(dataSource)
    val courseRepository = TeachingCourseRepository(dataSource)
    val classAccessRepository = ClassAccessRepository(dataSource)
    val teacherAdministrationRepository = TeacherAdministrationRepository(dataSource)
    val forumRepository = ClassForumRepository(dataSource)
    val announcementRepository = ClassAnnouncementRepository(dataSource)

    install(ContentNegotiation) { json() }
    install(Authentication) {
        bearer("firebase") {
            authenticate { credential ->
                runCatching {
                    authenticationService.authenticate(credential.token)
                }.getOrNull()?.let { DarsyarPrincipal(it.userId, it.roles) }
            }
        }
    }

    routing {
        get("/health/live") {
            call.respondText(
                """{"status":"ok","service":"darsyar-backend"}""",
                ContentType.Application.Json
            )
        }
        get("/health/ready") {
            val ready = runCatching {
                dataSource.connection.use { connection ->
                    connection.createStatement().use { it.execute("SELECT 1") }
                }
            }.isSuccess
            if (ready) {
                call.respondText(
                    """{"status":"ready","database":"reachable"}""",
                    ContentType.Application.Json
                )
            } else {
                call.respondText(
                    """{"status":"not_ready","database":"unavailable"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable
                )
            }
        }
        get("/api/v1") {
            call.respondText(
                """{"service":"darsyar-backend","apiVersion":"v1","stage":"protected-class-api"}""",
                ContentType.Application.Json
            )
        }

        // Public catalog deliberately sits outside Firebase authentication. It exposes only published public classes.
        get("/api/v1/public/classes") {
            call.respond(classRepository.listAnonymousPublic().map { it.toApiRecord() })
        }
        get("/api/v1/public/classes/{classId}/sessions") {
            val classId = call.parameters["classId"]
            if (classId == null || runCatching { java.util.UUID.fromString(classId) }.isFailure) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_class_id"))
            } else {
                call.respond(sessionRepository.listAnonymousPublic(classId).map { it.toSummaryApiRecord() })
            }
        }
        get("/api/v1/public/classes/{classId}/sessions/{sessionId}") {
            val classId = call.parameters["classId"]
            val sessionId = call.parameters["sessionId"]
            if (classId == null || sessionId == null ||
                runCatching { java.util.UUID.fromString(classId) }.isFailure ||
                runCatching { java.util.UUID.fromString(sessionId) }.isFailure
            ) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_reference"))
            } else {
                val session = sessionRepository.findAnonymousPublic(classId, sessionId)
                if (session == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
                else call.respond(session.toApiRecord())
            }
        }

        authenticate("firebase") {
            get("/api/v1/me") {
                val principal = call.principal<DarsyarPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                } else {
                    call.respond(
                        MeApiResponse(
                            principal.userId,
                            principal.roles.map { it.name.lowercase() }
                        )
                    )
                }
            }
            post("/api/v1/classes/{classId}/sessions/{sessionId}/ai/answer") {
                val principal = call.principal<DarsyarPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                    return@post
                }
                val classId = call.parameters["classId"].orEmpty()
                val sessionId = call.parameters["sessionId"].orEmpty()
                if (runCatching { java.util.UUID.fromString(classId) }.isFailure ||
                    runCatching { java.util.UUID.fromString(sessionId) }.isFailure
                ) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_reference"))
                    return@post
                }
                val request = try { call.receive<AiAnswerWriteRequest>() }
                catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_ai_request"))
                    return@post
                }
                if (request.question.isBlank() || request.question.length > 4_000 ||
                    (request.selectedText?.length ?: 0) > 12_000
                ) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_ai_request"))
                    return@post
                }
                val session = sessionRepository.findVisible(principal.userId, principal.roles, classId, sessionId)
                if (session == null) {
                    call.respond(HttpStatusCode.NotFound, ApiError("session_not_found"))
                    return@post
                }
                if (!aiAnswerService.isAvailable()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError("ai_service_unavailable"))
                    return@post
                }
                try {
                    val result = aiAnswerService.answer(
                        transcript = session.transcript,
                        question = request.question.trim(),
                        selectedText = request.selectedText?.trim()?.takeIf { it.isNotEmpty() }
                    )
                    if (!result.isGroundedIn(session.transcript)) {
                        call.respond(HttpStatusCode.BadGateway, ApiError("invalid_ai_response"))
                        return@post
                    }
                    call.respond(AiAnswerApiRecord(result.answer, result.citations.map {
                        AiCitationApiRecord(it.startOffset, it.endOffset, it.excerpt)
                    }))
                } catch (_: AiProviderUnavailableException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError("ai_service_unavailable"))
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadGateway, ApiError("ai_request_failed"))
                }
            }
            get("/api/v1/me/notes") {
                val principal = call.principal<DarsyarPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                } else {
                    call.respond(
                        sessionNoteRepository.listAllVisible(principal.userId, principal.roles)
                            .map { it.toSummaryApiRecord() }
                    )
                }
            }

            post("/api/v1/teacher-applications") {
                val principal = call.principal<DarsyarPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                    return@post
                }
                val request = try { call.receive<TeacherApplicationWriteRequest>() }
                catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                    return@post
                }
                try {
                    val result = teacherAdministrationRepository.submitApplication(
                        principal.userId, request.teachingField, request.institutionName
                    )
                    call.respond(HttpStatusCode.Created, result.toApiRecord())
                } catch (_: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_teacher_application"))
                } catch (_: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, ApiError("teacher_application_not_allowed"))
                }
            }

            route("/api/v1/admin/teacher-applications") {
                get {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@get
                    }
                    if (AppRole.SUPER_ADMIN !in principal.roles) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("admin_required"))
                        return@get
                    }
                    call.respond(teacherAdministrationRepository.listPendingApplications().map { it.toApiRecord() })
                }

                patch("/{userId}") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@patch
                    }
                    if (AppRole.SUPER_ADMIN !in principal.roles) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("admin_required"))
                        return@patch
                    }
                    val request = try { call.receive<TeacherApplicationDecisionRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@patch
                    }
                    try {
                        val result = teacherAdministrationRepository.decideApplication(
                            principal.userId, call.parameters["userId"] ?: "", request.decision
                        )
                        if (result == null) call.respond(HttpStatusCode.NotFound, ApiError("teacher_application_not_found"))
                        else call.respond(result.toApiRecord())
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_teacher_application_decision"))
                    }
                }
            }

            route("/api/v1/teacher-folders") {
                get {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                    } else {
                        call.respond(folderRepository.listVisible(principal.userId, principal.roles).map { it.toApiRecord() })
                    }
                }

                post {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@post
                    }
                    val request = try {
                        call.receive<FolderWriteRequest>()
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@post
                    }
                    val isAdmin = AppRole.SUPER_ADMIN in principal.roles
                    val ownerId = if (isAdmin) request.ownerUserId ?: run {
                        call.respond(HttpStatusCode.BadRequest, ApiError("owner_user_id_required"))
                        return@post
                    } else principal.userId
                    if (!isAdmin && request.ownerUserId != null && request.ownerUserId != principal.userId) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("cannot_create_for_another_owner"))
                        return@post
                    }
                    try {
                        val created = folderRepository.create(
                            principal.userId, principal.roles,
                            CreateTeacherFolder(ownerId, request.parentFolderId, request.name, request.sortOrder)
                        )
                        call.respond(HttpStatusCode.Created, created.toApiRecord())
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("folder_creation_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_folder_data"))
                    } catch (_: java.sql.SQLException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_folder_parent"))
                    }
                }

                patch("/{folderId}") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@patch
                    }
                    val request = try {
                        call.receive<FolderWriteRequest>()
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@patch
                    }
                    try {
                        val updated = folderRepository.update(
                            principal.userId, principal.roles,
                            call.parameters["folderId"] ?: "",
                            UpdateTeacherFolder(request.parentFolderId, request.name, request.sortOrder, request.isHidden)
                        )
                        if (updated == null) call.respond(HttpStatusCode.NotFound, ApiError("folder_not_found"))
                        else call.respond(updated.toApiRecord())
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("folder_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_folder_data"))
                    } catch (_: java.sql.SQLException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_folder_parent"))
                    }
                }

                delete("/{folderId}") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@delete
                    }
                    try {
                        val deleted = folderRepository.delete(
                            principal.userId, principal.roles,
                            call.parameters["folderId"] ?: ""
                        )
                        if (deleted) call.respond(HttpStatusCode.NoContent)
                        else call.respond(HttpStatusCode.NotFound, ApiError("folder_not_found"))
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("folder_management_forbidden"))
                    } catch (_: IllegalStateException) {
                        call.respond(HttpStatusCode.Conflict, ApiError("folder_not_empty"))
                    }
                }
            }

            route("/api/v1/classes/{classId}/access-codes") {
                get {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@get
                    }
                    try {
                        val classId = call.parameters["classId"] ?: ""
                        call.respond(
                            classAccessRepository.listCodes(principal.userId, principal.roles, classId)
                                .map {
                                    AccessCodeListApiRecord(
                                        it.id, it.classId, it.expiresAt?.toEpochMilli(), it.maxUses,
                                        it.useCount, it.isActive, it.createdAt.toEpochMilli()
                                    )
                                }
                        )
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("access_code_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_class_id"))
                    }
                }

                post {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@post
                    }
                    val request = try { call.receive<AccessCodeCreateRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@post
                    }
                    try {
                        val created = classAccessRepository.createCode(
                            principal.userId, principal.roles,
                            call.parameters["classId"] ?: "",
                            request.expiresAtMs?.let(java.time.Instant::ofEpochMilli),
                            request.maxUses
                        )
                        call.respond(
                            HttpStatusCode.Created,
                            AccessCodeApiRecord(
                                created.id, created.classId, created.code,
                                created.expiresAt?.toEpochMilli(), created.maxUses
                            )
                        )
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("access_code_creation_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_access_code_request"))
                    }
                }
            }

            route("/api/v1/access-codes/{codeId}") {
                delete {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@delete
                    }
                    try {
                        if (classAccessRepository.deactivateCode(
                            principal.userId, principal.roles, call.parameters["codeId"] ?: ""
                        )) call.respond(HttpStatusCode.NoContent)
                        else call.respond(HttpStatusCode.NotFound, ApiError("access_code_not_found"))
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("access_code_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_access_code_id"))
                    }
                }
            }

            route("/api/v1/classes/{classId}/memberships") {
                get {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@get
                    }
                    try {
                        val classId = call.parameters["classId"] ?: ""
                        call.respond(
                            classAccessRepository.listMembers(principal.userId, principal.roles, classId)
                                .map { MembershipApiRecord(it.classId, it.studentUserId, it.status, it.joinedAt?.toEpochMilli(), false) }
                        )
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("membership_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_class_id"))
                    }
                }

                delete("/{studentUserId}") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@delete
                    }
                    try {
                        val classId = call.parameters["classId"] ?: ""
                        val studentId = call.parameters["studentUserId"] ?: ""
                        if (classAccessRepository.revokeMembership(principal.userId, principal.roles, classId, studentId)) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respond(HttpStatusCode.NotFound, ApiError("active_membership_not_found"))
                        }
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("membership_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_membership_target"))
                    }
                }
            }

            post("/api/v1/class-memberships/redeem") {
                val principal = call.principal<DarsyarPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                    return@post
                }
                val request = try { call.receive<RedeemAccessCodeRequest>() }
                catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                    return@post
                }
                try {
                    val result = classAccessRepository.redeem(principal.userId, principal.roles, request.code)
                    call.respond(
                        HttpStatusCode.OK,
                        MembershipApiRecord(
                            result.membership.classId,
                            result.membership.studentUserId,
                            result.membership.status,
                            result.membership.joinedAt?.toEpochMilli(),
                            result.alreadyActive
                        )
                    )
                } catch (_: SecurityException) {
                    call.respond(HttpStatusCode.Forbidden, ApiError("student_account_required"))
                } catch (_: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_or_expired_access_code"))
                }
            }

            get("/api/v1/teachers") {
                val principal = call.principal<DarsyarPrincipal>()
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                } else {
                    try {
                        call.respond(
                            classRepository.listVisibleTeachers(principal.userId, principal.roles)
                                .map { TeacherDirectoryApiRecord(it.userId, it.displayName) }
                        )
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_user_id"))
                    }
                }
            }

            route("/api/v1/classes") {

                get("/deleted") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@get
                    }
                    try {
                        call.respond(classRepository.listDeleted(principal.userId, principal.roles).map { it.toApiRecord() })
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("class_recovery_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_user_id"))
                    }
                }

                delete("/{classId}") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@delete
                    }
                    try {
                        when (classRepository.setDeleted(principal.userId, principal.roles, call.parameters["classId"] ?: "", true)) {
                            null -> call.respond(HttpStatusCode.NotFound, ApiError("class_not_found"))
                            else -> call.respond(HttpStatusCode.NoContent)
                        }
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("class_deletion_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_class_id"))
                    }
                }

                post("/{classId}/restore") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@post
                    }
                    try {
                        when (classRepository.setDeleted(principal.userId, principal.roles, call.parameters["classId"] ?: "", false)) {
                            null -> call.respond(HttpStatusCode.NotFound, ApiError("class_not_found"))
                            else -> call.respond(HttpStatusCode.NoContent)
                        }
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("class_recovery_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_class_id"))
                    }
                }

                get {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                    } else {
                        call.respond(
                            classRepository.listVisible(principal.userId, principal.roles)
                                .map { it.toApiRecord() }
                        )
                    }
                }

                post {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@post
                    }
                    val isAdmin = AppRole.SUPER_ADMIN in principal.roles
                    val isTeacher = AppRole.TEACHER in principal.roles
                    if (!isAdmin && !isTeacher) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("class_creation_forbidden"))
                        return@post
                    }

                    val request = try {
                        call.receive<ClassWriteRequest>()
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@post
                    }
                    val ownerId = if (isAdmin) {
                        request.ownerUserId ?: run {
                            call.respond(HttpStatusCode.BadRequest, ApiError("owner_user_id_required"))
                            return@post
                        }
                    } else {
                        if (request.ownerUserId != null && request.ownerUserId != principal.userId) {
                            call.respond(HttpStatusCode.Forbidden, ApiError("cannot_create_for_another_owner"))
                            return@post
                        }
                        principal.userId
                    }

                    try {
                        val created = classRepository.create(
                            principal.userId,
                            CreateTeachingClass(
                                ownerId, request.title, request.description, request.term,
                                request.visibility, request.isPublished, request.folderId
                            )
                        )
                        call.respond(HttpStatusCode.Created, created.toApiRecord())
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_class_data"))
                    }
                }

                patch("/{classId}") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@patch
                    }
                    val request = try {
                        call.receive<ClassWriteRequest>()
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@patch
                    }
                    try {
                        val updated = classRepository.update(
                            principal.userId,
                            principal.roles,
                            call.parameters["classId"] ?: "",
                            UpdateTeachingClass(
                                request.title, request.description, request.term,
                                request.visibility, request.isPublished,
                                request.isHidden, request.sortOrder, request.folderId
                            )
                        )
                        if (updated == null) {
                            call.respond(HttpStatusCode.NotFound, ApiError("class_not_found"))
                        } else {
                            call.respond(updated.toApiRecord())
                        }
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("class_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_class_data"))
                    }
                }

            }

            route("/api/v1/classes/{classId}/courses") {
                get {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                    } else {
                        try {
                            val classId = call.parameters["classId"] ?: ""
                            call.respond(courseRepository.listVisible(principal.userId, principal.roles, classId).map { it.toApiRecord() })
                        } catch (_: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_class_id"))
                        }
                    }
                }

                post {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@post
                    }
                    if (AppRole.SUPER_ADMIN !in principal.roles && AppRole.TEACHER !in principal.roles) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("course_creation_forbidden"))
                        return@post
                    }
                    val request = try { call.receive<CourseWriteRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@post
                    }
                    try {
                        val created = courseRepository.create(
                            principal.userId, principal.roles,
                            CreateTeachingCourse(
                                classId = call.parameters["classId"] ?: "",
                                title = request.title,
                                teacherName = request.teacherName,
                                description = request.description,
                                createdAtMs = request.createdAtMs,
                                sortOrder = request.sortOrder
                            )
                        )
                        call.respond(HttpStatusCode.Created, created.toApiRecord())
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("course_creation_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_course_data"))
                    } catch (_: java.sql.SQLException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_course_class"))
                    }
                }
            }

            route("/api/v1/courses/{courseId}") {
                patch {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@patch
                    }
                    val request = try { call.receive<CourseWriteRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@patch
                    }
                    try {
                        val updated = courseRepository.update(
                            principal.userId, principal.roles, call.parameters["courseId"] ?: "",
                            UpdateTeachingCourse(request.title, request.teacherName, request.description, request.createdAtMs, request.sortOrder)
                        )
                        if (updated == null) call.respond(HttpStatusCode.NotFound, ApiError("course_not_found"))
                        else call.respond(updated.toApiRecord())
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("course_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_course_data"))
                    }
                }

                delete {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@delete
                    }
                    try {
                        val deleted = courseRepository.delete(
                            principal.userId, principal.roles, call.parameters["courseId"] ?: ""
                        )
                        if (deleted) call.respond(HttpStatusCode.NoContent)
                        else call.respond(HttpStatusCode.NotFound, ApiError("course_not_found"))
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("course_deletion_forbidden"))
                    } catch (_: IllegalStateException) {
                        call.respond(HttpStatusCode.Conflict, ApiError("course_has_sessions"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_course_id"))
                    }
                }
            }

            route("/api/v1/classes/{classId}/sessions") {
                get("/{sessionId}") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@get
                    }
                    try {
                        val classId = call.parameters["classId"] ?: ""
                        val sessionId = call.parameters["sessionId"] ?: ""
                        val session = sessionRepository.findVisible(
                            principal.userId, principal.roles, classId, sessionId
                        )
                        if (session == null) call.respond(HttpStatusCode.NotFound, ApiError("session_not_found"))
                        else call.respond(session.toApiRecord())
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_id"))
                    }
                }

                get {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                    } else {
                        try {
                            val classId = call.parameters["classId"] ?: ""
                            call.respond(sessionRepository.listVisible(principal.userId, principal.roles, classId).map { it.toSummaryApiRecord() })
                        } catch (_: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_class_id"))
                        }
                    }
                }

                post {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@post
                    }
                    if (AppRole.SUPER_ADMIN !in principal.roles && AppRole.TEACHER !in principal.roles) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("session_creation_forbidden"))
                        return@post
                    }
                    val request = try { call.receive<SessionWriteRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@post
                    }
                    try {
                        val created = sessionRepository.create(
                            principal.userId, principal.roles,
                            CreateTeachingSession(
                                classId = call.parameters["classId"] ?: "",
                                courseId = request.courseId, title = request.title,
                                courseName = request.courseName, professorName = request.professorName,
                                dateMs = request.dateMs, audioDurationMs = request.audioDurationMs,
                                transcript = request.transcript, originalTranscript = request.originalTranscript,
                                aiSummary = request.aiSummary, aiKeyPoints = request.aiKeyPoints,
                                aiQuizJson = request.aiQuizJson, tags = request.tags,
                                isPublished = request.isPublished, lastEditedMs = request.lastEditedMs,
                                sortOrder = request.sortOrder
                            )
                        )
                        call.respond(HttpStatusCode.Created, created.toApiRecord())
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("session_creation_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_data"))
                    } catch (_: java.sql.SQLException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_relation_or_json"))
                    }
                }
            }

            route("/api/v1/sessions") {
                get("/deleted") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@get
                    }
                    try {
                        call.respond(sessionRepository.listDeleted(principal.userId, principal.roles).map { it.toSummaryApiRecord() })
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("session_recovery_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_user_id"))
                    }
                }
            }

            route("/api/v1/classes/{classId}/sessions/{sessionId}/notes") {
                get {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@get
                    }
                    val notes = sessionNoteRepository.list(
                        principal.userId, principal.roles,
                        call.parameters["classId"] ?: "", call.parameters["sessionId"] ?: ""
                    )
                    call.respond(notes.map { it.toApiRecord() })
                }
                post {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@post
                    }
                    val request = try { call.receive<SessionNoteWriteRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@post
                    }
                    try {
                        val note = sessionNoteRepository.create(
                            principal.userId, principal.roles,
                            call.parameters["classId"] ?: "", call.parameters["sessionId"] ?: "",
                            request.quotedText, request.noteText, request.startOffset, request.endOffset
                        )
                        call.respond(HttpStatusCode.Created, note.toApiRecord())
                    } catch (_: NoSuchElementException) {
                        call.respond(HttpStatusCode.NotFound, ApiError("session_not_found"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_note"))
                    }
                }
            }
            route("/api/v1/session-notes/{noteId}") {
                patch {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@patch
                    }
                    val request = try { call.receive<SessionNoteWriteRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@patch
                    }
                    try {
                        val note = sessionNoteRepository.update(
                            principal.userId, principal.roles, call.parameters["noteId"] ?: "",
                            request.quotedText, request.noteText, request.startOffset, request.endOffset
                        )
                        call.respond(note.toApiRecord())
                    } catch (_: NoSuchElementException) {
                        call.respond(HttpStatusCode.NotFound, ApiError("session_note_not_found"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_note"))
                    }
                }
                delete {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@delete
                    }
                    try {
                        val deleted = sessionNoteRepository.delete(
                            principal.userId, principal.roles, call.parameters["noteId"] ?: ""
                        )
                        if (deleted) call.respond(HttpStatusCode.NoContent)
                        else call.respond(HttpStatusCode.NotFound, ApiError("session_note_not_found"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_note"))
                    }
                }
            }

            route("/api/v1/classes/{classId}/sessions/{sessionId}/state") {
                get {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@get
                    }
                    try {
                        val state = sessionStateRepository.get(
                            principal.userId, principal.roles,
                            call.parameters["classId"] ?: "", call.parameters["sessionId"] ?: ""
                        )
                        if (state == null) call.respond(HttpStatusCode.NotFound, ApiError("session_not_found"))
                        else call.respond(state.toApiRecord())
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_reference"))
                    }
                }
                put {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@put
                    }
                    val request = try { call.receive<SessionStateWriteRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@put
                    }
                    try {
                        val state = sessionStateRepository.put(
                            principal.userId, principal.roles,
                            call.parameters["classId"] ?: "", call.parameters["sessionId"] ?: "",
                            request.isFavorite, request.lastPlaybackPositionMs
                        )
                        call.respond(state.toApiRecord())
                    } catch (_: NoSuchElementException) {
                        call.respond(HttpStatusCode.NotFound, ApiError("session_not_found"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_state"))
                    }
                }
            }

            route("/api/v1/sessions/{sessionId}/audio-sources") {
                get {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                    } else {
                        try {
                            val sessionId = call.parameters["sessionId"] ?: ""
                            val sources = audioSourceRepository.listVisible(principal.userId, principal.roles, sessionId)
                            call.respond(sources.map { it.toApiRecord() })
                        } catch (_: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_id"))
                        }
                    }
                }

                post {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@post
                    }
                    if (AppRole.SUPER_ADMIN !in principal.roles && AppRole.TEACHER !in principal.roles) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("audio_source_management_forbidden"))
                        return@post
                    }
                    val request = try { call.receive<AudioSourceWriteRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@post
                    }
                    try {
                        val created = audioSourceRepository.create(
                            principal.userId, principal.roles,
                            call.parameters["sessionId"] ?: "",
                            CreateTeachingAudioSource(
                                sourceKind = request.sourceKind ?: "",
                                sourceUrl = request.sourceUrl,
                                storageKey = request.storageKey,
                                title = request.title,
                                sortOrder = request.sortOrder
                            )
                        )
                        call.respond(HttpStatusCode.Created, created.toApiRecord())
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("audio_source_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_audio_source"))
                    } catch (_: java.sql.SQLException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_audio_source_relation"))
                    }
                }
            }

            route("/api/v1/sessions/{sessionId}/audio-sources/{sourceId}") {
                patch {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@patch
                    }
                    val request = try { call.receive<AudioSourceWriteRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@patch
                    }
                    try {
                        val updated = audioSourceRepository.update(
                            principal.userId, principal.roles,
                            call.parameters["sessionId"] ?: "",
                            call.parameters["sourceId"] ?: "",
                            UpdateTeachingAudioSource(
                                request.sourceUrl, request.storageKey, request.title, request.sortOrder
                            )
                        )
                        if (updated == null) call.respond(HttpStatusCode.NotFound, ApiError("audio_source_not_found"))
                        else call.respond(updated.toApiRecord())
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("audio_source_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_audio_source"))
                    } catch (_: java.sql.SQLException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_audio_source_relation"))
                    }
                }

                delete {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@delete
                    }
                    try {
                        val deactivated = audioSourceRepository.deactivate(
                            principal.userId, principal.roles,
                            call.parameters["sessionId"] ?: "",
                            call.parameters["sourceId"] ?: ""
                        )
                        if (deactivated) call.respond(HttpStatusCode.NoContent)
                        else call.respond(HttpStatusCode.NotFound, ApiError("audio_source_not_found"))
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("audio_source_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_audio_source_id"))
                    }
                }
            }

            route("/api/v1/classes/{classId}/announcements") {
                get {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@get}
                    try {
                        call.respond(announcementRepository.list(principal.userId,principal.roles,call.parameters["classId"] ?: "").map{it.toApiRecord()})
                    } catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_class_id"))}
                }
                post {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@post}
                    val request=try{call.receive<ClassAnnouncementWriteRequest>()}catch(_:Exception){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_request"));return@post}
                    try {
                        val created=announcementRepository.create(principal.userId,principal.roles,call.parameters["classId"] ?: "",request.title,request.body)
                        call.respond(HttpStatusCode.Created,created.toApiRecord())
                    } catch(_:SecurityException){call.respond(HttpStatusCode.Forbidden,ApiError("announcement_publish_forbidden"))}
                    catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_announcement"))}
                }
            }

            route("/api/v1/classes/{classId}/forum/topics") {
                get {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@get}
                    try { call.respond(forumRepository.listTopics(principal.userId,principal.roles,call.parameters["classId"] ?: "").map{it.toApiRecord()}) }
                    catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_class_id"))}
                    catch(_:SecurityException){call.respond(HttpStatusCode.Forbidden,ApiError("forum_access_forbidden"))}
                }
                post {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@post}
                    val request=try{call.receive<ForumTopicWriteRequest>()}catch(_:Exception){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_request"));return@post}
                    try {
                        val created=forumRepository.createTopic(principal.userId,principal.roles,call.parameters["classId"] ?: "",request.sessionId,request.title,request.body)
                        call.respond(HttpStatusCode.Created,created.toApiRecord())
                    } catch(_:SecurityException){call.respond(HttpStatusCode.Forbidden,ApiError("forum_access_forbidden"))}
                    catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_forum_topic"))}
                }
            }

            route("/api/v1/forum/topics/{topicId}/posts") {
                get {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@get}
                    try{call.respond(forumRepository.listPosts(principal.userId,principal.roles,call.parameters["topicId"] ?: "").map{it.toApiRecord()})}
                    catch(_:SecurityException){call.respond(HttpStatusCode.Forbidden,ApiError("forum_access_forbidden"))}
                    catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_topic_id"))}
                }
                post {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@post}
                    val request=try{call.receive<ForumReplyWriteRequest>()}catch(_:Exception){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_request"));return@post}
                    try{
                        val created=forumRepository.createReply(principal.userId,principal.roles,call.parameters["topicId"] ?: "",request.parentPostId,request.body)
                        call.respond(HttpStatusCode.Created,created.toApiRecord())
                    }catch(_:SecurityException){call.respond(HttpStatusCode.Forbidden,ApiError("forum_access_forbidden"))}
                    catch(_:IllegalStateException){call.respond(HttpStatusCode.Conflict,ApiError("forum_topic_locked"))}
                    catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_forum_reply"))}
                }
            }


            route("/api/v1/forum/topics/{topicId}") {
                patch {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@patch}
                    val request=try{call.receive<ForumTopicUpdateRequest>()}catch(_:Exception){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_request"));return@patch}
                    try {
                        val updated=forumRepository.updateTopic(principal.userId,principal.roles,call.parameters["topicId"] ?: "",request.title,request.body)
                        call.respond(updated.toApiRecord())
                    } catch(_:SecurityException){call.respond(HttpStatusCode.Forbidden,ApiError("forum_topic_edit_forbidden"))}
                    catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_forum_topic"))}
                }
                delete {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@delete}
                    try {
                        val deleted=forumRepository.deleteTopic(principal.userId,principal.roles,call.parameters["topicId"] ?: "")
                        if(deleted) call.respond(HttpStatusCode.NoContent)
                        else call.respond(HttpStatusCode.NotFound,ApiError("forum_topic_not_found"))
                    } catch(_:SecurityException){call.respond(HttpStatusCode.Forbidden,ApiError("forum_topic_delete_forbidden"))}
                    catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_topic_id"))}
                }
            }

            route("/api/v1/forum/posts/{postId}") {
                patch {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@patch}
                    val request=try{call.receive<ForumPostUpdateRequest>()}catch(_:Exception){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_request"));return@patch}
                    try {
                        val updated=forumRepository.updatePost(principal.userId,principal.roles,call.parameters["postId"] ?: "",request.body)
                        call.respond(updated.toApiRecord())
                    } catch(_:SecurityException){call.respond(HttpStatusCode.Forbidden,ApiError("forum_post_edit_forbidden"))}
                    catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_forum_post"))}
                }
                delete {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@delete}
                    try {
                        val deleted=forumRepository.deletePost(principal.userId,principal.roles,call.parameters["postId"] ?: "")
                        if(deleted) call.respond(HttpStatusCode.NoContent)
                        else call.respond(HttpStatusCode.NotFound,ApiError("forum_post_not_found"))
                    } catch(_:SecurityException){call.respond(HttpStatusCode.Forbidden,ApiError("forum_post_delete_forbidden"))}
                    catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_post_id"))}
                }
            }

            route("/api/v1/forum/topics/{topicId}/moderation") {
                patch {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@patch}
                    val request=try{call.receive<ForumModerationRequest>()}catch(_:Exception){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_request"));return@patch}
                    try{
                        val updated=forumRepository.moderateTopic(principal.userId,principal.roles,call.parameters["topicId"] ?: "",request.isPinned,request.isLocked)
                        if(updated) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound,ApiError("forum_topic_not_found"))
                    }catch(_:SecurityException){call.respond(HttpStatusCode.Forbidden,ApiError("forum_moderation_forbidden"))}
                    catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_topic_id"))}
                }
            }

            route("/api/v1/notifications") {
                get {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@get}
                    val unreadOnly=call.request.queryParameters["unreadOnly"]?.toBooleanStrictOrNull() ?: false
                    try{call.respond(forumRepository.listNotifications(principal.userId,unreadOnly).map{it.toApiRecord()})}
                    catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_user_id"))}
                }
                post("/{notificationId}/read") {
                    val principal=call.principal<DarsyarPrincipal>()
                    if(principal==null){call.respond(HttpStatusCode.Unauthorized,ApiError("unauthorized"));return@post}
                    try{
                        val updated=forumRepository.markNotificationRead(principal.userId,call.parameters["notificationId"] ?: "")
                        if(updated) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound,ApiError("notification_not_found"))
                    }catch(_:IllegalArgumentException){call.respond(HttpStatusCode.BadRequest,ApiError("invalid_notification_id"))}
                }
            }

            route("/api/v1/sessions/{sessionId}") {
                post("/reorder") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@post
                    }
                    val request = try { call.receive<ReorderSessionRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@post
                    }
                    try {
                        val reordered = sessionRepository.reorder(
                            principal.userId,
                            principal.roles,
                            call.parameters["sessionId"] ?: "",
                            request.direction
                        )
                        if (reordered == null) call.respond(HttpStatusCode.NotFound, ApiError("session_not_found"))
                        else call.respond(reordered.toApiRecord())
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("session_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_reorder_request"))
                    }
                }

                post("/move") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@post
                    }
                    val request = try { call.receive<MoveSessionRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@post
                    }
                    try {
                        val moved = sessionRepository.move(
                            principal.userId, principal.roles,
                            call.parameters["sessionId"] ?: "", request.targetClassId
                        )
                        if (moved == null) call.respond(HttpStatusCode.NotFound, ApiError("session_not_found"))
                        else call.respond(moved.toApiRecord())
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("session_move_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_move_target"))
                    } catch (_: java.sql.SQLException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("session_move_failed"))
                    }
                }

                patch {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@patch
                    }
                    val request = try { call.receive<SessionWriteRequest>() }
                    catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
                        return@patch
                    }
                    try {
                        val updated = sessionRepository.update(
                            principal.userId, principal.roles,
                            call.parameters["sessionId"] ?: "",
                            UpdateTeachingSession(
                                request.courseId, request.title, request.courseName, request.professorName,
                                request.dateMs, request.audioDurationMs, request.transcript,
                                request.originalTranscript, request.aiSummary, request.aiKeyPoints,
                                request.aiQuizJson, request.tags, request.isPublished,
                                request.lastEditedMs, request.sortOrder
                            )
                        )
                        if (updated == null) call.respond(HttpStatusCode.NotFound, ApiError("session_not_found"))
                        else call.respond(updated.toApiRecord())
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("session_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_data"))
                    } catch (_: java.sql.SQLException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_relation_or_json"))
                    }
                }
                
                delete {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@delete
                    }
                    try {
                        when (sessionRepository.setDeleted(principal.userId, principal.roles, call.parameters["sessionId"] ?: "", true)) {
                            null -> call.respond(HttpStatusCode.NotFound, ApiError("session_not_found"))
                            else -> call.respond(HttpStatusCode.NoContent)
                        }
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("session_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_id"))
                    }
                }

                post("/restore") {
                    val principal = call.principal<DarsyarPrincipal>()
                    if (principal == null) {
                        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))
                        return@post
                    }
                    try {
                        when (sessionRepository.setDeleted(principal.userId, principal.roles, call.parameters["sessionId"] ?: "", false)) {
                            null -> call.respond(HttpStatusCode.NotFound, ApiError("session_not_found"))
                            else -> call.respond(HttpStatusCode.NoContent)
                        }
                    } catch (_: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden, ApiError("session_management_forbidden"))
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_session_id"))
                    }
                }

            }
        }
    }
}
