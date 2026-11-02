package ir.darsyar.backend

/**
 * Claims proven by a trusted identity-provider verifier.
 *
 * This deliberately contains no application role. Roles are loaded from our
 * own database after the external identity has been verified.
 */
data class VerifiedIdentity(
    val provider: String,
    val subject: String,
    val email: String? = null,
    val emailVerified: Boolean = false
)

fun interface IdentityTokenVerifier {
    /** Verify signature, issuer, audience, expiry and token format. */
    fun verify(idToken: String): VerifiedIdentity
}

data class AuthenticatedUserRecord(
    val userId: String,
    val status: String,
    val roles: Set<AppRole>
)

fun interface AuthenticatedUserRepository {
    /** Resolve an already-linked provider identity and load roles from the database. */
    fun findByIdentity(provider: String, subject: String): AuthenticatedUserRecord?
}

data class AuthenticatedPrincipal(
    val userId: String,
    val provider: String,
    val providerSubject: String,
    val email: String?,
    val roles: Set<AppRole>
)

/**
 * Provider-independent authentication boundary.
 *
 * No auto-provisioning is performed: an identity must be explicitly linked to
 * an application user, and every role comes from the server-side repository.
 */
class AuthenticationService(
    private val tokenVerifier: IdentityTokenVerifier,
    private val userRepository: AuthenticatedUserRepository
) {
    fun authenticate(idToken: String): AuthenticatedPrincipal {
        require(idToken.isNotBlank()) { "Bearer token is required." }

        val identity = tokenVerifier.verify(idToken)
        require(identity.provider.isNotBlank() && identity.subject.isNotBlank()) {
            "Verified identity is incomplete."
        }

        val user = userRepository.findByIdentity(identity.provider, identity.subject)
            ?: throw SecurityException("Identity is not linked to an application account.")

        if (user.status != "active") {
            throw SecurityException("Application account is not active.")
        }

        return AuthenticatedPrincipal(
            userId = user.userId,
            provider = identity.provider,
            providerSubject = identity.subject,
            email = identity.email,
            roles = user.roles
        )
    }
}
