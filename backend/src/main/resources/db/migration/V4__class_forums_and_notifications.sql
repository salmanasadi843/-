-- Class forums and in-app notifications. Existing class/session data is untouched.
CREATE TABLE forum_topics (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 class_id uuid NOT NULL REFERENCES teaching_classes(id) ON DELETE CASCADE,
 session_id uuid REFERENCES teaching_sessions(id) ON DELETE SET NULL,
 author_user_id uuid NOT NULL REFERENCES app_users(id),
 title text NOT NULL CHECK (length(trim(title)) BETWEEN 3 AND 200),
 body text NOT NULL CHECK (length(trim(body)) BETWEEN 1 AND 20000),
 is_pinned boolean NOT NULL DEFAULT false,
 is_locked boolean NOT NULL DEFAULT false,
 deleted_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_forum_topics_class ON forum_topics(class_id, deleted_at, is_pinned DESC, updated_at DESC);
CREATE INDEX ix_forum_topics_session ON forum_topics(session_id) WHERE session_id IS NOT NULL;

CREATE TABLE forum_posts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 topic_id uuid NOT NULL REFERENCES forum_topics(id) ON DELETE CASCADE,
 parent_post_id uuid REFERENCES forum_posts(id) ON DELETE CASCADE,
 author_user_id uuid NOT NULL REFERENCES app_users(id),
 body text NOT NULL CHECK (length(trim(body)) BETWEEN 1 AND 20000),
 deleted_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_forum_posts_topic ON forum_posts(topic_id, created_at);

CREATE TABLE user_notifications (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 recipient_user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 actor_user_id uuid REFERENCES app_users(id) ON DELETE SET NULL,
 class_id uuid REFERENCES teaching_classes(id) ON DELETE CASCADE,
 topic_id uuid REFERENCES forum_topics(id) ON DELETE CASCADE,
 post_id uuid REFERENCES forum_posts(id) ON DELETE CASCADE,
 notification_type text NOT NULL CHECK (notification_type IN ('forum_topic','forum_reply','forum_mention')),
 message text NOT NULL,
 is_read boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_inbox ON user_notifications(recipient_user_id, is_read, created_at DESC);
