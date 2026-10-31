# Darsyar Backend — portable staging foundation

This is a standalone Kotlin/Ktor service, isolated from the Android Gradle modules.
Run Gradle with `-p backend`; do not add this as an Android module.

## Included

- Ktor/Netty HTTP service and liveness/readiness endpoints.
- PostgreSQL connection pool and Flyway migrations.
- Schema preserving teacher → optional folder → class → optional course → session.
- Central role policy: primary admin can manage all teacher classes; a teacher manages
  only their own classes and can read other teachers' published classes; students need
  public publication or active membership.
- Provider-independent authentication boundary: verified external identity is mapped
  to an internal user, and roles are loaded from the server-side repository.
- Docker packaging and local PostgreSQL Compose environment.
- PostgreSQL backup/restore scripts with SHA-256 verification; restore refuses
  non-empty targets and never drops existing tables.
- CI tests, build, disposable PostgreSQL migration and HTTP health checks.

## Authentication status

The authentication boundary and unit tests are in place, but the Firebase ID-token
verifier, PostgreSQL identity/role repository, and protected HTTP routes are not yet
implemented or wired into Ktor. Until those pieces are complete, do not expose this
service as an authenticated production API. Client-supplied role names are never trusted.

## Run locally

From repository root:

```bash
docker compose -f backend/compose.yaml up --build
```

Check `http://localhost:8080/health/live`, `/health/ready`, and `/api/v1`.

## Backup and restore

See [the portability runbook](docs/portability-runbook.md). The scripts require
PostgreSQL client tools and environment variables. Test only with isolated databases.
The backup file and its manifest must be stored securely outside the source host.

## Safety boundary

This service is not connected to Firebase production, the Iranian server, Android, or
real user data. Do not point it at a production database. No migration is being performed.
