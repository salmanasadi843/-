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

-- psql does not substitute variables inside a dollar-quoted DO block.
-- Store the safely quoted values as transaction-local settings first.
SELECT set_config('darsyar.link.firebase_uid', :'firebase_uid', true);
SELECT set_config('darsyar.link.target_user_id', :'target_user_id', true);

DO $link$
DECLARE
    v_uid text := current_setting('darsyar.link.firebase_uid');
    v_user_id uuid := current_setting('darsyar.link.target_user_id')::uuid;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM app_users
        WHERE id = v_user_id AND status = 'active'
    ) THEN
        RAISE EXCEPTION 'Refusing link: target application user does not exist or is not active.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM auth_identities
        WHERE provider = 'firebase' AND provider_subject = v_uid
    ) THEN
        RAISE EXCEPTION 'Refusing link: this Firebase UID is already linked to an application account.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM auth_identities
        WHERE provider = 'firebase' AND user_id = v_user_id
    ) THEN
        RAISE EXCEPTION 'Refusing link: target account already has a Firebase identity.';
    END IF;

    INSERT INTO auth_identities(
        user_id, provider, provider_subject, email, email_verified
    ) VALUES (
        v_user_id, 'firebase', v_uid, NULL, false
    );

    INSERT INTO audit_log(
        actor_user_id, action, entity_type, entity_id, owner_user_id, after_data
    ) VALUES (
        NULL,
        'identity.firebase.linked_by_operator',
        'app_user',
        v_user_id,
        v_user_id,
        jsonb_build_object(
            'provider', 'firebase',
            'providerSubject', v_uid,
            'verification', 'independently_verified_operator',
            'emailImported', false
        )
    );
END
$link$;

COMMIT;
\echo 'Firebase identity linked successfully. No roles were created or changed.'
SQL
