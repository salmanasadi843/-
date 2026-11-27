# Protected class API (staging foundation)

All endpoints below are under `/api/v1`. Except for health and version
discovery, requests require a Firebase ID token in the HTTP Bearer header.
The backend verifies the token with Firebase Admin (including revocation
checking), resolves an already-linked identity, and loads application roles
from PostgreSQL. Client-supplied roles are never accepted.

## Class endpoints

New classes can use only two visibility modes: `public` and `private`. The legacy database value `unlisted` is retained for existing records during compatibility; it is treated as restricted content and cannot be selected for new class creation or updates.

### `GET /api/v1/classes`

Returns classes the authenticated user may see:

- Super admin: all classes, including unpublished and hidden classes.
- Class owner: all of their own classes, including unpublished and hidden.
- Any authenticated account, including students and users without a teaching role: published, non-hidden public classes without joining or creating a membership.
- Student: the same public classes, plus published, non-hidden private classes with an active membership.
- Another approved teacher: public classes from other teachers are read-only; private classes are not disclosed unless that account also has the student role and an active membership.
- Class owner and super admin retain their management visibility, including private, hidden, and unpublished classes.

### `POST /api/v1/classes`

Creates a class for an approved teacher or a super admin.

Example teacher request:

```json
{
  "title": "Usul al-Fiqh",
  "description": "Course description",
  "term": "1405",
  "visibility": "private",
  "isPublished": false
}
```

A teacher is always assigned as the owner of their own new class. Supplying a
different `ownerUserId` is rejected. A super admin must supply an existing
`ownerUserId` to create content on behalf of another account.

### `PATCH /api/v1/classes/{classId}`

Updates the class metadata and publication/visibility flags. A teacher may
update only a class whose owner ID matches their authenticated account.
A super admin may update any class. A published class belonging to another
teacher remains read-only for ordinary teachers.

The owner ID is immutable through this endpoint. The request's optional
`ownerUserId` field is ignored for updates; ownership transfer is not exposed.

### Class deletion and recovery

- `DELETE /api/v1/classes/{classId}`: soft-deletes a class; only its owner or super admin may do so.
- `POST /api/v1/classes/{classId}/restore`: restores the class using the same authorization rule.
- `GET /api/v1/classes/deleted`: lists deleted classes only for their owner or super admin, so they can be selected for recovery.
- Deletion does not delete or rewrite courses, sessions, transcripts, AI outputs, audio-source metadata, access codes, memberships, or student state. Those rows remain attached to the same class IDs.
- A deleted class and its nested content are hidden from normal class/course/session listings and private-code redemption. New course/session/audio-source writes against it are rejected until restored.
- Repeating delete or restore is idempotent. State changes increment the class version and write an audit record in the same PostgreSQL transaction.


## Teacher folder endpoints

### `GET /api/v1/teacher-folders`

Returns non-deleted folders the caller may see. Owners and super admins can
see their full folder trees. Other approved teachers see folders containing
published, non-hidden classes. Students see folders containing published,
non-hidden public classes or classes for which they have an active membership.

### `POST /api/v1/teacher-folders`

Creates a folder. Teachers are always assigned as owner of their own folder.
A super admin must provide `ownerUserId` when creating a folder for another
teacher. Optional `parentFolderId` must belong to the same owner.

### `PATCH /api/v1/teacher-folders/{folderId}`

Renames, hides/unhides, reorders, or moves a folder within the same owner's
folder tree. The API rejects moving a folder beneath itself or one of its
descendants. Cross-owner moves are rejected by the database's composite
foreign key.

### `DELETE /api/v1/teacher-folders/{folderId}`

Soft-deletes an empty folder. Deletion is rejected while it still contains
child folders or classes; those items must be moved explicitly first.
Folder mutations write audit records in the same transaction.

## Course endpoints

Courses are optional groupings inside a class; sessions remain directly owned by the class.

- `GET /api/v1/classes/{classId}/courses`: lists courses in a class the caller can see.
- `POST /api/v1/classes/{classId}/courses`: creates a course for the class owner or super admin.
- `PATCH /api/v1/courses/{courseId}`: updates course title, teacher name, description, date and order; the class relationship is immutable.
- `DELETE /api/v1/courses/{courseId}`: soft-deletes an empty course only. Deletion is rejected while non-deleted sessions still reference it.

Course writes are audited transactionally. The database's composite foreign key ensures a session cannot reference a course from another class.

## Session endpoints

The canonical content hierarchy is **teacher → class → session**. A session
always belongs directly to a class. Its optional `courseId` is only a grouping
inside that same class; it does not replace or sit between the class and session
relationship. Optional teacher folders are also only class organization metadata.

### `GET /api/v1/classes/{classId}/sessions`

Returns sessions visible to the authenticated caller in the specified class.
Super admins and class owners can see unpublished sessions. Any authenticated
account can read published sessions in published, non-hidden public classes
without membership. Students can additionally read published sessions in
private classes where they have an active membership. Other teachers can read
public classes from other teachers but cannot see their private classes unless
they also have student role and active membership.

### `POST /api/v1/classes/{classId}/sessions`

Creates a session directly under the class. Only the class owner (approved
teacher) or super admin can create it. A supplied `courseId` must refer to a
course belonging to the same class; the database enforces this relationship.

The request and response preserve the original transcript, edited transcript,
AI summary, key points, quiz JSON, tags, date, audio duration, publication
state, and optional course grouping.

### `PATCH /api/v1/sessions/{sessionId}`

