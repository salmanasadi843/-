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
    val sortOrder: Int
)

@Serializable
data class ApiError(val error: String)

@Serializable
data class MeApiResponse(val userId: String, val roles: List<String>)

private fun TeachingClassRecord.toApiRecord() = ClassApiRecord(
    id, ownerUserId, title, description, term, visibility,
    isPublished, isHidden, sortOrder
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
    val authenticationService = AuthenticationService(
        tokenVerifier,
        PostgresAuthenticatedUserRepository(dataSource)
    )
    val classRepository = TeachingClassRepository(dataSource)

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
                                request.isHidden, request.sortOrder
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
