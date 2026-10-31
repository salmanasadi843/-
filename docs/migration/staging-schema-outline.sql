-- Darsyar staging schema design v2.
-- DESIGN ONLY: not executed against staging or production.
-- Requires PostgreSQL. UUIDs are destination identities; Room IDs remain in legacy_id_map.
-- Keep this schema aligned with Flyway migrations once backend implementation begins.

CREATE TABLE IF NOT EXISTS app_users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  display_name text NOT NULL DEFAULT '',
  email text,
  status text NOT NULL DEFAULT 'active'
    CHECK (status IN ('active','suspended','pending_deletion')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Identity is provider-neutral. Firebase UID is one external subject, not the app's primary key.
CREATE TABLE IF NOT EXISTS auth_identities (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  provider text NOT NULL,
  provider_subject text NOT NULL,
  email text,
  email_verified boolean NOT NULL DEFAULT false,
  linked_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (provider, provider_subject)
);

CREATE TABLE IF NOT EXISTS app_roles (
  user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  role_name text NOT NULL CHECK (role_name IN ('super_admin','teacher','student')),
  granted_at timestamptz NOT NULL DEFAULT now(),
  granted_by uuid REFERENCES app_users(id),
  PRIMARY KEY (user_id, role_name)
);

CREATE TABLE IF NOT EXISTS teacher_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  teacher_name text NOT NULL,
  teaching_field text NOT NULL,
  institution_name text,
  status text NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending','approved','rejected','revoked')),
  reviewed_by uuid REFERENCES app_users(id),
  reviewed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_teacher_requests_status_created
  ON teacher_requests(status, created_at);

CREATE TABLE IF NOT EXISTS teacher_profiles (
  user_id uuid PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
  approval_status text NOT NULL DEFAULT 'pending'
    CHECK (approval_status IN ('pending','approved','rejected','revoked')),
  teaching_field text,
  institution_name text,
  approved_at timestamptz,
  approved_by uuid REFERENCES app_users(id),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS teacher_folders (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_user_id uuid NOT NULL REFERENCES app_users(id),
  parent_folder_id uuid,
  name text NOT NULL,
  sort_order integer NOT NULL DEFAULT 0,
  is_hidden boolean NOT NULL DEFAULT false,
  version bigint NOT NULL DEFAULT 1,
  deleted_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, owner_user_id),
  FOREIGN KEY (parent_folder_id, owner_user_id)
    REFERENCES teacher_folders(id, owner_user_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_teacher_folder_root_name
  ON teacher_folders(owner_user_id, lower(name))
  WHERE parent_folder_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_teacher_folder_child_name
  ON teacher_folders(owner_user_id, parent_folder_id, lower(name))
  WHERE parent_folder_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS teaching_classes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_user_id uuid NOT NULL REFERENCES app_users(id),
  folder_id uuid,
  title text NOT NULL,
  teacher_name text NOT NULL DEFAULT '',
  description text NOT NULL DEFAULT '',
  term text NOT NULL DEFAULT '',
  class_date_ms bigint,
  visibility text NOT NULL DEFAULT 'private'
    CHECK (visibility IN ('public','private','unlisted')),
  is_published boolean NOT NULL DEFAULT false,
  is_hidden boolean NOT NULL DEFAULT false,
  sort_order integer NOT NULL DEFAULT 0,
  created_at_ms bigint,
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1,
  UNIQUE (id, owner_user_id),
  FOREIGN KEY (folder_id, owner_user_id)
    REFERENCES teacher_folders(id, owner_user_id)
);
CREATE INDEX IF NOT EXISTS ix_classes_owner_folder
  ON teaching_classes(owner_user_id, folder_id, sort_order);
CREATE INDEX IF NOT EXISTS ix_classes_public_listing
  ON teaching_classes(visibility, is_published, is_hidden);

CREATE TABLE IF NOT EXISTS teaching_courses (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  class_id uuid NOT NULL REFERENCES teaching_classes(id) ON DELETE CASCADE,
  title text NOT NULL,
  teacher_name text NOT NULL DEFAULT '',
  description text NOT NULL DEFAULT '',
  created_at_ms bigint,
  sort_order integer NOT NULL DEFAULT 0,
  version bigint NOT NULL DEFAULT 1,
  deleted_at timestamptz,
  UNIQUE (id, class_id)
);
CREATE INDEX IF NOT EXISTS ix_courses_class_order
  ON teaching_courses(class_id, sort_order);

CREATE TABLE IF NOT EXISTS teaching_sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  class_id uuid NOT NULL REFERENCES teaching_classes(id) ON DELETE CASCADE,
  course_id uuid,
  title text NOT NULL,
  course_name text NOT NULL DEFAULT '',
  professor_name text NOT NULL DEFAULT '',
  date_ms bigint,
  audio_duration_ms bigint NOT NULL DEFAULT 0,
  transcript text NOT NULL DEFAULT '',
  original_transcript text NOT NULL DEFAULT '',
  ai_summary text,
  ai_key_points text,
  ai_quiz_json jsonb,
  tags text NOT NULL DEFAULT '',
  is_published boolean NOT NULL DEFAULT true,
  last_edited_ms bigint,
  sort_order integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1,
  deleted_at timestamptz,
  FOREIGN KEY (course_id, class_id)
    REFERENCES teaching_courses(id, class_id)
);
CREATE INDEX IF NOT EXISTS ix_sessions_class_order
  ON teaching_sessions(class_id, sort_order, date_ms);
CREATE INDEX IF NOT EXISTS ix_sessions_course_order
  ON teaching_sessions(course_id, sort_order);

-- Audio references are separate from uploaded binaries. Room audioFilePath is device-local
-- and must never be copied into this table as if it were a portable URL.
CREATE TABLE IF NOT EXISTS session_audio_sources (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id uuid NOT NULL REFERENCES teaching_sessions(id) ON DELETE CASCADE,
  source_kind text NOT NULL
    CHECK (source_kind IN ('direct_url','hosted_page','external_link','uploaded_asset')),
  source_url text,
  storage_key text,
  title text,
  sort_order integer NOT NULL DEFAULT 0,
  is_active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (
    (source_kind = 'uploaded_asset' AND storage_key IS NOT NULL)
    OR
    (source_kind <> 'uploaded_asset' AND source_url IS NOT NULL)
  )
);

-- Per-user preferences must not leak through shared lesson records.
CREATE TABLE IF NOT EXISTS user_session_state (
  user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  session_id uuid NOT NULL REFERENCES teaching_sessions(id) ON DELETE CASCADE,
  is_favorite boolean NOT NULL DEFAULT false,
  last_playback_position_ms bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, session_id)
);

