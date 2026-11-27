CREATE TABLE app_users (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 display_name text NOT NULL DEFAULT '', email text,
 status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended','pending_deletion')),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE auth_identities (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 provider text NOT NULL, provider_subject text NOT NULL, email text,
 email_verified boolean NOT NULL DEFAULT false, linked_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(provider, provider_subject)
);
CREATE TABLE app_roles (
 user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 role_name text NOT NULL CHECK (role_name IN ('super_admin','teacher','student')),
 granted_at timestamptz NOT NULL DEFAULT now(), granted_by uuid REFERENCES app_users(id),
 PRIMARY KEY(user_id, role_name)
);
CREATE TABLE teacher_profiles (
 user_id uuid PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
 approval_status text NOT NULL DEFAULT 'pending'
   CHECK (approval_status IN ('pending','approved','rejected','revoked')),
 teaching_field text, institution_name text, approved_at timestamptz,
 approved_by uuid REFERENCES app_users(id), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE teacher_folders (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 owner_user_id uuid NOT NULL REFERENCES app_users(id), parent_folder_id uuid,
 name text NOT NULL, sort_order integer NOT NULL DEFAULT 0,
 is_hidden boolean NOT NULL DEFAULT false, version bigint NOT NULL DEFAULT 1,
 deleted_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(id, owner_user_id),
 FOREIGN KEY(parent_folder_id, owner_user_id) REFERENCES teacher_folders(id, owner_user_id)
);
CREATE TABLE teaching_classes (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 owner_user_id uuid NOT NULL REFERENCES app_users(id), folder_id uuid,
 title text NOT NULL, teacher_name text NOT NULL DEFAULT '',
 description text NOT NULL DEFAULT '', term text NOT NULL DEFAULT '',
 class_date_ms bigint, visibility text NOT NULL DEFAULT 'private'
   CHECK (visibility IN ('public','private','unlisted')),
 is_published boolean NOT NULL DEFAULT false, is_hidden boolean NOT NULL DEFAULT false,
 sort_order integer NOT NULL DEFAULT 0, created_at_ms bigint,
 updated_at timestamptz NOT NULL DEFAULT now(), version bigint NOT NULL DEFAULT 1,
 UNIQUE(id, owner_user_id),
 FOREIGN KEY(folder_id, owner_user_id) REFERENCES teacher_folders(id, owner_user_id)
);
CREATE INDEX ix_classes_owner_folder ON teaching_classes(owner_user_id, folder_id, sort_order);
CREATE INDEX ix_classes_public_listing ON teaching_classes(visibility, is_published, is_hidden);
CREATE TABLE teaching_courses (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 class_id uuid NOT NULL REFERENCES teaching_classes(id) ON DELETE CASCADE,
 title text NOT NULL, teacher_name text NOT NULL DEFAULT '',
 description text NOT NULL DEFAULT '', created_at_ms bigint,
 sort_order integer NOT NULL DEFAULT 0, version bigint NOT NULL DEFAULT 1,
 deleted_at timestamptz, UNIQUE(id, class_id)
);
CREATE INDEX ix_courses_class_order ON teaching_courses(class_id, sort_order);
CREATE TABLE teaching_sessions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 class_id uuid NOT NULL REFERENCES teaching_classes(id) ON DELETE CASCADE,
 course_id uuid, title text NOT NULL, course_name text NOT NULL DEFAULT '',
 professor_name text NOT NULL DEFAULT '', date_ms bigint,
 audio_duration_ms bigint NOT NULL DEFAULT 0, transcript text NOT NULL DEFAULT '',
 original_transcript text NOT NULL DEFAULT '', ai_summary text, ai_key_points text,
 ai_quiz_json jsonb, tags text NOT NULL DEFAULT '', is_published boolean NOT NULL DEFAULT true,
 last_edited_ms bigint, sort_order integer NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 1, deleted_at timestamptz,
 FOREIGN KEY(course_id, class_id) REFERENCES teaching_courses(id, class_id)
);
CREATE INDEX ix_sessions_class_order ON teaching_sessions(class_id, sort_order, date_ms);
CREATE INDEX ix_sessions_course_order ON teaching_sessions(course_id, sort_order);
CREATE TABLE session_audio_sources (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 session_id uuid NOT NULL REFERENCES teaching_sessions(id) ON DELETE CASCADE,
 source_kind text NOT NULL CHECK (source_kind IN ('direct_url','hosted_page','external_link','uploaded_asset')),
 source_url text, storage_key text, title text, sort_order integer NOT NULL DEFAULT 0,
 is_active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(),
 CHECK ((source_kind = 'uploaded_asset' AND storage_key IS NOT NULL)
     OR (source_kind <> 'uploaded_asset' AND source_url IS NOT NULL))
);
CREATE TABLE user_session_state (
 user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 session_id uuid NOT NULL REFERENCES teaching_sessions(id) ON DELETE CASCADE,
 is_favorite boolean NOT NULL DEFAULT false, last_playback_position_ms bigint NOT NULL DEFAULT 0,
 updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id, session_id)
);
CREATE TABLE class_memberships (
 class_id uuid NOT NULL REFERENCES teaching_classes(id) ON DELETE CASCADE,
 student_user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 status text NOT NULL CHECK (status IN ('pending','active','revoked')),
 joined_at timestamptz, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(class_id, student_user_id)
);
CREATE TABLE class_access_codes (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 class_id uuid NOT NULL REFERENCES teaching_classes(id) ON DELETE CASCADE,
 code_hash text NOT NULL UNIQUE, expires_at timestamptz, max_uses integer,
 use_count integer NOT NULL DEFAULT 0, is_active boolean NOT NULL DEFAULT true,
 created_by uuid NOT NULL REFERENCES app_users(id), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE legacy_id_map (
 id bigserial PRIMARY KEY, source_system text NOT NULL, source_owner_key text NOT NULL,
 entity_type text NOT NULL CHECK (entity_type IN ('user','teacher_folder','class','course','session')),
 legacy_id text NOT NULL, target_id uuid NOT NULL, import_batch_id uuid,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(source_system, source_owner_key, entity_type, legacy_id),
 UNIQUE(source_system, source_owner_key, entity_type, target_id)
);
CREATE TABLE migration_batches (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), source_system text NOT NULL,
 status text NOT NULL CHECK (status IN ('started','validated','imported','rejected','rolled_back')),
 source_counts jsonb NOT NULL DEFAULT '{}'::jsonb, imported_counts jsonb NOT NULL DEFAULT '{}'::jsonb,
 rejected_counts jsonb NOT NULL DEFAULT '{}'::jsonb, checksum_manifest jsonb NOT NULL DEFAULT '{}'::jsonb,
 started_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz, notes text
);
CREATE TABLE migration_unresolved_records (
 id bigserial PRIMARY KEY, batch_id uuid NOT NULL REFERENCES migration_batches(id) ON DELETE CASCADE,
 entity_type text NOT NULL, source_owner_key text, legacy_id text, reason text NOT NULL,
 payload jsonb NOT NULL, resolution_status text NOT NULL DEFAULT 'pending'
   CHECK (resolution_status IN ('pending','resolved','excluded_by_owner_approval')),
 resolved_target_id uuid, resolved_by uuid REFERENCES app_users(id), resolved_at timestamptz
);
CREATE TABLE audit_log (
 id bigserial PRIMARY KEY, actor_user_id uuid REFERENCES app_users(id), action text NOT NULL,
 entity_type text NOT NULL, entity_id uuid, owner_user_id uuid REFERENCES app_users(id),
 before_data jsonb, after_data jsonb, request_id text, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_entity ON audit_log(entity_type, entity_id, created_at);
CREATE TABLE idempotency_keys (
 actor_user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 idempotency_key text NOT NULL, request_hash text NOT NULL, response_status integer,
 response_body jsonb, created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL, PRIMARY KEY(actor_user_id, idempotency_key)
);
