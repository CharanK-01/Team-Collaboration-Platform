package com.charan.backend.channel;

import java.time.Instant;

public record ChannelResponse(
        Long id,
        Long workspaceId,
        String name,
        ChannelType type,
        boolean isPrivate,
        boolean joined,
        Instant createdAt
) {
}
