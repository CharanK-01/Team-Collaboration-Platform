package com.charan.backend.message;

import java.time.Instant;

/**
 * The sender sub-object embedded in every MessageResponse.
 * We only expose id and displayName; email is not included to keep
 * payloads small and consistent with what a chat UI actually needs.
 */
public record SenderInfo(
        Long id,
        String displayName
) {}
