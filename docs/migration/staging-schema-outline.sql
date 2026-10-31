-- Darsyar staging schema outline only. Not applied to a database.
CREATE TABLE IF NOT EXISTS app_users (
  id uuid PRIMARY KEY,
  legacy_firebase_uid text UNIQUE,
  display_name text NOT NULL DEFAULT '',
  email text,
  status text NOT NULL DEFAULT 'active'
);
CREATE TABLE IF NOT EXISTS app_roles (
  user_id uuid NOT NULL REFERENCES app_users(id),
  role_name text NOT NULL CHECK (role_name IN ('super_admin','teacher','student')),
  PRIMARY KEY (user_id, role_name)
);
CREATE TABLE IF NOT EXISTS teacher_folders (
  id uuid PRIMARY KEY,
  owner_user_id uuid NOT NULL REFERENCES app_users(id),
  legacy_room_id bigint,
  parent_folder_id uuid REFERENCES teacher_folders(id),
  name text NOT NULL,
  sort_order integer NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS teaching_classes (
  id uuid PRIMARY KEY,
  owner_user_id uuid NOT NULL REFERENCES app_users(id),
  legacy_room_id bigint,
  folder_id uuid REFERENCES teacher_folders(id),
  title text NOT NULL,
  visibility text NOT NULL CHECK (visibility IN ('public','private','unlisted')),
  published boolean NOT NULL DEFAULT false
);
CREATE TABLE IF NOT EXISTS teaching_courses (
  id uuid PRIMARY KEY,
  class_id uuid NOT NULL REFERENCES teaching_classes(id),
  legacy_room_id bigint,
  title text NOT NULL
);
CREATE TABLE IF NOT EXISTS teaching_sessions (
  id uuid PRIMARY KEY,
  class_id uuid NOT NULL REFERENCES teaching_classes(id),
  course_id uuid REFERENCES teaching_courses(id),
  legacy_room_id bigint,
  title text NOT NULL,
  transcript text NOT NULL DEFAULT '',
  original_transcript text NOT NULL DEFAULT '',
  ai_summary text,
  sort_order integer NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS session_audio_sources (
  id uuid PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES teaching_sessions(id),
  source_kind text NOT NULL CHECK (source_kind IN ('direct_url','hosted_page','uploaded_asset')),
  source_url text,
  sort_order integer NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS class_memberships (
  class_id uuid NOT NULL REFERENCES teaching_classes(id),
  student_user_id uuid NOT NULL REFERENCES app_users(id),
  status text NOT NULL CHECK (status IN ('pending','active','revoked')),
  PRIMARY KEY (class_id, student_user_id)
);
