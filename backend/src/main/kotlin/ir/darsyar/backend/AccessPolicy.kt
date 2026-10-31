package ir.darsyar.backend

enum class AppRole { SUPER_ADMIN, TEACHER, STUDENT, GUEST }

data class ClassAccess(
    val ownerUserId: String,
    val published: Boolean,
    val public: Boolean,
    val activeMembership: Boolean = false
)

/** Central policy. Call only after authentication and server-side role lookup. */
object AccessPolicy {
    fun canReadClass(role: AppRole, actorUserId: String?, resource: ClassAccess): Boolean {
        if (role == AppRole.SUPER_ADMIN) return true
        if (actorUserId != null && actorUserId == resource.ownerUserId) return true
        return when (role) {
            AppRole.TEACHER -> resource.published
            AppRole.STUDENT -> resource.published && (resource.public || resource.activeMembership)
            AppRole.GUEST -> resource.published && resource.public
            AppRole.SUPER_ADMIN -> true
        }
    }
    fun canManageClass(role: AppRole, actorUserId: String?, resource: ClassAccess): Boolean =
        role == AppRole.SUPER_ADMIN ||
            (role == AppRole.TEACHER && actorUserId != null && actorUserId == resource.ownerUserId)
}
