# Client endpoint configuration

The backend endpoint is a deployment setting, not an identity or data-migration setting. Changing it must not change Firebase project IDs, local Room data, user IDs, or role assignments.

## Android

The Android app reads the optional Gradle property/environment variable `DARSYAR_API_BASE_URL` into `BuildConfig.DARSYAR_API_BASE_URL`.

Build with a deployment-specific URL:

```bash
./gradlew assembleDebug -PDARSYAR_API_BASE_URL=https://api.example.org/
```

Or set the `DARSYAR_API_BASE_URL` environment variable in the build environment. Do not commit a real server address to source control. The URL must use HTTPS and must not contain credentials, query parameters, or fragments.

If the value is absent, the backend endpoint remains unconfigured. No request is made and existing Firebase/Room behavior is unchanged. The current app does not yet call this endpoint; protected API integration is a later, separately tested phase.

## Web client convention

When the web client is created, use a build-time environment variable named `DARSYAR_API_BASE_URL` and pass it through one central API-client factory. Do not scatter hostnames through components. The actual variable syntax (for example, Vite's `import.meta.env`) must follow the web framework selected for that site. Never put service credentials or private keys in browser environment variables.

## Moving hosts

For a future host change, update the deployment configuration for Android and web, validate `/health/ready` and `/api/v1`, then use the documented backup/restore and cutover runbook. Do not change clients to a new host until the target database and file storage have been restored and verified.

This configuration alone does not deploy the backend, connect production data, or perform a migration.