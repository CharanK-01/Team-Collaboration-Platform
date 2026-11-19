-- V4: Performance index for cursor-pagination on messages (newest first).
-- Covers queries filtering by channel_id and sorting by id DESC.

CREATE INDEX idx_messages_channel_id_id_desc
    ON messages (channel_id, id DESC);
