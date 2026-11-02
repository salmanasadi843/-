CREATE TABLE user_session_notes (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 session_id uuid NOT NULL REFERENCES teaching_sessions(id) ON DELETE CASCADE,
 quoted_text text NOT NULL DEFAULT '',
 note_text text NOT NULL,
 start_offset integer,
 end_offset integer,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 deleted_at timestamptz,
 CHECK (char_length(note_text) BETWEEN 1 AND 20000),
 CHECK (char_length(quoted_text) <= 10000),
 CHECK ((start_offset IS NULL AND end_offset IS NULL) OR
        (start_offset IS NOT NULL AND end_offset IS NOT NULL AND start_offset >= 0 AND end_offset >= start_offset))
);
CREATE INDEX ix_user_session_notes_owner_session
 ON user_session_notes(user_id, session_id, updated_at DESC)
 WHERE deleted_at IS NULL;
