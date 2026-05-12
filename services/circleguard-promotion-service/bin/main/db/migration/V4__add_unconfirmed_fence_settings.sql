ALTER TABLE system_settings
ADD COLUMN IF NOT EXISTS unconfirmed_fencing_enabled BOOLEAN NOT NULL DEFAULT true,
ADD COLUMN IF NOT EXISTS auto_threshold_seconds BIGINT NOT NULL DEFAULT 3600;

UPDATE system_settings SET
    unconfirmed_fencing_enabled = true,
    auto_threshold_seconds = 3600
WHERE id = 1;
