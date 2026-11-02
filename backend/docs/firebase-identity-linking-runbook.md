# Firebase identity linking runbook

## Purpose

This procedure links one already verified Firebase UID to one already imported
Darsyar application user. It is intentionally operator-controlled and does not
provide public registration, account discovery, automatic provisioning, or role
granting.

## Non-negotiable identity checks

Before running the script, an authorized operator must independently verify:

1. The exact Firebase UID from the trusted Firebase Authentication console.
2. The target internal `app_users.id` from the approved import/mapping report.
3. That both records refer to the same person, using an approved account-recovery
   or identity-verification process.

An email address, display name, phone number, matching class title, or a user's
claim alone is not sufficient. Email is not copied into the identity record by
this script and is not marked verified.

## Preconditions

- Use only an isolated staging database for rehearsal.
- The target application user must already exist and have status `active`.
- The target user must not already have a Firebase identity.
- The Firebase UID must not already be linked to any application user.
- A verified backup must exist before any real operational linking batch.
- Never point this script at Firebase production or the Iranian production
  server as part of the current staging work.

## Staging procedure

Set the PostgreSQL connection using the standard `PGHOST`, `PGPORT`,
`PGDATABASE`, `PGUSER`, and `PGPASSWORD` variables. Do not put passwords
in command-line arguments or Git.

```bash
export LINK_FIREBASE_UID='exact-uid-copied-from-trusted-firebase-console'
export LINK_TARGET_USER_ID='existing-app-users-uuid-from-approved-mapping'
export CONFIRM_FIREBASE_IDENTITY_LINK='I_VERIFIED_UID_AND_TARGET_ACCOUNT'
./backend/scripts/link-existing-firebase-identity.sh
```

The database also enforces at most one Firebase identity per internal user
with a partial unique index. Before applying that migration to any database that
already contains imported identity rows, check for duplicates:

```sql
SELECT user_id, count(*)
FROM auth_identities
WHERE provider = 'firebase'
GROUP BY user_id
HAVING count(*) > 1;
```

Resolve any returned rows through an independently reviewed account-mapping
process; never delete or merge identities automatically. The operation runs in
one transaction under a PostgreSQL advisory lock. It rejects a missing/inactive target, a Firebase UID already linked elsewhere, or
a target account that already has a Firebase identity. It writes an audit event
in the same transaction. If any check fails, the transaction is not committed.

The script does not create an `app_users` row, merge two users, import email,
grant student/teacher/admin roles, or change Firebase Auth. A linked user
receives only the roles already recorded in `app_roles`; teacher access still
requires an approved teacher profile.

## After linking

Verify the exact provider/subject-to-user mapping and audit event in staging.
Then authenticate with a Firebase ID token for that UID and confirm
`GET /api/v1/me` returns the intended internal user and only the expected
database roles.

For batch operations, use a reviewed mapping file with one-to-one UID and
internal-user pairs, keep an immutable copy of the approved mapping, and record
counts and exceptions. Do not infer mappings by email or fuzzy matching.
Unresolved or disputed accounts remain unlinked and quarantined until their
owners are verified.

## Rollback

Do not delete an identity row casually. If a mapping is wrong, stop using the
account, preserve the audit trail, take a backup, and have an administrator
review the correction. Any unlink/relink operation needs a separate reviewed
procedure that preserves the original audit evidence and checks for dependent
sessions or account activity.

## Current safety boundary

This runbook and script are for staging validation only. No production Firebase
identity, Android account, Firestore document, Room database, Iranian server,
DNS record, or user data has been modified or linked by creating these files.
