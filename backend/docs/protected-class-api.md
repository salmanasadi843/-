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

## Audit and transaction guarantees

Class creation and update, together with their corresponding `audit_log`
entries, commit in one PostgreSQL transaction. If the audit insert fails, the
class write is rolled back. Updates lock the target class row before checking
ownership and changing it.

The integration suite checks visibility for admin/teacher/student, ownership
protection against another teacher, admin cross-teacher updates, and audit
record creation against a disposable PostgreSQL schema.

## Deliberate limitations

- No class deletion endpoint is exposed yet.
- Course/session CRUD, folder management, access-code redemption, and membership
  administration are not implemented by this API yet.
- This is a backend staging foundation. It is not deployed, does not receive
  production traffic, and is not connected to the Android app or live Firebase
  data.
