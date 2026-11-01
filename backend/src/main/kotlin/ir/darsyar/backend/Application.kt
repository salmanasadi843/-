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
data class ApiError(val error: String)

@Serializable
data class MeApiResponse(val userId: String, val roles: List<String>)

private fun TeacherFolderRecord.toApiRecord() = FolderApiRecord(
    id, ownerUserId, parentFolderId, name, sortOrder, isHidden, version
)

private fun TeachingClassRecord.toApiRecord() = ClassApiRecord(
    id, ownerUserId, title, description, term, visibility,
    isPublished, isHidden, sortOrder, folderId
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
    userRepository: AuthenticatedUserRepository
) {
    val authenticationService = AuthenticationService(tokenVerifier, userRepository)
    val classRepository = TeachingClassRepository(dataSource)
    val folderRepository = TeacherFolderRepository(dataSource)

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

            route("/api/v1/classes") {
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
                                request.visibility, request.isPublished
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
        }
    }
}