Updates session content and metadata. Only the class owner or super admin may
update a session. The class relationship and owner cannot be changed through
this endpoint. Every create/update writes a session audit record in the same
transaction.

### Session deletion and recovery

- `DELETE /api/v1/sessions/{sessionId}`: soft-deletes a session; only its class owner or super admin may do so.
- `POST /api/v1/sessions/{sessionId}/restore`: restores a soft-deleted session for the same authorized roles.
- Deleted sessions are omitted from normal session listings, but their full content, course association, and audio-source metadata remain stored. No related record is cascaded away.
- Repeating delete or restore is safe and does not create duplicate audit entries. Actual state changes increment the session version and write an audit record in the same transaction.

### Audio source endpoints

Audio sources are separate records attached to a session; a session may have
multiple ordered sources. The API supports `direct_url`, `hosted_page`,
`external_link`, and `uploaded_asset` kinds.

- `GET /api/v1/sessions/{sessionId}/audio-sources`: lists active sources when the caller can see the session.
- `POST /api/v1/sessions/{sessionId}/audio-sources`: creates a source; only the class owner or super admin may write.
- `PATCH /api/v1/sessions/{sessionId}/audio-sources/{sourceId}`: updates URL/storage key/title/order.
- `DELETE /api/v1/sessions/{sessionId}/audio-sources/{sourceId}`: deactivates the source; it does not delete the underlying object.

External links must be absolute HTTP(S) URLs without embedded credentials.
Uploaded assets use an opaque `storageKey` and must not expose a provider URL.
The backend does not accept local device file paths and does not upload, move,
or delete binary files. A future storage adapter can resolve storage keys to
the currently configured provider without changing session records. Every
audio-source write and deactivation is audited transactionally.

## Private class access and membership

- `POST /api/v1/classes/{classId}/access-codes`: the class owner or super admin creates a code for a private class. Optional `expiresAtMs` and `maxUses` limits can be supplied.
- `GET /api/v1/classes/{classId}/access-codes`: the class owner or super admin lists code metadata, including expiry, use limit, usage count, active state, and creation time; the secret code is never returned.
- `DELETE /api/v1/access-codes/{codeId}`: the class owner or super admin deactivates a code.
- `POST /api/v1/class-memberships/redeem`: a student redeems a code and receives active membership.
- `GET /api/v1/classes/{classId}/memberships`: the class owner or super admin lists membership records.
- `DELETE /api/v1/classes/{classId}/memberships/{studentUserId}`: the class owner or super admin revokes an active membership; the record is retained with `revoked` status.

The generated code is returned only once. PostgreSQL stores only its SHA-256 hash. Listing codes returns metadata only and includes inactive records for management history; it never returns the raw code or its hash. Codes are checked under a row lock, so concurrent redemptions respect usage limits. Repeating a redemption by a student who already has active membership is idempotent and does not consume another use. Codes work only for published private classes; public/unlisted classes do not need this mechanism. Every code creation/deactivation and membership activation/revocation is audited in the same transaction. Revocation does not delete membership history; a later valid code redemption can reactivate the membership.

The API supports owner/admin access-code listing/deactivation and membership listing/revocation. Student-facing membership history and UI integration remain pending.

## Teacher applications and approval

- `POST /api/v1/teacher-applications`: an authenticated account submits or updates its own pending teacher application. At least one of `teachingField` or `institutionName` is required. The caller cannot choose a user ID or grant a role.
- `GET /api/v1/admin/teacher-applications`: super-admin only; lists pending applications.
- `PATCH /api/v1/admin/teacher-applications/{userId}`: super-admin only; accepts `{"decision":"approved"}`, `rejected`, or `revoked`.

Approval updates `teacher_profiles` and grants the database `teacher` role in the same transaction, recording the approving admin and an audit event. Rejection/revocation removes the teacher role; the authentication repository also exposes the teacher role only while the profile is approved. A rejected application may be resubmitted. Approved or revoked profiles cannot be resubmitted through the self-service endpoint. No client can link Firebase identities or grant roles, and no arbitrary role-management endpoint is exposed.

## First super-admin bootstrap\n\nThe HTTP API intentionally has no public registration, identity-linking, or role-grant endpoint. The first super-admin is created only by the operator-run `backend/scripts/bootstrap-first-super-admin.sh` against an already migrated database. The operator must independently verify the Firebase UID. The script is one-time, refuses to proceed when any super-admin or the supplied Firebase identity already exists, serializes concurrent attempts with a PostgreSQL advisory transaction lock, and atomically inserts the application user, Firebase identity, `super_admin` role, and audit event. Email is optional metadata and is not marked verified by the script. Subsequent Firebase identity linking remains a separate, reviewed operator-controlled workflow; teacher applications and approval are handled by the authenticated endpoints below.\n\n## Audit and transaction guarantees

Class and session creation/update, together with their corresponding `audit_log`
entries, commit in one PostgreSQL transaction. If the audit insert fails, the
class write is rolled back. Updates lock the target class row before checking
ownership and changing it.

The integration suite checks visibility for admin/teacher/student, ownership
protection against another teacher, admin cross-teacher updates, and audit
record creation against a disposable PostgreSQL schema.

## Deliberate limitations

- Binary audio upload/storage-provider adapters and student-facing membership history/UI integration remain pending.
- Secure linking of existing Firebase identities remains an operator-controlled workflow; the teacher application/approval workflow is available through the API but is not yet integrated into Android UI. Student-facing membership history/UI integration and binary audio upload/storage-provider adapters remain pending.
- This is a backend staging foundation. It is not deployed, does not receive
  production traffic, and is not connected to the Android app or live Firebase
  data.
