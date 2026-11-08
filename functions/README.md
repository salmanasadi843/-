# Darsyar administrative Functions

This codebase adds server-authorized operations for the primary Darsyar administrator.
It does not deploy itself and does not modify live Firestore data or security rules.

## Operations

- `adminUpdateTeacherRecord`: updates an allowlisted set of fields in a teacher's private schema-v2 backup.
- `adminDeleteTeacherRecord`: deletes a lecture, an unused course, or a class with its course/lecture records.
- Both operations require the existing primary administrator UID, matching email, and a verified email token.
- The target must still have a TEACHER profile and an enabled/approved `approved_teachers/{uid}` document.
- Original record ownership is preserved. IDs, ownerUid, classId, courseId, and folder relationships cannot be changed through the update endpoint.
- Each successful operation creates an `admin_audit` record. A versioned `adminPending` marker prevents stale device uploads; after the teacher restores that exact version, the Android app republishes the updated shared-materials feed. A short, renewable `syncLockToken` lease serializes teacher backup batches against admin mutations; admin callables refuse to run while a backup lease is active, and failed partial uploads leave the lease to expire rather than exposing an incomplete snapshot to admin edits.
- Ordinary Android backups are merge-only: they update locally present records but do not delete cloud-only records merely because a phone's local database is incomplete. Hard deletion is reserved for the explicit admin callable until a durable local deletion-tombstone protocol exists.
- The Android primary-admin screen is integrated with these callable names, but it remains unavailable until the Functions codebase and the matching Firestore rules are deployed.

## Build and tests

```sh
cd functions
npm install
npm test
```

## Deployment is intentionally separate

Deploying requires the verified Firebase project, Cloud Functions enabled (typically the Blaze billing plan), a configured billing account, and review of the target project and region. Confirm the Firestore database location before choosing the Functions region; this branch currently specifies `europe-west1`, which must be checked against the actual project configuration. Firestore rules must be tested with the Emulator Suite before deployment.

After review and explicit approval only:

```sh
firebase deploy --only functions:darsyar-admin
```

Do not deploy these functions as part of an Android APK build. The current Android app does not call these endpoints yet; a later client integration and emulator/integration tests are required before the admin UI can use them.
