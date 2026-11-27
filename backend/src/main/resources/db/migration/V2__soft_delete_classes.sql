ALTER TABLE teaching_classes
    ADD COLUMN deleted_at timestamptz;

CREATE INDEX ix_classes_deleted_owner
    ON teaching_classes(owner_user_id, deleted_at DESC)
    WHERE deleted_at IS NOT NULL;
