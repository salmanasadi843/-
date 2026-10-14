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
