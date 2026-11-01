#!/usr/bin/env bash
set -Eeuo pipefail

# Operator-only linking of a previously verified Firebase UID to an already
# imported application user. This does not create users or grant roles.
: "${LINK_FIREBASE_UID:?Set LINK_FIREBASE_UID to the independently verified Firebase UID}"
: "${LINK_TARGET_USER_ID:?Set LINK_TARGET_USER_ID to the existing app_users UUID}"

if [[ "${LINK_FIREBASE_UID}" =~ [[:space:]] ]] || [[ -z "${LINK_FIREBASE_UID}" ]]; then
  echo "Firebase UID must be non-empty and contain no whitespace." >&2
  exit 2
fi

if [[ ! "${LINK_TARGET_USER_ID}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
  echo "LINK_TARGET_USER_ID must be an existing application-user UUID." >&2
  exit 2
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "PostgreSQL client (psql) is required." >&2
  exit 2
fi

cat <<'WARNING'
WARNING: This links one Firebase UID to one existing Darsyar application user.
Before continuing, independently verify BOTH:
  1. The Firebase UID belongs to the person who controls the intended account.
  2. The target application-user UUID belongs to that same person.
Email, display name, phone number, and matching text are NOT sufficient proof.
This script does not create accounts, merge accounts, or grant application roles.
WARNING

if [[ "${CONFIRM_FIREBASE_IDENTITY_LINK:-}" != "I_VERIFIED_UID_AND_TARGET_ACCOUNT" ]]; then
  echo "Set CONFIRM_FIREBASE_IDENTITY_LINK=I_VERIFIED_UID_AND_TARGET_ACCOUNT to continue." >&2
  exit 2
fi

psql -X -v ON_ERROR_STOP=1 \
  -v firebase_uid="${LINK_FIREBASE_UID}" \
  -v target_user_id="${LINK_TARGET_USER_ID}" <<'SQL'
BEGIN;
SELECT pg_advisory_xact_lock(74190520261102);

SELECT EXISTS (
    SELECT 1 FROM app_users
    WHERE id = :'target_user_id'::uuid AND status = 'active'
) AS target_is_active \gset
\if :target_is_active
\else
  \echo 'Refusing link: target application user does not exist or is not active.'
  \quit 3
\endif

SELECT EXISTS (
    SELECT 1 FROM auth_identities
    WHERE provider = 'firebase' AND provider_subject = :'firebase_uid'
) AS firebase_uid_already_linked \gset
\if :firebase_uid_already_linked
  \echo 'Refusing link: this Firebase UID is already linked to an application account.'
  \quit 3
\endif

SELECT EXISTS (
    SELECT 1 FROM auth_identities
    WHERE provider = 'firebase' AND user_id = :'target_user_id'::uuid
) AS target_already_has_firebase \gset
\if :target_already_has_firebase
  \echo 'Refusing link: target account already has a Firebase identity.'
  \quit 3
\endif

INSERT INTO auth_identities(
    user_id, provider, provider_subject, email, email_verified
)
VALUES (
    :'target_user_id'::uuid, 'firebase', :'firebase_uid', NULL, false
);

INSERT INTO audit_log(
    actor_user_id, action, entity_type, entity_id, owner_user_id, after_data
)
VALUES (
    NULL,
    'identity.firebase.linked_by_operator',
    'app_user',
    :'target_user_id'::uuid,
    :'target_user_id'::uuid,
    jsonb_build_object(
        'provider', 'firebase',
        'providerSubject', :'firebase_uid',
        'verification', 'independently_verified_operator',
        'emailImported', false
    )
);

COMMIT;
\echo 'Firebase identity linked successfully. No roles were created or changed.'
SQL
