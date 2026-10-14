# Firestore security migration — role and publication gate

## Proposed rule changes

- A new client-created `users/{uid}` profile must use role `STUDENT`.
- Profile updates cannot change the role field. Root profiles have a dedicated rule; the nested-user wildcard no longer grants root-profile writes.
- Teacher privileges require both a profile role of `TEACHER` and an `approved_teachers/{uid}` record.
- `approved_teachers` writes are intentionally denied to clients; provision approvals using trusted Firebase Admin tooling only.
- Only approved teachers can write their own `shared_materials/{uid}`, `teacher_data/{uid}`, and backup records.
- Discussion paths remain denied by default.
- Legacy `shared/{shareId}` remains publicly readable in this proposal pending a product/data review.

## Deployment blockers — do not deploy yet

1. Identify and verify the current primary teacher account, then create its `approved_teachers/{uid}` document using trusted Admin tooling before deploying. Do not let the client create this document.
2. Update teacher registration/onboarding so a client cannot create a `TEACHER` profile. Teacher requests need a trusted approval path; do not silently promote all existing role values.
3. Audit existing `shared_materials` documents and remove/repair any student-created or unapproved publication documents before deployment. The current student app queries the whole collection and Rules are not query filters.
4. Test teacher login, cloud backup, publish/unpublish, student sync, reinstall/restore, and account recovery against a staging Firebase project.
5. Decide whether legacy share links should remain public and check that they contain no private data.
6. Complete Android APK/build and app-flow regression checks before merging/deployment.

## Test status

- The first Emulator run (2026-10-14) exposed two confirmed failures in the old rules: profile role escalation and student-created shared-material documents.
- Updated rules and regression suite passed on 2026-10-14: **12 tests passed, 0 failed**. [GitHub Actions run](https://github.com/salmanasadi843/-/actions/runs/38574609056).
- The Emulator suite passing is not an Android app regression result. Teacher login, cloud backup, publish/unpublish, student sync, reinstall/restore, and account recovery still require staging verification.
- No production Firestore rules have been deployed by this change.
