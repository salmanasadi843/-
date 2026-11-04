package com.example.data.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Response

class DarsyarReadOnlyRepositoryTest {
    @Test
    fun snapshotUsesBearerTokenAndReturnsAccountAndClasses() = runBlocking {
        val fake = FakeApi()
        val result = DarsyarReadOnlyRepository(fake).loadSnapshot("firebase-token")

        assertEquals("Bearer firebase-token", fake.authorizationForMe)
        assertEquals("Bearer firebase-token", fake.authorizationForClasses)
        assertEquals("internal-user-1", result.account.userId)
        assertEquals(1, result.classes.size)
        assertEquals("Public class", result.classes.single().title)
    }

    @Test
    fun teacherFolderOperationsUseBearerTokenAndBackendIds() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)
        val folders = repository.loadTeacherFolders("folder-token")
        assertEquals("Bearer folder-token", fake.authorizationForFolders)
        assertEquals("folder-1", folders.single().id)

        val request = BackendTeacherFolderWriteRequest(name = "پوشه جدید", sortOrder = 3)
        val created = repository.createTeacherFolder("folder-token", request)
        assertEquals("Bearer folder-token", fake.authorizationForCreateFolder)
        assertEquals("پوشه جدید", fake.createdFolderRequest?.name)
        assertEquals("folder-created", created.id)

        val updated = repository.updateTeacherFolder("folder-token", "folder-1", request.copy(name = "اصلاح‌شده"))
        assertEquals("Bearer folder-token", fake.authorizationForUpdateFolder)
        assertEquals("folder-1", fake.updatedFolderId)
        assertEquals("اصلاح‌شده", fake.updatedFolderRequest?.name)
        assertEquals("folder-1", updated.id)

        repository.deleteTeacherFolder("folder-token", "folder-1")
        assertEquals("Bearer folder-token", fake.authorizationForDeleteFolder)
        assertEquals("folder-1", fake.deletedFolderId)
    }

    @Test
    fun blankFolderArgumentsAreRejectedBeforeRequest() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.loadTeacherFolders(" ") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.updateTeacherFolder("token", " ", BackendTeacherFolderWriteRequest(name = "x")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.deleteTeacherFolder("token", " ") }
        }
        assertEquals(null, fake.authorizationForFolders)
        assertEquals(null, fake.authorizationForUpdateFolder)
        assertEquals(null, fake.authorizationForDeleteFolder)
    }

    @Test
    fun teacherDirectoryUsesBearerTokenAndReturnsOnlyApiDisplayNames() = runBlocking {
        val fake = FakeApi()
        val teachers = DarsyarReadOnlyRepository(fake).loadTeachers("fresh-token")
        assertEquals("Bearer fresh-token", fake.authorizationForTeachers)
        assertEquals("استاد الف", teachers.single().displayName)
        assertEquals("teacher-1", teachers.single().userId)
    }

    @Test
    fun blankTokenForTeacherDirectoryIsRejectedBeforeRequest() = runBlocking {
        val fake = FakeApi()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DarsyarReadOnlyRepository(fake).loadTeachers("  ") }
        }
        assertEquals(null, fake.authorizationForTeachers)
    }

    @Test
    fun coursesAndSessionsUseBearerTokenAndSelectedClassId() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)

        assertEquals(emptyList<BackendCourseDto>(), repository.loadCourses("fresh-token", "class-42"))
        assertEquals(emptyList<BackendSessionSummaryDto>(), repository.loadSessions("fresh-token", "class-42"))

        assertEquals("Bearer fresh-token", fake.authorizationForCourses)
        assertEquals("Bearer fresh-token", fake.authorizationForSessions)
        assertEquals("class-42", fake.requestedClassIdForCourses)
        assertEquals("class-42", fake.requestedClassIdForSessions)

        val detail = repository.loadSessionDetail("fresh-token", "class-42", "session-7")
        assertEquals("full transcript", detail.transcript)
        assertEquals("Bearer fresh-token", fake.authorizationForSessionDetail)
        assertEquals("class-42", fake.requestedClassIdForSessionDetail)
        assertEquals("session-7", fake.requestedSessionIdForSessionDetail)
    }

    @Test
    fun sessionMoveAndReorderUseBearerTokenAndExactIdentifiers() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)

        val reordered = repository.reorderSession("fresh-token", "session-7", "up")
        assertEquals("session-7", reordered.id)
        assertEquals("Bearer fresh-token", fake.authorizationForReorder)
        assertEquals("session-7", fake.reorderedSessionId)
        assertEquals("up", fake.reorderRequest?.direction)

        val moved = repository.moveSession("admin-token", "session-7", "class-target")
        assertEquals("session-7", moved.id)
        assertEquals("class-target", moved.classId)
        assertEquals("Bearer admin-token", fake.authorizationForMove)
        assertEquals("session-7", fake.movedSessionId)
        assertEquals("class-target", fake.moveRequest?.targetClassId)
    }


    @Test
    fun classReorderUsesBearerTokenAndExactClassId() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)
        val result = repository.reorderClass("fresh-token", "class-42", "up")

        assertEquals(listOf("class-42", "class-43"), result.map { it.id })
        assertEquals("Bearer fresh-token", fake.authorizationForClassReorder)
        assertEquals("class-42", fake.reorderedClassId)
        assertEquals("up", fake.classReorderRequest?.direction)

        val movedDown = repository.reorderClass("fresh-token", "class-42", "down")
        assertEquals(listOf("class-42", "class-43"), movedDown.map { it.id })
        assertEquals("Bearer fresh-token", fake.authorizationForClassReorder)
        assertEquals("class-42", fake.reorderedClassId)
        assertEquals("down", fake.classReorderRequest?.direction)
    }

    @Test
    fun invalidClassReorderArgumentsAreRejectedBeforeRequest() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.reorderClass("token", "class-42", "sideways") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.reorderClass(" ", "class-42", "up") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.reorderClass("token", " ", "up") }
        }

        assertEquals(null, fake.authorizationForClassReorder)
    }

    @Test
    fun invalidSessionMoveAndReorderArgumentsAreRejectedBeforeRequest() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.reorderSession("token", "session-7", "sideways") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.reorderSession(" ", "session-7", "up") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.moveSession("token", " ", "class-target") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.moveSession("token", "session-7", " ") }
        }

        assertEquals(null, fake.authorizationForReorder)
        assertEquals(null, fake.authorizationForMove)
    }

    @Test
    fun blankSessionDetailArgumentsAreRejectedBeforeRequest() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.loadSessionDetail("token", "class-42", " ") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.loadSessionDetail(" ", "class-42", "session-7") }
        }
        assertEquals(null, fake.authorizationForSessionDetail)
    }

    @Test
    fun blankFirebaseTokenIsRejectedBeforeAnyRequest() = runBlocking {
        val fake = FakeApi()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DarsyarReadOnlyRepository(fake).loadSnapshot("  ") }
        }
        assertEquals(null, fake.authorizationForMe)
        assertEquals(null, fake.authorizationForClasses)
        assertEquals(null, fake.authorizationForTeachers)
        assertEquals(null, fake.authorizationForCourses)
        assertEquals(null, fake.authorizationForSessions)
        assertEquals(null, fake.authorizationForSessionDetail)
    }

    @Test
    fun teacherApplicationEndpointsUseBearerTokenAndExactDecision() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)

        val submitted = repository.submitTeacherApplication(
            "student-token",
            BackendTeacherApplicationWriteRequest(teachingField = "فقه", institutionName = "حوزه")
        )
        assertEquals("Bearer student-token", fake.authorizationForSubmitApplication)
        assertEquals("فقه", fake.submittedApplicationRequest?.teachingField)
        assertEquals("pending", submitted.approvalStatus)

        val pending = repository.loadPendingTeacherApplications("admin-token")
        assertEquals("Bearer admin-token", fake.authorizationForPendingApplications)
        assertEquals("teacher-2", pending.single().userId)

        val approved = repository.decideTeacherApplication("admin-token", "teacher-2", "approved")
        assertEquals("Bearer admin-token", fake.authorizationForDecision)
        assertEquals("teacher-2", fake.decidedUserId)
        assertEquals("approved", fake.decisionRequest?.decision)
        assertEquals("approved", approved.approvalStatus)
    }

    @Test
    fun teacherApplicationRejectsBlankTokenDetailsAndInvalidDecisionBeforeRequest() = runBlocking {
        val fake = FakeApi()
        val repository = DarsyarReadOnlyRepository(fake)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.submitTeacherApplication(" ", BackendTeacherApplicationWriteRequest(teachingField = "فقه")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.submitTeacherApplication("token", BackendTeacherApplicationWriteRequest()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.loadPendingTeacherApplications(" ") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.decideTeacherApplication("token", "teacher-2", "invalid") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.decideTeacherApplication("token", " ", "approved") }
        }
        assertEquals(null, fake.authorizationForSubmitApplication)
        assertEquals(null, fake.authorizationForPendingApplications)
        assertEquals(null, fake.authorizationForDecision)
    }

    private class FakeApi : DarsyarReadOnlyApi {
        var authorizationForSubmitApplication: String? = null
        var submittedApplicationRequest: BackendTeacherApplicationWriteRequest? = null
        var authorizationForPendingApplications: String? = null
        var authorizationForDecision: String? = null
        var decidedUserId: String? = null
        var decisionRequest: BackendTeacherApplicationDecisionRequest? = null
        var authorizationForMe: String? = null
        var authorizationForClasses: String? = null
        var authorizationForTeachers: String? = null
        var authorizationForFolders: String? = null
        var authorizationForCreateFolder: String? = null
        var authorizationForUpdateFolder: String? = null
        var authorizationForDeleteFolder: String? = null
        var createdFolderRequest: BackendTeacherFolderWriteRequest? = null
        var updatedFolderId: String? = null
        var updatedFolderRequest: BackendTeacherFolderWriteRequest? = null
        var deletedFolderId: String? = null
        var authorizationForCourses: String? = null
        var authorizationForSessions: String? = null
        var requestedClassIdForCourses: String? = null
        var requestedClassIdForSessions: String? = null
        var authorizationForSessionDetail: String? = null
        var requestedClassIdForSessionDetail: String? = null
        var requestedSessionIdForSessionDetail: String? = null
        var authorizationForClassReorder: String? = null
        var reorderedClassId: String? = null
        var classReorderRequest: BackendReorderClassRequest? = null
        var authorizationForReorder: String? = null
        var reorderedSessionId: String? = null
        var reorderRequest: BackendReorderSessionRequest? = null
        var authorizationForMove: String? = null
        var movedSessionId: String? = null
        var moveRequest: BackendMoveSessionRequest? = null

        override suspend fun getPublicClasses(): Response<List<BackendClassDto>> = Response.success(emptyList())

        override suspend fun getPublicSessions(classId: String): Response<List<BackendSessionSummaryDto>> =
            Response.success(emptyList())

        override suspend fun getPublicSessionDetail(
            classId: String,
            sessionId: String
        ): Response<BackendSessionDto> = Response.success(
            BackendSessionDto(
                id = sessionId, classId = classId, courseId = null, title = "Public session",
                courseName = "", professorName = "", dateMs = null, audioDurationMs = 0,
                transcript = "public transcript", originalTranscript = "public original",
                aiSummary = null, aiKeyPoints = null, aiQuizJson = null, tags = "",
                isPublished = true, lastEditedMs = null, sortOrder = 0, version = 1
            )
        )

        override suspend fun getMe(authorization: String): Response<BackendMeDto> {
            authorizationForMe = authorization
            return Response.success(BackendMeDto("internal-user-1", listOf("student")))
        }

        private fun folder(id: String, name: String) = BackendTeacherFolderDto(
            id = id, ownerUserId = "teacher-1", parentFolderId = null,
            name = name, sortOrder = 0, isHidden = false, version = 1
        )

        override suspend fun getTeacherFolders(authorization: String): Response<List<BackendTeacherFolderDto>> {
            authorizationForFolders = authorization
            return Response.success(listOf(folder("folder-1", "پوشه")))
        }

        override suspend fun createTeacherFolder(
            authorization: String,
            request: BackendTeacherFolderWriteRequest
        ): Response<BackendTeacherFolderDto> {
            authorizationForCreateFolder = authorization
            createdFolderRequest = request
            return Response.success(folder("folder-created", request.name))
        }

        override suspend fun updateTeacherFolder(
            folderId: String,
            authorization: String,
            request: BackendTeacherFolderWriteRequest
        ): Response<BackendTeacherFolderDto> {
            authorizationForUpdateFolder = authorization
            updatedFolderId = folderId
            updatedFolderRequest = request
            return Response.success(folder(folderId, request.name))
        }

        override suspend fun deleteTeacherFolder(
            folderId: String,
            authorization: String
        ): Response<Unit> {
            authorizationForDeleteFolder = authorization
            deletedFolderId = folderId
            return Response.success(Unit)
        }

        override suspend fun getDeletedClasses(authorization: String): Response<List<BackendClassDto>> = Response.success(emptyList())

        override suspend fun deleteClass(classId: String, authorization: String): Response<Unit> = Response.success(Unit)

        override suspend fun restoreClass(classId: String, authorization: String): Response<Unit> = Response.success(Unit)

        override suspend fun updateClass(
            classId: String,
            authorization: String,
            request: BackendClassWriteRequest
        ): Response<BackendClassDto> = Response.success(
            BackendClassDto(
                id = classId, ownerUserId = "teacher-1", title = request.title,
                description = request.description, term = request.term,
                visibility = request.visibility, isPublished = request.isPublished,
                isHidden = request.isHidden, sortOrder = request.sortOrder, folderId = request.folderId
            )
        )


        override suspend fun reorderClass(
            classId: String,
            authorization: String,
            request: BackendReorderClassRequest
        ): Response<List<BackendClassDto>> {
            authorizationForClassReorder = authorization
            reorderedClassId = classId
            classReorderRequest = request
            return Response.success(
                listOf(
                    BackendClassDto(
                        id = classId, ownerUserId = "teacher-1", title = "Reordered class",
                        description = "", term = "", visibility = "private",
                        isPublished = true, isHidden = false, sortOrder = 0, folderId = null
                    ),
                    BackendClassDto(
                        id = "class-43", ownerUserId = "teacher-1", title = "Sibling class",
                        description = "", term = "", visibility = "private",
                        isPublished = true, isHidden = false, sortOrder = 1, folderId = null
                    )
                )
            )
        }

        override suspend fun updateSession(
            sessionId: String,
            authorization: String,
            request: BackendSessionWriteRequest
        ): Response<BackendSessionDto> = Response.success(
            BackendSessionDto(
                id = sessionId, classId = "class-1", courseId = request.courseId,
                title = request.title, courseName = request.courseName, professorName = request.professorName,
                dateMs = request.dateMs, audioDurationMs = request.audioDurationMs,
                transcript = request.transcript, originalTranscript = request.originalTranscript,
                aiSummary = request.aiSummary, aiKeyPoints = request.aiKeyPoints, aiQuizJson = request.aiQuizJson,
                tags = request.tags, isPublished = request.isPublished, lastEditedMs = request.lastEditedMs,
                sortOrder = request.sortOrder, version = 1
            )
        )

        override suspend fun getDeletedSessions(authorization: String): Response<List<BackendSessionSummaryDto>> =
            Response.success(emptyList())

        override suspend fun deleteSession(sessionId: String, authorization: String): Response<Unit> =
            Response.success(Unit)

        override suspend fun restoreSession(sessionId: String, authorization: String): Response<Unit> =
            Response.success(Unit)

        override suspend fun reorderSession(
            sessionId: String,
            authorization: String,
            request: BackendReorderSessionRequest
        ): Response<BackendSessionDto> {
            authorizationForReorder = authorization
            reorderedSessionId = sessionId
            reorderRequest = request
            return Response.success(
            BackendSessionDto(
                id = sessionId, classId = "class-1", courseId = null, title = "Reordered",
                courseName = "", professorName = "", dateMs = null, audioDurationMs = 0,
                transcript = "", originalTranscript = "", aiSummary = null, aiKeyPoints = null,
                aiQuizJson = null, tags = "", isPublished = true, lastEditedMs = null,
                sortOrder = 0, version = 1
            )
            )
        }

        override suspend fun moveSession(
            sessionId: String,
            authorization: String,
            request: BackendMoveSessionRequest
        ): Response<BackendSessionDto> {
            authorizationForMove = authorization
            movedSessionId = sessionId
            moveRequest = request
            return Response.success(
            BackendSessionDto(
                id = sessionId, classId = request.targetClassId, courseId = null, title = "Moved",
                courseName = "", professorName = "", dateMs = null, audioDurationMs = 0,
                transcript = "", originalTranscript = "", aiSummary = null, aiKeyPoints = null,
                aiQuizJson = null, tags = "", isPublished = true, lastEditedMs = null,
                sortOrder = 0, version = 1
            )
            )
        }

        override suspend fun submitTeacherApplication(
            authorization: String,
            request: BackendTeacherApplicationWriteRequest
        ): Response<BackendTeacherApplicationDto> {
            authorizationForSubmitApplication = authorization
            submittedApplicationRequest = request
            return Response.success(BackendTeacherApplicationDto("student-1", "", null,
                request.teachingField, request.institutionName, "pending"))
        }

        override suspend fun getPendingTeacherApplications(
            authorization: String
        ): Response<List<BackendTeacherApplicationDto>> {
            authorizationForPendingApplications = authorization
            return Response.success(listOf(BackendTeacherApplicationDto(
                "teacher-2", "استاد دوم", "teacher@example.test", "فقه", null, "pending"
            )))
        }

        override suspend fun decideTeacherApplication(
            userId: String,
            authorization: String,
            request: BackendTeacherApplicationDecisionRequest
        ): Response<BackendTeacherApplicationDto> {
            authorizationForDecision = authorization
            decidedUserId = userId
            decisionRequest = request
            return Response.success(BackendTeacherApplicationDto(
                userId, "استاد دوم", "teacher@example.test", "فقه", null, request.decision
            ))
        }

        override suspend fun getTeachers(authorization: String): Response<List<BackendTeacherDto>> {
            authorizationForTeachers = authorization
            return Response.success(listOf(BackendTeacherDto("teacher-1", "استاد الف")))
        }

        override suspend fun getCourses(classId: String, authorization: String): Response<List<BackendCourseDto>> {
            authorizationForCourses = authorization
            requestedClassIdForCourses = classId
            return Response.success(emptyList())
        }

        override suspend fun getSessions(classId: String, authorization: String): Response<List<BackendSessionSummaryDto>> {
            authorizationForSessions = authorization
            requestedClassIdForSessions = classId
            return Response.success(emptyList())
        }

        override suspend fun getSessionDetail(
            classId: String,
            sessionId: String,
            authorization: String
        ): Response<BackendSessionDto> {
            authorizationForSessionDetail = authorization
            requestedClassIdForSessionDetail = classId
            requestedSessionIdForSessionDetail = sessionId
            return Response.success(
                BackendSessionDto(
                    id = sessionId, classId = classId, courseId = null, title = "Selected session",
                    courseName = "", professorName = "", dateMs = null, audioDurationMs = 0,
                    transcript = "full transcript", originalTranscript = "original",
                    aiSummary = null, aiKeyPoints = null, aiQuizJson = null, tags = "",
                    isPublished = true, lastEditedMs = null, sortOrder = 0, version = 1
                )
            )
        }

        override suspend fun getClasses(authorization: String): Response<List<BackendClassDto>> {
            authorizationForClasses = authorization
            return Response.success(
                listOf(
                    BackendClassDto(
                        id = "class-1",
                        ownerUserId = "teacher-1",
                        title = "Public class",
                        description = "",
                        term = "1405",
                        visibility = "public",
                        isPublished = true,
                        isHidden = false,
                        sortOrder = 0,
                        folderId = null
                    )
                )
            )
        }
    }
}
