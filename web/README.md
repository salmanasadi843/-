# Darsyar Web (staging MVP in progress)

Responsive Persian RTL web client for the Darsyar staging backend.

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

Build verification: `npm run build`. GitHub Actions runs the same production build on web changes.

## Implemented in this increment

- Firebase email/password sign-in using the existing identity provider.
- Reads the signed-in user's role, teacher directory, visible classes, session summaries and selected session detail.
- Displays the hierarchy teacher → class → session.
- Teacher and super-admin can create classes; super-admin must select the target teacher.
- Teacher can create sessions in their own classes; super-admin can create sessions in any class returned by the backend.
- Class visibility can be set to public or private at creation.
- Admins can browse classes returned by the backend; ordinary users see only what the backend authorizes.
- Backend remains the authority for all permission checks; UI visibility is not treated as a security boundary.

## Not implemented yet

- Editing, hiding, deleting and restoring classes/sessions from the web UI.
- Managing teacher folders and course records.
- Anonymous (not signed-in) public class/session browsing. Current protected API requires Firebase sign-in even for public classes.
- Full sync with Android Room/Firestore, migration, production deployment, account creation and password reset.

Never add Firebase Admin credentials or other private secrets to `.env.local` or any `VITE_*` variable; Vite variables are bundled into browser code. The Firebase values above are browser app public configuration, not a service-account credential. The backend URL is centralized in `src/api.ts`; do not hardcode hostnames in UI components.

This is a staging client only. It is not connected to production data and does not authorize server deployment or migration.
