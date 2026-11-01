# Darsyar Web (staging scaffold)

The first web increment is a responsive, Persian RTL, read-only browser for the existing Darsyar backend.

## Local setup

1. Install Node.js 22 or newer.
2. Copy `.env.example` to `.env.local`.
3. Fill the Firebase **Web App public configuration** for the existing Darsyar Firebase project:
   - `VITE_FIREBASE_API_KEY`
   - `VITE_FIREBASE_AUTH_DOMAIN`
   - `VITE_FIREBASE_PROJECT_ID`
   - `VITE_FIREBASE_APP_ID`
4. Set `VITE_DARSYAR_API_BASE_URL` to the HTTPS staging API URL. For local backend development, `http://localhost:8080` is acceptable.
5. Run `npm install`, then `npm run dev`.

Build verification: `npm run build`.

## Current scope

- Firebase email/password sign-in using the existing identity provider.
- Reads `/api/v1/me`, teachers, visible classes, session summaries and selected session detail.
- Displays the hierarchy teacher → class → session.
- Admins can browse all classes returned by the backend; ordinary users see only what the backend authorizes.
- No write operations, anonymous public browsing, account creation, password reset, migration or production deployment are enabled.

The backend remains the authority for all permissions. Never add Firebase Admin credentials or other private secrets to `.env.local` or any `VITE_*` variable; Vite variables are bundled into browser code.

## Configuration note

The Firebase values above are the browser app's public configuration, not a service-account credential. The backend URL is centralized in `src/api.ts`; do not hardcode hostnames in UI components.
