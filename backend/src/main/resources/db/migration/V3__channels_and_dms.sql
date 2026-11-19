-- V3: Add dm_key, unique constraint for DMs, uppercase channel types, and case-insensitive channel name index

-- 1. Standardize channel types to uppercase ('PUBLIC', 'PRIVATE', 'DM')
UPDATE channels SET type = upper(type);
ALTER TABLE channels DROP CONSTRAINT IF EXISTS channels_type_check;
ALTER TABLE channels
    ADD CONSTRAINT channels_type_check
    CHECK (type IN ('PUBLIC', 'PRIVATE', 'DM'));

-- 2. Add dm_key column for DM canonical user pair tracking
ALTER TABLE channels ADD COLUMN dm_key VARCHAR(50);

-- 3. Unique constraint on (workspace_id, dm_key) for DMs
ALTER TABLE channels
    ADD CONSTRAINT channels_workspace_dm_key_key
    UNIQUE (workspace_id, dm_key);

-- 4. Case-insensitive unique index for channel names per workspace
CREATE UNIQUE INDEX idx_channels_workspace_lower_name
    ON channels (workspace_id, lower(name))
    WHERE name IS NOT NULL;
