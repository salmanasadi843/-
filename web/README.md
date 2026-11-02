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

## Implemented in this staging increment

- Firebase email/password sign-in using the existing identity provider.
- Role-aware teacher directory, visible class list and the hierarchy teacher → class → session.
- Create and edit classes, with public/private visibility and teacher-folder assignment.
- Nested teacher folders: create, choose parent, rename, hide/show, delete when allowed, and filter classes by folder.
- Create and edit sessions, preserving original transcript separately from edited transcript; show AI summary and key points.
- Optional courses inside a class, with create/edit/delete controls.
- Private class access codes: create, display once, copy, list and revoke.
- Student redemption of private-class codes and teacher/admin membership list with revocation.
- Soft deletion of classes and sessions, with recovery panels for authorized managers.
- Session audio sources: add direct audio URLs or hosted/external links, play direct URLs in the browser, and deactivate links without deleting the underlying file.
- Official class announcements: teachers/admins can publish notices to active class members; announcements appear in the class and generate in-app alerts.
- Class discussion forum: create class-wide or session-linked topics, threaded replies, pin/lock moderation for the responsible teacher/admin.
- In-app notification inbox: new-topic/reply notifications, user mentions via `@[user-uuid]`, unread count and mark-as-read.
- Super-admin cross-teacher content management; teacher controls are limited to their own classes by backend authorization.
- Backend remains the authority for all permission checks; UI visibility is not treated as a security boundary.

## Remaining work before product parity or deployment

- Public guest browsing is implemented for published, visible public classes and their published sessions through separate anonymous read-only API routes. Private, hidden, unpublished or deleted content remains excluded. The Android guest experience is not enabled yet; its current backend browse screen is an authenticated staging preview.
- Teacher application submission/review screens and account creation/password reset.
- Student-facing learning progress, bookmarks and personal session state.
- Search, transcript navigation/highlighting, notes/questions and AI study tools.
- Speech-to-text workflows and richer audio integration; this increment stores external links only and does not upload audio.
- Forum enhancements still pending: file attachments, richer user-mention picker, notification preferences and push delivery; current mentions use an explicit user UUID syntax. Topic/reply edit and delete controls are implemented in the staging clients.
- Full Android Room/Firestore synchronization and safe migration workflows.
- End-to-end tests against a disposable staging environment, security review and production operations runbook.

## Backend and hosting status

Firebase remains the current primary identity/data environment. The shared backend and web client are being developed as a staging layer; this work does not switch Android or web production traffic to that backend. The agreed future direction is to make a suitable Iranian server the primary host and retain Firebase as an independent backup, only after infrastructure, budget, recovery and migration checks are complete.

## Safety boundary

This is a staging client only. It is not yet a publicly deployed production website and does not authorize server deployment or migration. Firebase production, the current Iran server, DNS and existing user data remain unchanged.

Never add Firebase Admin credentials or other private secrets to `.env.local` or any `VITE_*` variable; Vite variables are bundled into browser code. The Firebase values above are browser app public configuration, not a service-account credential. The backend URL is centralized in `src/api.ts`; do not hardcode hostnames in UI components.
