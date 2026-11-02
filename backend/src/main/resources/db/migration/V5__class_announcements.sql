ALTER TABLE user_notifications
    DROP CONSTRAINT user_notifications_notification_type_check;
ALTER TABLE user_notifications
    ADD CONSTRAINT user_notifications_notification_type_check
    CHECK (notification_type IN ('forum_topic','forum_reply','forum_mention','class_announcement'));

CREATE TABLE class_announcements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id uuid NOT NULL REFERENCES teaching_classes(id) ON DELETE CASCADE,
    author_user_id uuid NOT NULL REFERENCES app_users(id),
    title text NOT NULL CHECK (length(trim(title)) BETWEEN 3 AND 200),
    body text NOT NULL CHECK (length(trim(body)) BETWEEN 1 AND 20000),
    is_pinned boolean NOT NULL DEFAULT false,
    published_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);
CREATE INDEX ix_class_announcements_feed
    ON class_announcements(class_id, deleted_at, is_pinned DESC, published_at DESC);
