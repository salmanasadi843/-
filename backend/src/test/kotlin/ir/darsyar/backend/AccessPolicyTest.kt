package ir.darsyar.backend

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessPolicyTest {
    private val publishedClass = ClassAccess("teacher-a", published = true, public = true)

    @Test fun adminCanManageAnyTeacherClass() {
        assertTrue(AccessPolicy.canManageClass(AppRole.SUPER_ADMIN, "admin", publishedClass))
    }

    @Test fun teacherCanManageOnlyOwnClass() {
        assertTrue(AccessPolicy.canManageClass(AppRole.TEACHER, "teacher-a", publishedClass))
        assertFalse(AccessPolicy.canManageClass(AppRole.TEACHER, "teacher-b", publishedClass))
    }

    @Test fun anotherTeacherCanReadPublishedButNotUnpublishedClass() {
        assertTrue(AccessPolicy.canReadClass(AppRole.TEACHER, "teacher-b", publishedClass))
        assertFalse(AccessPolicy.canReadClass(AppRole.TEACHER, "teacher-b", publishedClass.copy(published = false)))
    }

    @Test fun studentNeedsPublicPublicationOrActiveMembership() {
        assertTrue(AccessPolicy.canReadClass(AppRole.STUDENT, "student-a", publishedClass))
        assertFalse(AccessPolicy.canReadClass(AppRole.STUDENT, "student-a", publishedClass.copy(public = false)))
        assertTrue(AccessPolicy.canReadClass(AppRole.STUDENT, "student-a",
            publishedClass.copy(public = false, activeMembership = true)))
    }

    @Test fun guestCannotReadPrivateClass() {
        assertFalse(AccessPolicy.canReadClass(AppRole.GUEST, null, publishedClass.copy(public = false)))
    }
}
