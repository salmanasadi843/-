package com.example.data.repository

import org.junit.Assert.assertThrows
import org.junit.Test

class BackupGenerationCommitGuardTest {
    private fun state(
        generation: String? = "generation-a",
        adminPending: Boolean = false,
        version: Long? = 3L
    ) = BackupRootVersion(
        exists = true,
        ownerUid = "teacher-uid",
        schemaVersion = 3L,
        activeGeneration = generation,
        adminPending = adminPending,
        adminPendingVersion = version,
        syncLockUntilMillis = null
    )

    @Test
    fun allowsActivationWhenRootHasNotChanged() {
        BackupGenerationCommitGuard.requireCanActivate(
            "teacher-uid",
            state(),
            state()
        )
    }

    @Test
    fun rejectsActivationWhenAnotherGenerationBecameActive() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupGenerationCommitGuard.requireCanActivate(
                "teacher-uid",
                state(generation = "generation-a"),
                state(generation = "generation-b")
            )
        }
    }

    @Test
    fun rejectsActivationWhileAdminRestoreIsPending() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupGenerationCommitGuard.requireCanActivate(
                "teacher-uid",
                state(adminPending = true),
                state(adminPending = true)
            )
        }
    }

    @Test
    fun rejectsActivationWhenAdminChangeArrivesDuringUpload() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupGenerationCommitGuard.requireCanActivate(
                "teacher-uid",
                state(),
                state(adminPending = true, version = 4L)
            )
        }
    }

    @Test
    fun rejectsOwnerMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupGenerationCommitGuard.requireCanActivate(
                "teacher-uid",
                state(),
                state().copy(ownerUid = "another-teacher")
            )
        }
    }
}
