#!/usr/bin/env bash
set -Eeuo pipefail

# One-time, operator-controlled bootstrap. This script never provisions from an
# HTTP endpoint and never grants roles based on client-supplied claims.
: "${BOOTSTRAP_FIREBASE_UID:?Set BOOTSTRAP_FIREBASE_UID to the verified Firebase UID}"
: "${BOOTSTRAP_ADMIN_EMAIL:=}"
: "${BOOTSTRAP_ADMIN_NAME:=Darsyar Primary Administrator}"

if [[ "${BOOTSTRAP_FIREBASE_UID}" =~ [[:space:]] ]] || [[ -z "${BOOTSTRAP_FIREBASE_UID}" ]]; then
  echo "Firebase UID must be non-empty and contain no whitespace." >&2
  exit 2
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "PostgreSQL client (psql) is required." >&2
  exit 2
fi

cat <<'WARNING'
WARNING: This creates the FIRST super-admin in the database selected by your
PG* environment variables / DATABASE_URL. Use only after independently
verifying the Firebase UID belongs to the intended administrator.
The operation is one-time and refuses to run if a super-admin or this Firebase
identity already exists. No production database should be targeted casually.
WARNING

if [[ "${CONFIRM_SUPER_ADMIN_BOOTSTRAP:-}" != "I_HAVE_VERIFIED_THE_FIREBASE_UID" ]]; then
  echo "Set CONFIRM_SUPER_ADMIN_BOOTSTRAP=I_HAVE_VERIFIED_THE_FIREBASE_UID to continue." >&2
  exit 2
fi

psql "${DATABASE_URL:-}" \
  -X -v ON_ERROR_STOP=1 \
  -v firebase_uid="${BOOTSTRAP_FIREBASE_UID}" \
  -v admin_email="${BOOTSTRAP_ADMIN_EMAIL}" \
  -v display_name="${BOOTSTRAP_ADMIN_NAME}" <<'SQL'
BEGIN;

-- Serialize concurrent attempts, including attempts from separate operators.
SELECT pg_advisory_xact_lock(74190520261101);

SELECT EXISTS (
    SELECT 1 FROM app_roles WHERE role_name = 'super_admin'
) AS super_admin_exists \gset

\if :super_admin_exists
  \echo 'Refusing bootstrap: a super-admin already exists.'
  \quit 3
\endif

SELECT EXISTS (
    SELECT 1 FROM auth_identities
    WHERE provider = 'firebase' AND provider_subject = :'firebase_uid'
) AS identity_exists \gset

\if :identity_exists
  \echo 'Refusing bootstrap: this Firebase UID is already linked. Resolve it through a separately reviewed operator procedure.'
  \quit 4
\endif

INSERT INTO app_users(display_name, email)
VALUES (NULLIF(:'display_name', ''), NULLIF(:'admin_email', ''))
RETURNING id AS created_user_id \gset

INSERT INTO auth_identities(
    user_id, provider, provider_subject, email, email_verified
)
VALUES (
    :'created_user_id'::uuid,
    'firebase',
    :'firebase_uid',
    NULLIF(:'admin_email', ''),
    false
);

INSERT INTO app_roles(user_id, role_name)
VALUES (:'created_user_id'::uuid, 'super_admin');

INSERT INTO audit_log(
    actor_user_id, action, entity_type, entity_id, owner_user_id, after_data
)
VALUES (
    :'created_user_id'::uuid,
    'bootstrap.first_super_admin',
    'app_user',
    :'created_user_id'::uuid,
    :'created_user_id'::uuid,
    jsonb_build_object(
        'provider', 'firebase',
        'providerSubject', :'firebase_uid',
        'role', 'super_admin'
    )
);

COMMIT;

\echo 'First super-admin bootstrap completed. Keep the Firebase UID and database audit record for your operational records.'
\echo 'Created application user ID: ' :created_user_id
SQL
