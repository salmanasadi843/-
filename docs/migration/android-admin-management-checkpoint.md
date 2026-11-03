# Android cross-teacher administration — implementation checkpoint

Branch: `architecture/shared-backend-and-web`
Last reviewed: 2026-11-03

## Verified implementation (do not rebuild)

- `BackendBrowseScreen` is reachable from the Android app navigation and obtains a fresh Firebase ID token before calling the backend.
- The screen loads the backend account roles, teacher directory, classes, and teacher folders.
- Its hierarchy is **استاد → کلاس → جلسه**.
- Super-admin UI controls are present for managing foreign teachers' folders/classes/sessions, including class/session edit, delete/recovery, session reorder, and session move. Ordinary teachers' controls are owner-scoped.
- The Android API contract already includes `GET /api/v1/teachers`, class/session mutation methods, folder methods, reorder, and move. Do not recreate these endpoints or controls.
- Backend routes use authenticated server-side roles. Class authorization tests cover student denial, teacher ownership restrictions, super-admin owner selection, and teacher-application moderation restrictions.
- Backend teacher application repository supports submit, approve, reject, and revoke, grants/removes the teacher role, and writes audit records.
- Web UI already submits teacher applications, lists pending applications for admins, and exposes approve/reject actions.
- The main Android `HomeScreen` still contains the older Firebase `teacher_requests` approval flow. The Android backend API contract currently has no methods for `/api/v1/teacher-applications` or `/api/v1/admin/teacher-applications`. This is a real cross-platform integration gap, not a missing backend feature.
- Existing Room classes and Firebase private teacher backups remain separate; do not broaden Firestore rules or migrate local data as part of this task.

## Remaining work (only these gaps)

1. [x] Add Android API methods and DTOs for teacher application submission, listing pending applications, and admin decisions using the existing authenticated Firebase ID token. Repository tests were added; CI result is pending.
2. Integrate those calls into Android UI without removing or corrupting existing local data. Show admin approval controls only when backend `/api/v1/me` confirms `super_admin`.
3. Decide and document how the legacy Firebase `teacher_requests` flow is handled during transition; do not silently mix Firebase and backend approval states.
4. Add API/repository and UI tests for student, teacher, and super-admin behavior; verify server authorization remains authoritative.
5. Re-run Android and backend CI.

## Acceptance criteria

- Admin can review and approve/reject/revoke teacher applications through the backend from Android and web.
- Ordinary teachers and students cannot inspect or decide applications.
- Super-admin authorization comes from backend roles, never from a client-side email or locally asserted role.
- Existing teacher/class/session management in `BackendBrowseScreen` remains intact and is not reimplemented.
- Existing Room data and private Firebase backups remain unchanged.
- No server migration, Firebase project change, deployment, or PR merge without explicit approval.
