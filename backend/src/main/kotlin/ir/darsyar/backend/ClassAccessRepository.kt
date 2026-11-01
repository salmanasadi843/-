package ir.darsyar.backend

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class CreatedClassAccessCode(
    val id: String,
    val classId: String,
    /** Returned only once at creation; only its SHA-256 hash is persisted. */
    val code: String,
    val expiresAt: Instant?,
    val maxUses: Int?
)

data class ClassMembershipRecord(
    val classId: String,
    val studentUserId: String,
    val status: String,
    val joinedAt: Instant?
)

data class RedeemClassAccessResult(
    val membership: ClassMembershipRecord,
    val alreadyActive: Boolean
)

/** Secure private-class access codes and student memberships. */
class ClassAccessRepository(
    private val dataSource: DataSource,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    fun createCode(
        actorUserId: String,
        roles: Set<AppRole>,
        classId: String,
        expiresAt: Instant? = null,
        maxUses: Int? = null
    ): CreatedClassAccessCode {
        val actor = UUID.fromString(actorUserId)
        val classUuid = UUID.fromString(classId)
        require(maxUses == null || maxUses > 0) { "Maximum uses must be positive." }
        require(expiresAt == null || expiresAt.isAfter(Instant.now())) { "Expiry must be in the future." }
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val owner = connection.prepareStatement(
                    "SELECT owner_user_id, visibility FROM teaching_classes WHERE id = ? FOR UPDATE"
                ).use { st ->
                    st.setObject(1, classUuid)
                    st.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject("owner_user_id", UUID::class.java) to rs.getString("visibility")
                        else null
                    }
                } ?: throw IllegalArgumentException("Class not found.")
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && owner.first == actor)
                ) throw SecurityException("You cannot manage access to this class.")
                require(owner.second == "private") { "Access codes are only for private classes." }

                val plainCode = generateCode()
                val created = connection.prepareStatement(
                    """
                    INSERT INTO class_access_codes(class_id, code_hash, expires_at, max_uses, created_by)
                    VALUES (?, ?, ?, ?, ?)
                    RETURNING id::text, class_id::text, expires_at, max_uses
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, classUuid)
                    st.setString(2, hashCode(plainCode))
                    if (expiresAt == null) st.setNull(3, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                    else st.setObject(3, expiresAt?.atOffset(java.time.ZoneOffset.UTC))
                    if (maxUses == null) st.setNull(4, java.sql.Types.INTEGER) else st.setInt(4, maxUses)
                    st.setObject(5, actor)
                    st.executeQuery().use { rs ->
                        check(rs.next())
                        CreatedClassAccessCode(
                            rs.getString("id"), rs.getString("class_id"), plainCode,
                            rs.getTimestamp("expires_at")?.toInstant(),
                            rs.getObject("max_uses")?.let { (it as Number).toInt() }
                        )
                    }
                }
                connection.writeAccessAudit(actor, "class_access_code.created", created.id, owner.first)
                connection.commit()
                return created
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally { connection.autoCommit = true }
        }
    }

    fun deactivateCode(actorUserId: String, roles: Set<AppRole>, codeId: String): Boolean {
        val actor = UUID.fromString(actorUserId)
        val id = UUID.fromString(codeId)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val owner = connection.prepareStatement(
                    """
                    SELECT ac.class_id, c.owner_user_id
                    FROM class_access_codes ac JOIN teaching_classes c ON c.id = ac.class_id
                    WHERE ac.id = ? AND ac.is_active = true
                    FOR UPDATE OF ac
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, id)
                    st.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject("owner_user_id", UUID::class.java) to rs.getObject("class_id", UUID::class.java)
                        else null
                    }
                } ?: run { connection.rollback(); return false }
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && owner.first == actor)
                ) throw SecurityException("You cannot manage this access code.")
                connection.prepareStatement("UPDATE class_access_codes SET is_active = false WHERE id = ?").use { st ->
                    st.setObject(1, id); check(st.executeUpdate() == 1)
                }
                connection.writeAccessAudit(actor, "class_access_code.deactivated", id.toString(), owner.first)
                connection.commit()
                return true
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally { connection.autoCommit = true }
        }
    }

    fun listMembers(actorUserId: String, roles: Set<AppRole>, classId: String): List<ClassMembershipRecord> {
        val actor = UUID.fromString(actorUserId)
        val classUuid = UUID.fromString(classId)
        dataSource.connection.use { connection ->
            val owner = connection.prepareStatement(
                "SELECT owner_user_id FROM teaching_classes WHERE id = ?"
            ).use { st ->
                st.setObject(1, classUuid)
                st.executeQuery().use { rs ->
                    if (rs.next()) rs.getObject("owner_user_id", UUID::class.java) else null
                }
            } ?: throw IllegalArgumentException("Class not found.")
            if (AppRole.SUPER_ADMIN !in roles &&
                !(AppRole.TEACHER in roles && owner == actor)
            ) throw SecurityException("You cannot manage memberships for this class.")
            connection.prepareStatement(
                """
                SELECT class_id::text, student_user_id::text, status, joined_at
                FROM class_memberships
                WHERE class_id = ?
                ORDER BY joined_at NULLS LAST, student_user_id
                """.trimIndent()
            ).use { st ->
                st.setObject(1, classUuid)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            ClassMembershipRecord(
                                rs.getString("class_id"), rs.getString("student_user_id"),
                                rs.getString("status"), rs.getTimestamp("joined_at")?.toInstant()
                            )
                        )
                    }
                }
            }
        }
    }

    fun revokeMembership(
        actorUserId: String,
        roles: Set<AppRole>,
        classId: String,
        studentUserId: String
    ): Boolean {
        val actor = UUID.fromString(actorUserId)
        val classUuid = UUID.fromString(classId)
        val student = UUID.fromString(studentUserId)
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val owner = connection.prepareStatement(
                    "SELECT owner_user_id FROM teaching_classes WHERE id = ? FOR UPDATE"
                ).use { st ->
                    st.setObject(1, classUuid)
                    st.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject("owner_user_id", UUID::class.java) else null
                    }
                } ?: throw IllegalArgumentException("Class not found.")
                if (AppRole.SUPER_ADMIN !in roles &&
                    !(AppRole.TEACHER in roles && owner == actor)
                ) throw SecurityException("You cannot manage memberships for this class.")
                val changed = connection.prepareStatement(
                    """
                    UPDATE class_memberships
                    SET status = 'revoked', updated_at = now()
                    WHERE class_id = ? AND student_user_id = ? AND status = 'active'
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, classUuid)
                    st.setObject(2, student)
                    st.executeUpdate()
                }
                if (changed == 0) {
                    connection.commit()
                    return false
                }
                connection.writeAccessAudit(actor, "class_membership.revoked", classUuid.toString(), owner)
                connection.commit()
                return true
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally { connection.autoCommit = true }
        }
    }

    fun redeem(actorUserId: String, roles: Set<AppRole>, rawCode: String): RedeemClassAccessResult {
        val actor = UUID.fromString(actorUserId)
        if (AppRole.STUDENT !in roles || AppRole.SUPER_ADMIN in roles || AppRole.TEACHER in roles) {
            throw SecurityException("Only student accounts can redeem private-class codes.")
        }
        val normalized = rawCode.trim().uppercase()
        require(normalized.matches(Regex("[A-Z2-9]{10}"))) { "Invalid access code." }
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val access = connection.prepareStatement(
                    """
                    SELECT ac.id, ac.class_id, ac.max_uses, ac.use_count, ac.expires_at,
                           c.owner_user_id, c.visibility, c.is_published, c.is_hidden
                    FROM class_access_codes ac
                    JOIN teaching_classes c ON c.id = ac.class_id
                    WHERE ac.code_hash = ? AND ac.is_active = true
                    FOR UPDATE OF ac
                    """.trimIndent()
                ).use { st ->
                    st.setString(1, hashCode(normalized))
                    st.executeQuery().use { rs ->
                        if (rs.next()) AccessRow(
                            rs.getObject("id", UUID::class.java),
                            rs.getObject("class_id", UUID::class.java),
                            rs.getObject("max_uses")?.let { (it as Number).toInt() },
                            rs.getInt("use_count"),
                            rs.getTimestamp("expires_at")?.toInstant(),
                            rs.getObject("owner_user_id", UUID::class.java),
                            rs.getString("visibility"),
                            rs.getBoolean("is_published"),
                            rs.getBoolean("is_hidden")
                        ) else null
                    }
                } ?: throw IllegalArgumentException("Invalid or inactive access code.")

                if (access.visibility != "private" || !access.isPublished || access.isHidden)
                    throw IllegalArgumentException("This private class is not currently available.")
                if (access.expiresAt != null && !access.expiresAt.isAfter(Instant.now()))
                    throw IllegalArgumentException("Access code has expired.")
                val existing = connection.prepareStatement(
                    "SELECT status, joined_at FROM class_memberships WHERE class_id = ? AND student_user_id = ? FOR UPDATE"
                ).use { st ->
                    st.setObject(1, access.classId); st.setObject(2, actor)
                    st.executeQuery().use { rs ->
                        if (rs.next()) ClassMembershipRecord(
                            access.classId.toString(), actor.toString(), rs.getString("status"),
                            rs.getTimestamp("joined_at")?.toInstant()
                        ) else null
                    }
                }
                if (existing?.status == "active") {
                    connection.commit()
                    return RedeemClassAccessResult(existing, true)
                }

                if (access.maxUses != null && access.useCount >= access.maxUses)
                    throw IllegalArgumentException("Access code has reached its usage limit.")

                val membership = connection.prepareStatement(
                    """
                    INSERT INTO class_memberships(class_id, student_user_id, status, joined_at, updated_at)
                    VALUES (?, ?, 'active', now(), now())
                    ON CONFLICT (class_id, student_user_id) DO UPDATE
                    SET status = 'active', joined_at = now(), updated_at = now()
                    RETURNING class_id::text, student_user_id::text, status, joined_at
                    """.trimIndent()
                ).use { st ->
                    st.setObject(1, access.classId); st.setObject(2, actor)
                    st.executeQuery().use { rs ->
                        check(rs.next())
                        ClassMembershipRecord(
                            rs.getString("class_id"), rs.getString("student_user_id"),
                            rs.getString("status"), rs.getTimestamp("joined_at")?.toInstant()
                        )
                    }
                }
                connection.prepareStatement(
                    "UPDATE class_access_codes SET use_count = use_count + 1 WHERE id = ?"
                ).use { st -> st.setObject(1, access.id); check(st.executeUpdate() == 1) }
                connection.writeAccessAudit(actor, "class_membership.activated", access.classId.toString(), access.ownerUserId)
                connection.commit()
                return RedeemClassAccessResult(membership, false)
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally { connection.autoCommit = true }
        }
    }

    private fun generateCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(10) { repeat(10) { append(alphabet[secureRandom.nextInt(alphabet.length)]) } }
    }

    private fun hashCode(code: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class AccessRow(
        val id: UUID, val classId: UUID, val maxUses: Int?, val useCount: Int,
        val expiresAt: Instant?, val ownerUserId: UUID, val visibility: String, val isPublished: Boolean, val isHidden: Boolean
    )
}

private fun Connection.writeAccessAudit(actor: UUID, action: String, entityId: String, owner: UUID) {
    prepareStatement(
        """
        INSERT INTO audit_log(actor_user_id, action, entity_type, entity_id, owner_user_id)
        VALUES (?, ?, ?, ?::uuid, ?)
        """.trimIndent()
    ).use { st ->
        st.setObject(1, actor)
        st.setString(2, action)
        st.setString(3, if (action.startsWith("class_access_code")) "class_access_code" else "class_membership")
        st.setString(4, entityId)
        st.setObject(5, owner)
        check(st.executeUpdate() == 1)
    }
}
