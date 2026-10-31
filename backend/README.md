# Darsyar Backend — staging foundation

This is a standalone Kotlin/Ktor service, isolated from the Android Gradle modules.
Run Gradle with `-p backend`; do not add this as an Android module.

## Included in this first slice

- Ktor/Netty HTTP service.
- PostgreSQL connection pool and Flyway migrations.
- Liveness and database-readiness endpoints.
- Initial schema preserving teacher → optional folder → class → optional course → session.
- Tested role policy: primary admin can manage all teacher classes; a teacher can manage
  only their own classes and read other teachers' published classes; students need a
  public publication or active membership.

Authentication and protected CRUD endpoints are deliberately not enabled yet. The
Firebase ID-token verifier and server-side role lookup must be implemented before
protected routes. Client-supplied role names are never trusted.

## Run locally with Docker

From repository root:

```bash
docker compose -f backend/compose.yaml up --build
```

Check `http://localhost:8080/health/live`, `/health/ready`, and `/api/v1`.

Stop with:

```bash
docker compose -f backend/compose.yaml down
```

Add `-v` only if you also want to delete the local development database volume.
It contains test data only.

## Run tests without Docker

```bash
gradle -p backend test
gradle -p backend installDist
```

Set `DB_URL`, `DB_USER`, and `DB_PASSWORD` through environment variables for an
isolated staging database. Never commit production credentials.

## Safety boundary

This service is not connected to Firebase production, the Iranian server, the Android
app, or real user data. Do not point it at a production database.
