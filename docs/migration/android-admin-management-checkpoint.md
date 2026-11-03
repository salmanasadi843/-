# Android cross-teacher administration — implementation checkpoint

Branch: `architecture/shared-backend-and-web`

## Verified current state

- Android `UserRole` has only `TEACHER` and `STUDENT`; the primary teacher is detected separately by Firebase email.
- `TeacherFoldersScreen` groups the Room classes and published-feed imports. It does not query the backend teacher directory or private class records.
- `ClassScreen` deliberately makes foreign classes read-only; class/session edit controls are owner-scoped.
- Firestore `teacher_data/{ownerUid}` and its `records` subcollection are owner-only. Do not broaden these rules to let the primary Firebase account read every teacher's private backup.
- Backend already has a server-side `SUPER_ADMIN` role, `AccessPolicy`, and admin-capable class operations. `GET /api/v1/classes` uses server roles; `GET /api/v1/teachers` returns the visible teacher directory.
- `BackendBrowseScreen` is currently a read-only server-backed preview. `BackendEndpointConfig` explicitly says the backend is optional and is not wired into Room/Firebase flows yet.

## Required implementation sequence

1. **Integrate authenticated backend API in Android** (Firebase ID token → backend; handle unavailable endpoint without damaging local data).
2. Add a super-admin-only teacher directory and teacher → classes → sessions navigation from backend records.
3. Add admin class/session edit, move, hide/unhide, and soft-delete/recovery actions; every mutation must use backend authorization/audit APIs.
4. Keep ordinary teachers read-only on other teachers' classes; never use Firestore rules or client-supplied role values as an admin bypass.
5. Add integration/UI tests for admin, owner-teacher, other-teacher, student, and guest behavior; then run Android + backend CI.

## Acceptance criteria

- Primary administrator can list and manage all teachers' classes and sessions through the backend.
- A teacher can manage only owned content and read published foreign content.
- Student/guest visibility follows public/private + membership rules.
- Existing Room data and private Firebase backups remain unchanged during backend outages.
- Hierarchy is consistently **استاد → کلاس → جلسه**.
