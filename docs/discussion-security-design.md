# Darsyar discussion feature: data and security contract

## Status
Foundation only. These models do not enable Firestore reads/writes or add UI.
Do not merge a discussion UI until the rules and repository are implemented and tested.

## Collections
- `discussion_threads/{threadId}`: one question and its class/course/session references.
- `discussion_threads/{threadId}/posts/{postId}`: replies and follow-ups.
- `discussion_threads/{threadId}/history/{historyId}`: append-only edit history.

Canonical scope fields:
- `teacherUid`: owner of the class materials.
- `classId`: teacher's original local class ID, serialized as a string.
- `courseId`, `lectureId`: optional original local IDs, serialized as strings.
- Do not use the student's negative Room cache IDs as cloud identifiers.

## Access rules required before activation
1. Require Firebase Authentication for every read/write.
2. Resolve the current user's role from a trusted profile; never trust client-supplied `authorRole`.
3. Only permit access while the referenced class is published by `teacherUid`; if hidden, block reading and writing the associated thread and posts.
4. Only the thread author may edit the question. Only a post's author may edit that post. The teacher may change the thread status but may not silently rewrite other users' text.
5. Every edit must atomically append an immutable history record. Ordinary clients may not delete history.
6. Validate that thread, post, and history scope fields match their parent documents. A client must not choose a different `teacherUid` or `classId` to bypass access checks.
7. A user's profile update must not permit role escalation. Current registration lets the client choose a role; a trusted teacher approval/claim strategy is required before tightening profile creation rules without breaking teacher registration.
8. Do not rely on UI filtering as authorization. Add Firestore Rules Emulator tests for unauthorized reads/writes, hidden classes, cross-class access, role escalation, author-only edits, and immutable history.

## Initial UI behavior
- Status values: `UNANSWERED`, `ANSWERED`, `RESOLVED`.
- Resolved threads remain readable to authorized class participants and are not deleted.
- Questions can optionally link to a course, session, and a selected text excerpt.
- Class-code membership is intentionally out of scope for the first implementation, per product decision; access follows the teacher's publication state.


## Repository security audit (2026-10-14)

Audit scope: current `firestore.rules`, `AuthPreferences.kt`, and `CloudMaterialsSync.kt`.
This is a source review, not an Emulator test or a live Firebase penetration test.

### Findings — do not enable discussion writes yet

- **High: profile role can be self-assigned.** The recursive `/users/{userId}/{document=**}` rule grants the signed-in owner read/write access to their profile path. In Rules v2 the recursive wildcard can match zero or more path segments, so the root profile document is covered too. Registration writes the client-selected `role` to `users/{uid}`. A student may therefore attempt to change their profile role to `TEACHER`; rules that trust this field do not prove teacher status.
- **High: shared-material documents are not role-gated.** Any authenticated user can create/update their own `shared_materials/{uid}` document. The student sync code reads other users' documents and interprets their payload as teacher materials without verifying the owner's trusted role. A student account can therefore publish a forged payload under its own UID and have it appear as shared content to other clients.
- **High: published state is only inside a client-produced JSON string.** `CloudMaterialsSync` serializes classes and lessons into the `payload` string. Firestore Rules cannot inspect JSON fields inside that string to independently enforce `isPublished`; rules currently authorize the whole document based on UID alone.
- **Medium: legacy `shared/{shareId}` is publicly readable.** `allow read: if true` exposes every document matched by that path to unauthenticated readers. Confirm this is intentional and that no private data is stored there before changing it.
- **Positive: discussion paths currently have no matching allow rule.** Firestore's default is deny, so the new discussion collections are not enabled by these rules yet. Keep them denied until trusted role and publication checks are available.
- **No canonical publication registry exists in the inspected code.** Class visibility currently comes from Room fields serialized by the client; there is no separate trusted per-class publication document that rules can check.
- **Teacher onboarding constraint:** registration currently lets the user choose `TEACHER` or `STUDENT`, and the existing primary-teacher account is specially recognized by email in app code. Do not add rules that simply require `users/{uid}.role == 'TEACHER'` and call that secure. A safe migration needs a trusted teacher-approval mechanism (for example, Admin SDK-managed custom claims or an explicitly admin-managed approval field) and must preserve the current account's access.

### Required next implementation sequence

1. Add tests using the Firestore Rules Emulator before changing deployed rules. Include: anonymous read of legacy shares; student profile role escalation; student writing a fake shared-material payload; hidden/unpublished class access; cross-teacher access; author-only question/post edits; teacher-only status changes; immutable history.
2. Design a trusted teacher identity migration that does not lock out the current teacher. Client code must not be allowed to set the trusted role/approval field.
3. Replace the JSON-only publication signal with per-class canonical publication documents, written only through a trusted path and checked by rules. Define how unpublish blocks existing discussion reads/writes, not just how the UI hides a class.
4. Add discussion collection rules and repository operations only after the preceding checks are covered by automated tests.
5. Deploy rules only after Emulator tests pass and the current backup, login, and published-material flows are regression-tested.

### Deployment status

No Firestore rules were changed by this audit. No live rules were deployed. No Emulator tests or APK build have been run for this audit. The findings above are based on the repository source only.
