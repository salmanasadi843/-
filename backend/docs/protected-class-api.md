# Protected class API (staging foundation)

All endpoints below are under `/api/v1`. Except for health and version
discovery, requests require a Firebase ID token in the HTTP Bearer header.
The backend verifies the token with Firebase Admin (including revocation
checking), resolves an already-linked identity, and loads application roles
from PostgreSQL. Client-supplied roles are never accepted.

## Class endpoints

### `GET /api/v1/classes`

Returns classes the authenticated user may see:

- Super admin: all classes, including unpublished and hidden classes.
- Class owner: all of their own classes, including unpublished and hidden.
- Approved teacher: published, non-hidden classes from other teachers (read-only).
- Student: published, non-hidden public classes and classes with an active membership.
- A user with no application role can only see classes they own (normally none).

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

## Session endpoints

The canonical content hierarchy is **teacher → class → session**. A session
always belongs directly to a class. Its optional `courseId` is only a grouping
inside that same class; it does not replace or sit between the class and session
relationship. Optional teacher folders are also only class organization metadata.

### `GET /api/v1/classes/{classId}/sessions`

Returns sessions visible to the authenticated caller in the specified class.
Super admins and class owners can see unpublished sessions. Other teachers can
read published sessions in published, non-hidden classes. Students can read
published sessions in public classes or classes where they have an active
membership. Sessions in other teachers' classes remain read-only.

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

Audio source records have a schema foundation, but dedicated audio-source
management endpoints are not exposed yet.

## Audit and transaction guarantees

Class and session creation/update, together with their corresponding `audit_log`
entries, commit in one PostgreSQL transaction. If the audit insert fails, the
class write is rolled back. Updates lock the target class row before checking
ownership and changing it.

The integration suite checks visibility for admin/teacher/student, ownership
protection against another teacher, admin cross-teacher updates, and audit
record creation against a disposable PostgreSQL schema.

## Deliberate limitations

- No class deletion endpoint is exposed yet.
- Course CRUD, session deletion, audio-source management, access-code redemption,
  and membership administration are not implemented by this API yet.
- This is a backend staging foundation. It is not deployed, does not receive
  production traffic, and is not connected to the Android app or live Firebase
  data.
