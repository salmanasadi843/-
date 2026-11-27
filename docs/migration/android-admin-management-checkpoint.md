# Android cross-teacher administration — implementation checkpoint

Branch: `architecture/shared-backend-and-web`
Last reviewed: 2026-11-03
Verified head before this UI integration: `347873c6338919662f2c420398b6fe96e92be9a0`. UI integration commits are recorded in the PR branch history; check the current PR head and its own CI before treating this checkpoint as verified.

## Verified implementation (do not rebuild)

- `BackendBrowseScreen` is reachable from the Android app navigation and obtains a fresh Firebase ID token before calling the backend.
- The screen loads the backend account roles, teacher directory, classes, and teacher folders.
- Its hierarchy is **استاد → کلاس → جلسه**.
- Super-admin UI controls are present for managing foreign teachers' folders/classes/sessions, including class/session edit, delete/recovery, session reorder, and session move. Ordinary teachers' controls are owner-scoped.
- The Android API contract already includes `GET /api/v1/teachers`, class/session mutation methods, folder methods, reorder, and move. Do not recreate these endpoints or controls.
- Backend routes use authenticated server-side roles. Class authorization tests cover student denial, teacher ownership restrictions, super-admin owner selection, and teacher-application moderation restrictions.
- Backend teacher application repository supports submit, approve, reject, and revoke, grants/removes the teacher role, and writes audit records.
- Web UI already submits teacher applications, lists pending applications for admins, and exposes approve/reject actions.
- The main Android `HomeScreen` still contains the older Firebase `teacher_requests` approval flow. Do not mistake it for the Backend queue; it has not been migrated or merged with Backend state.
- A separate `BackendTeacherApplicationsPanel` is now embedded in `BackendBrowseScreen`. It submits applications through `/api/v1/teacher-applications`; when `/api/v1/me` returns `super_admin`, it loads the pending Backend queue and exposes approve/reject actions. It obtains fresh Firebase ID tokens through `AuthPreferences` and relies on Backend authorization. The panel explicitly tells users that its queue is separate from the legacy Firebase queue.
- The legacy Firebase flow remains unchanged as a compatibility path. Before cutover, define a one-time review/migration policy for outstanding legacy requests; do not silently mirror or merge the two queues.
- Existing Room classes and Firebase private teacher backups remain separate; do not broaden Firestore rules or migrate local data as part of this task.

## Remaining work (only these gaps)

1. [x] Add Android API methods and DTOs for teacher application submission, listing pending applications, and admin decisions using the existing authenticated Firebase ID token; repository tests are present.
2. [x] Add a distinct Backend-backed Android panel for teacher application submission and super-admin moderation; admin controls are rendered only after the authenticated Backend snapshot returns `super_admin`.
3. [ ] Define the one-time transition policy for outstanding legacy Firebase `teacher_requests`; the two queues remain intentionally separate.
4. [ ] Add Compose/UI tests and run real-account tests for student, teacher, and super-admin behavior; verify server authorization remains authoritative.
5. [ ] Run Android and Backend CI on the latest UI integration commit; earlier CI success on the pre-integration head does not validate these changes.

## Latest verification

- Previous verified head: `2f1906f0a73121c77b4910d878931fd5223ed0ce` (before this UI integration).
- The current UI integration must be verified against the latest PR head; do not carry forward the earlier green CI results.
- Backend `TeacherAdministrationRepository.decideApplication` explicitly accepts `approved`, `rejected`, and `revoked`; the Android repository's allowed values match it.
- PR #39 remains open and Draft. No deployment, migration, or merge was performed.


- The Android panel now hides the application form for accounts that already have the `teacher` role, avoiding a predictable conflict when an approved teacher opens the panel. This is a presentation guard only; Backend authorization remains authoritative.

## Verification update — 2026-11-03

- Head `77cda64a34bfa596da33e413d2e0f4f74fad4ae4`: Android APK, web build, legacy inventory validator, and Backend CI all completed successfully. Backend CI included PostgreSQL integration, backup/restore, first super-admin bootstrap, guarded Firebase identity linking, and liveness/API-version checks.
- Follow-up UI guard commit: `64db5d60ff75a140bdf725fb7c84659cfd023c7b`. Its Android, web, inventory, and Backend workflows have been triggered but are still queued/in progress at the time of this checkpoint update. Do not mark this head green until those runs finish.
- No live-account role test has been performed. No legacy queue was migrated, no server was moved, and no deployment or PR merge was performed.

## Acceptance criteria

- Admin can review and approve/reject teacher applications through the backend from Android and web; revocation remains available through the backend API but is not exposed in this pending-application panel.
- Ordinary teachers and students cannot inspect or decide applications.
- Super-admin authorization comes from backend roles, never from a client-side email or locally asserted role.
- Existing teacher/class/session management in `BackendBrowseScreen` remains intact and is not reimplemented.
- Existing Room data and private Firebase backups remain unchanged.
- No server migration, Firebase project change, deployment, or PR merge without explicit approval.
