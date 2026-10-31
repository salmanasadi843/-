package ir.darsyar.backend

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Firebase is only an external identity provider here. Application roles are
 * deliberately not read from Firebase custom claims or client-supplied data.
 *
 * Uses Application Default Credentials (for example, a workload identity in
 * production or GOOGLE_APPLICATION_CREDENTIALS in an isolated local setup).
 */
class FirebaseAdminTokenVerifier(
    private val projectId: String
) : IdentityTokenVerifier {
    init {
        require(projectId.isNotBlank()) { "FIREBASE_PROJECT_ID is required." }
    }

    override fun verify(idToken: String): VerifiedIdentity {
        require(idToken.isNotBlank()) { "Firebase ID token is required." }
        val app = FirebaseAdminAppHolder.get(projectId)
        val decoded = FirebaseAuth.getInstance(app).verifyIdToken(idToken, true)
        return VerifiedIdentity(
            provider = "firebase",
            subject = decoded.uid,
            email = decoded.email,
            emailVerified = decoded.isEmailVerified
        )
    }
}

private object FirebaseAdminAppHolder {
    @Volatile private var cached: FirebaseApp? = null
    @Volatile private var cachedProjectId: String? = null

    fun get(projectId: String): FirebaseApp {
        cached?.let {
            check(cachedProjectId == projectId) {
                "This process is already initialized for a different Firebase project."
            }
            return it
        }
        synchronized(this) {
            cached?.let {
                check(cachedProjectId == projectId) {
                    "This process is already initialized for a different Firebase project."
                }
                return it
            }
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .setProjectId(projectId)
                .build()
            val app = FirebaseApp.initializeApp(options, "darsyar-auth")
            cachedProjectId = projectId
            cached = app
            return app
        }
    }
}

/** Resolves provider identities and server-managed roles from PostgreSQL. */
class PostgresAuthenticatedUserRepository(
    private val dataSource: DataSource
) : AuthenticatedUserRepository {
    override fun findByIdentity(
        provider: String,
        subject: String
    ): AuthenticatedUserRecord? {
        require(provider.isNotBlank() && subject.isNotBlank())

        dataSource.connection.use { connection ->
            val sql = """
                SELECT u.id::text AS user_id,
                       u.status AS user_status,
                       r.role_name AS role_name,
                       tp.approval_status AS teacher_approval
                FROM auth_identities ai
                JOIN app_users u ON u.id = ai.user_id
                LEFT JOIN app_roles r ON r.user_id = u.id
                LEFT JOIN teacher_profiles tp ON tp.user_id = u.id
                WHERE ai.provider = ? AND ai.provider_subject = ?
                ORDER BY r.role_name
            """.trimIndent()

            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, provider)
                statement.setString(2, subject)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return null

                    val userId = rows.getString("user_id")
                    val status = rows.getString("user_status")
                    val roles = linkedSetOf<AppRole>()
                    addRoleIfAllowed(rows, roles)

                    while (rows.next()) {
                        check(rows.getString("user_id") == userId) {
                            "Identity lookup returned inconsistent user records."
                        }
                        addRoleIfAllowed(rows, roles)
                    }
                    return AuthenticatedUserRecord(userId, status, roles)
                }
            }
        }
    }

    private fun addRoleIfAllowed(rows: ResultSet, roles: MutableSet<AppRole>) {
        val roleName = rows.getString("role_name") ?: return
        val role = when (roleName) {
            "super_admin" -> AppRole.SUPER_ADMIN
            "teacher" -> AppRole.TEACHER
            "student" -> AppRole.STUDENT
            else -> throw IllegalStateException("Unknown role stored in app_roles.")
        }
        if (role != AppRole.TEACHER || rows.getString("teacher_approval") == "approved") {
            roles += role
        }
    }
}
