# Darsyar Backend — portable staging foundation

This is a standalone Kotlin/Ktor service, isolated from the Android Gradle modules.
Run Gradle with `-p backend`; do not add this as an Android module.

## Included

- Ktor/Netty HTTP service and liveness/readiness endpoints.
- PostgreSQL connection pool and Flyway migrations.
- Schema preserving the canonical teacher → class → session hierarchy. Optional folders
  categorize a teacher's classes; optional courses group sessions within a class.
- Central role policy: primary admin can manage all teacher classes; a teacher manages
  only their own classes and can read other teachers' published classes; students need
  public publication or active membership.
- Firebase Admin ID-token verification using Application Default Credentials.
- Provider-independent internal user identities and server-side PostgreSQL role lookup.
- Protected `GET /api/v1/me`, class list/create/update/soft-delete/recovery, teacher-folder list/create/update/soft-delete,
  and class-scoped course list/create/update/soft-delete, session list/create/update/soft-delete/recovery, protected audio-source management, and private-class access-code creation/listing/deactivation, redemption, and owner/admin membership listing/revocation; anonymous requests are rejected.
- Class visibility and ownership enforcement in PostgreSQL, including admin cross-teacher management.
- Transactional audit records for class, session, and folder mutations; folders support safe same-owner nesting, hide/reorder/rename/move, cycle prevention, and empty-folder soft deletion.
- Docker packaging and local PostgreSQL Compose environment.
- PostgreSQL backup/restore scripts with SHA-256 verification; restore refuses
  non-empty targets and never drops existing tables.
- Unit and PostgreSQL integration tests for identity mapping, teacher approval, class visibility, class and folder ownership, admin management, hierarchy safety, and audit writes; build, disposable migrations, backup/restore and HTTP checks.

## Authentication setup and limits

The protected profile route verifies Firebase ID tokens with revocation checking,
maps the verified Firebase UID through `auth_identities`, and loads roles from
`app_roles`. Teacher role is only granted to the principal when the corresponding
`teacher_profiles.approval_status` is `approved`. Token claims and client-supplied
roles are not used for authorization.

Configure `FIREBASE_PROJECT_ID` and Application Default Credentials
(`GOOGLE_APPLICATION_CREDENTIALS` for an isolated local service account, or workload
identity in a supported hosting environment). Never commit service-account keys.
Create/link application users and grant roles through a separately controlled
administrative process; this service intentionally does not auto-provision accounts.

The protected domain API now covers class and folder management plus class-scoped
session listing, creation, and content updates. Session deletion, binary audio upload/storage-provider adapters, student-facing membership history, administrative role-management
workflows, production secrets, and Firebase-to-user linking procedures still require
further implementation and review. See [the protected class API notes](docs/protected-class-api.md).
Do not expose this staging service as the production app backend yet.

## Run locally

From repository root:

```bash
docker compose -f backend/compose.yaml up --build
```

Check `http://localhost:8080/health/live`, `/health/ready`, and `/api/v1`.
The protected profile route requires a valid Firebase ID token in the Bearer header.

## Backup and restore

See [the portability runbook](docs/portability-runbook.md). The scripts require
PostgreSQL client tools and environment variables. Test only with isolated databases.
The backup file and its manifest must be stored securely outside the source host.

## Safety boundary

This service is not connected to Firebase production, the Iranian server, Android, or
real user data. Do not point it at a production database. No migration is being performed.
