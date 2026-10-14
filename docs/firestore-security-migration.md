# Firestore security and publication migration

## Canonical publication model

- `published_classes/{teacherUid}__{classId}` contains one class record and its course metadata. The outer `isPublished` field is authoritative for Firestore read rules.
- `published_lessons/{teacherUid}__{lessonId}` contains one lesson payload. A lesson is readable only when its own `isPublished` flag is true and its parent class record is published.
- `publication_index/{teacherUid}` contains only hashes of withdrawn class identities, allowing the app to hide stale local imports without exposing hidden class names or transcripts.
- `shared_materials/{teacherUid}` remains as an owner-only legacy aggregate; students must read the canonical collections, not the opaque JSON string.
- Hiding a class/lesson changes publication flags and does not delete the teacher's local source data. Explicitly deleted classes/lessons are removed from the public canonical feed on the next sync.

## Security and rollout blockers — do not deploy yet

1. Verify the current primary teacher account, then create its `approved_teachers/{uid}` record through trusted Firebase Admin tooling. Client code cannot create this record.
2. Existing teacher profiles must have role `TEACHER` and a trusted approval record. A trusted teacher request/approval workflow is still needed.
3. Audit existing `shared_materials` documents and existing cloud data before deploying; students will no longer be allowed to read the opaque aggregate.
4. Deploy the new APK and rules together to a staging Firebase project. Older APKs still query `shared_materials` and will fail after the stricter read rule is deployed.
5. Test teacher login, backup, publish/unpublish at class and lesson level, student sync, stale-cache removal, reinstall/restore, and account recovery.
6. Decide whether legacy `shared/{shareId}` links should remain publicly readable and audit their contents.
7. Do not merge or deploy to production until Emulator tests, APK build, and two-account staging checks pass.

## Test status

The Firestore Emulator suite now includes tests for role escalation, approved teacher writes, private aggregate reads, published/hidden class visibility, lesson visibility, parent-class publication enforcement, and forged canonical records. CI results for this branch are pending.
No production Firestore rules have been deployed by this branch.
