# Darsyar shared content core

## Goal

Keep the existing Android class/course/session hierarchy and Room records. Add a platform-neutral publication and access layer that can be used by Android and a future Darsyar website. The Android app and website must use the same Firebase project, authentication identities, publication records, and membership records.

## Current state (audited on 2026-10-30)

- Android stores the working copy in Room.
- Teacher-owned cloud backup is stored under `teacher_data/{uid}/records`.
- Student/secondary-teacher feed currently reads the legacy `shared_materials` aggregate.
- `ClassEntity.isPublished` is the effective class-level visibility control; individual lesson visibility also exists.
- The repository has an older, unmerged canonical-publication implementation in historical branches. It is not assumed to be safe to transplant as-is.
- Firebase is shared with Yadno. Do not broaden or replace rules globally; all new paths must be Darsyar-specific.
- Firebase Hosting is not configured yet.

## Target Firestore contract

All paths below are Darsyar-specific and must be covered by emulator tests before production deployment.

### Public catalogue

`darsyar_public_classes/{classPublicId}`

Required fields:
- `schemaVersion: 1`
- `ownerUid: string`
- `sourceClassId: string` (legacy Room ID retained as source metadata, not public identity)
- `title: string`
- `teacherName: string`
- `visibility: "PUBLIC"`
- `status: "PUBLISHED" | "WITHDRAWN"`
- `createdAt`, `updatedAt`

`darsyar_public_lessons/{lessonPublicId}`

Required fields:
- `schemaVersion: 1`
- `ownerUid: string`
- `classPublicId: string`
- `sourceLessonId: string`
- `status: "PUBLISHED" | "WITHDRAWN"`
- `content`: structured lesson fields (transcript, original transcript, summary, key points, tags, audio URL, duration, date, ordering)
- `updatedAt`

Public read is allowed only when the record is published and its parent public class is published. Anonymous website visitors may read these public records. Private source records and teacher backups remain private.

### Private classes and membership

`darsyar_private_classes/{classPublicId}`
- `ownerUid`, source IDs, descriptive metadata, `visibility: "PRIVATE"`, `status`, `inviteCodeHash`, optional expiry/capacity, timestamps.
- Never store a plaintext invite code.

`darsyar_class_memberships/{classPublicId}_{uid}`
- `classPublicId`, `studentUid`, `status: "ACTIVE" | "REVOKED"`, `joinedAt`, `grantedBy`.

Invite-code verification must be performed by a trusted server endpoint/Cloud Function with rate limiting. The client must not be allowed to query hashes or enumerate private classes. A successful code entry creates a membership tied to the authenticated UID. Private lesson reads require owner, trusted admin, or active membership.

## Stable identifiers

Do not expose Room auto-increment IDs as the cross-platform public identity. Introduce immutable random public IDs for class and lesson publication records. Preserve existing Room IDs and all current records during migration. Store source IDs only as migration/link metadata. Never deduplicate classes solely by title/teacher; same-named classes can be distinct.

## Authorization model

- Teacher writes only their own publication records.
- Admin authority is verified from a trusted server-managed claim/registry, never from a client-editable profile field alone.
- Anonymous users read only explicitly public published content.
- Authenticated students read public content and private content only with an active membership.
- Other teachers read public content; they do not gain edit/delete rights over another teacher's source material.
- Admin moderation and cross-teacher management must be implemented with a trusted authorization path and audited separately.
- Existing Yadno paths and behavior must remain unchanged.

## Safe rollout sequence

1. Add and test the new Darsyar-specific rules and data contract in the Firestore Emulator. Do not deploy rules yet.
2. Add teacher-side dual-write to the new public catalogue while preserving the existing student feed. This keeps old APKs functioning during rollout.
3. Verify parity between Room source data, private teacher backup, and canonical publication records using two teacher accounts and a student account.
4. Move Android student/teacher feed reads to the canonical records. Keep a compatibility window for old APKs; do not revoke legacy reads until adoption is confirmed.
5. Add private-class invite verification and membership using a trusted server endpoint, then test owner/member/non-member/admin access.
6. Configure Firebase Hosting and website auth only after the shared contract and rules pass. Hosting deployment must be separate from Firestore rules deployment.

## Explicit non-goals for this foundation

- Do not change the existing teacher/class/session UI hierarchy.
- Do not rewrite Room records or renumber local IDs.
- Do not automatically make existing classes public or private without an explicit migration decision.
- Do not deploy Firestore rules or change Yadno rules as part of an Android APK commit.
- Do not remove legacy `shared_materials` reads until the compatibility rollout is complete.
