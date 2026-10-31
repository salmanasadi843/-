package ir.darsyar.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.Principal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import javax.sql.DataSource

data class DarsyarPrincipal(
    val userId: String,
    val roles: Set<AppRole>
) : Principal

fun main() {
    val dataSource = Database.connectAndMigrate()
    val server = embeddedServer(
        Netty,
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
                """{"service":"darsyar-backend","apiVersion":"v1","stage":"staging-foundation"}""",
                ContentType.Application.Json
            )
        }
        authenticate("firebase") {
            get("/api/v1/me") {
                val principal = call.principal<DarsyarPrincipal>()
                if (principal == null) {
                    call.respondText(
                        """{"error":"unauthorized"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized
                    )
                } else {
                    val roles = principal.roles.joinToString(",") { "\"\${it.name.lowercase()}\"" }
                    call.respondText(
                        """{"userId":"${principal.userId}","roles":[$roles]}""",
                        ContentType.Application.Json
                    )
                }
            }
        }
    }
}
