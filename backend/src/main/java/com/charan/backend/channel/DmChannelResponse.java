package com.charan.backend.channel;

import com.charan.backend.auth.UserResponse;
import java.time.Instant;

public record DmChannelResponse(
        Long id,
        Long workspaceId,
        ChannelType type,
        UserResponse otherUser,
        Instant createdAt
) {
}
