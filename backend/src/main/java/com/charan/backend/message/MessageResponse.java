package com.charan.backend.message;

import java.time.Instant;

/**
 * A single message in an API response.
 *
 * Fields:
 *  id        – the message id (also used as the pagination cursor)
 *  channelId – which channel this belongs to
 *  content   – the message text (stored as "body" in the DB)
 *  sender    – nested object with the author's id and displayName
 *  createdAt – when the message was persisted
 */
public record MessageResponse(
        Long id,
        Long channelId,
        String content,
        SenderInfo sender,
        Instant createdAt
) {}
