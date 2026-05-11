CREATE TABLE IF NOT EXISTS system_settings (
    id BIGSERIAL PRIMARY KEY
);

ALTER TABLE system_settings
ADD COLUMN IF NOT EXISTS mandatory_fence_days INTEGER NOT NULL DEFAULT 14,
ADD COLUMN IF NOT EXISTS encounter_window_days INTEGER NOT NULL DEFAULT 14;

INSERT INTO system_settings (id, mandatory_fence_days, encounter_window_days)
VALUES (1, 14, 14) ON CONFLICT (id) DO NOTHING;
