# Darsyar administrative Functions

This codebase adds server-authorized operations for the primary Darsyar administrator.
It does not deploy itself and does not modify live Firestore data or security rules.

## Operations

- `adminUpdateTeacherRecord`: updates an allowlisted set of fields in a teacher's private schema-v2 backup.
- `adminDeleteTeacherRecord`: deletes a lecture, an unused course, or a class with its course/lecture records.
- Both operations require the existing primary administrator UID, matching email, and a verified email token.
- The target must still have a TEACHER profile and an enabled/approved `approved_teachers/{uid}` document.
- Original record ownership is preserved. IDs, ownerUid, classId, courseId, and folder relationships cannot be changed through the update endpoint.
- Each successful operation creates an `admin_audit` record. Admin SDK access bypasses client Firestore rules; no broader client rule is introduced.

## Build and tests

```sh
cd functions
npm install
npm test
```

## Deployment is intentionally separate

Deploying requires a Firebase project with Cloud Functions enabled (typically the Blaze billing plan), a configured billing account, and review of the target project and region.

After review and explicit approval only:

```sh
firebase deploy --only functions:darsyar-admin
```

Do not deploy these functions as part of an Android APK build. The current Android app does not call these endpoints yet; a later client integration and emulator/integration tests are required before the admin UI can use them.
