package ir.darsyar.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthenticationTest {
    private class FakeVerifier(
        private val identity: VerifiedIdentity
    ) : IdentityTokenVerifier {
        override fun verify(idToken: String): VerifiedIdentity {
            check(idToken == "valid-test-token")
            return identity
        }
    }

    @Test
    fun `principal roles are loaded from repository not token claims`() {
        val verifier = FakeVerifier(
            VerifiedIdentity(
                provider = "firebase",
                subject = "firebase-uid-123",
                email = "teacher@example.org",
                emailVerified = true
            )
        )
        val repository = AuthenticatedUserRepository { provider, subject ->
            check(provider == "firebase")
            check(subject == "firebase-uid-123")
            AuthenticatedUserRecord(
                userId = "internal-user-uuid",
                status = "active",
                roles = setOf(AppRole.TEACHER)
            )
        }

        val principal = AuthenticationService(verifier, repository)
            .authenticate("valid-test-token")

        assertEquals("internal-user-uuid", principal.userId)
        assertEquals(setOf(AppRole.TEACHER), principal.roles)
        assertEquals("firebase", principal.provider)
    }

    @Test
    fun `unlinked external identity is rejected`() {
        val service = AuthenticationService(
            FakeVerifier(VerifiedIdentity("firebase", "unknown-uid")),
            AuthenticatedUserRepository { _, _ -> null }
        )

        assertFailsWith<SecurityException> {
            service.authenticate("valid-test-token")
        }
    }

    @Test
    fun `suspended application user is rejected`() {
        val service = AuthenticationService(
            FakeVerifier(VerifiedIdentity("firebase", "known-uid")),
            AuthenticatedUserRepository { _, _ ->
                AuthenticatedUserRecord("user-uuid", "suspended", setOf(AppRole.SUPER_ADMIN))
            }
        )

        assertFailsWith<SecurityException> {
            service.authenticate("valid-test-token")
        }
    }

    @Test
    fun `blank token is rejected before verification`() {
        val service = AuthenticationService(
            FakeVerifier(VerifiedIdentity("firebase", "known-uid")),
            AuthenticatedUserRepository { _, _ ->
                AuthenticatedUserRecord("user-uuid", "active", setOf(AppRole.STUDENT))
            }
        )

        assertFailsWith<IllegalArgumentException> {
            service.authenticate(" ")
        }
    }
}