CREATE TABLE IF NOT EXISTS class_memberships (
  class_id uuid NOT NULL REFERENCES teaching_classes(id) ON DELETE CASCADE,
  student_user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  status text NOT NULL CHECK (status IN ('pending','active','revoked')),
  joined_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (class_id, student_user_id)
);

-- Store only a strong hash of a class access code; never store or log the raw code.
CREATE TABLE IF NOT EXISTS class_access_codes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  class_id uuid NOT NULL REFERENCES teaching_classes(id) ON DELETE CASCADE,
  code_hash text NOT NULL UNIQUE,
  expires_at timestamptz,
  max_uses integer,
  use_count integer NOT NULL DEFAULT 0,
  is_active boolean NOT NULL DEFAULT true,
  created_by uuid NOT NULL REFERENCES app_users(id),
  created_at timestamptz NOT NULL DEFAULT now()
);

-- A source-scoped mapping prevents collisions between Room auto-increment IDs
-- belonging to different users/devices and preserves all old identifiers.
CREATE TABLE IF NOT EXISTS legacy_id_map (
  id bigserial PRIMARY KEY,
  source_system text NOT NULL,
  source_owner_key text NOT NULL,
  entity_type text NOT NULL
    CHECK (entity_type IN ('user','teacher_folder','class','course','session')),
  legacy_id text NOT NULL,
  target_id uuid NOT NULL,
  import_batch_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_system, source_owner_key, entity_type, legacy_id),
  UNIQUE (source_system, source_owner_key, entity_type, target_id)
);
CREATE INDEX IF NOT EXISTS ix_legacy_id_map_target
  ON legacy_id_map(target_id);

CREATE TABLE IF NOT EXISTS migration_batches (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source_system text NOT NULL,
  status text NOT NULL
    CHECK (status IN ('started','validated','imported','rejected','rolled_back')),
  source_counts jsonb NOT NULL DEFAULT '{}'::jsonb,
  imported_counts jsonb NOT NULL DEFAULT '{}'::jsonb,
  rejected_counts jsonb NOT NULL DEFAULT '{}'::jsonb,
  checksum_manifest jsonb NOT NULL DEFAULT '{}'::jsonb,
  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  notes text
);

-- Unresolved legacy ownership is quarantined; it must not be assigned by guesswork.
CREATE TABLE IF NOT EXISTS migration_unresolved_records (
  id bigserial PRIMARY KEY,
  batch_id uuid NOT NULL REFERENCES migration_batches(id) ON DELETE CASCADE,
  entity_type text NOT NULL,
  source_owner_key text,
  legacy_id text,
  reason text NOT NULL,
  payload jsonb NOT NULL,
  resolution_status text NOT NULL DEFAULT 'pending'
    CHECK (resolution_status IN ('pending','resolved','excluded_by_owner_approval')),
  resolved_target_id uuid,
  resolved_by uuid REFERENCES app_users(id),
  resolved_at timestamptz
);

CREATE TABLE IF NOT EXISTS audit_log (
  id bigserial PRIMARY KEY,
  actor_user_id uuid REFERENCES app_users(id),
  action text NOT NULL,
  entity_type text NOT NULL,
  entity_id uuid,
  owner_user_id uuid REFERENCES app_users(id),
  before_data jsonb,
  after_data jsonb,
  request_id text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_audit_log_entity
  ON audit_log(entity_type, entity_id, created_at);

CREATE TABLE IF NOT EXISTS idempotency_keys (
  actor_user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  idempotency_key text NOT NULL,
  request_hash text NOT NULL,
  response_status integer,
  response_body jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (actor_user_id, idempotency_key)
);

-- Important import rules:
-- 1) Do not import a Room ID as the destination UUID.
-- 2) Resolve ClassEntity.ownerUid='' before activating imported classes.
-- 3) Resolve LectureEntity.classId/courseId == NULL into a quarantine report.
-- 4) Do not deduplicate distinct owners' records by title/name/date.
-- 5) Keep original_transcript and transcript as separate fields.
-- 6) Convert legacy audioUrl to session_audio_sources; do not migrate local audioFilePath.
-- 7) Keep isFavorite in user_session_state, not on a shared session row.
-- 8) Execute all import batches transactionally and verify counts/checksums before marking imported.
-- 9) Use deleted_at tombstones and version increments for sync; do not hard-delete records
--    while offline clients may still hold older copies.
