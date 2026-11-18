package com.charan.backend.workspace;

import java.time.Instant;

public record WorkspaceResponse(
        Long id,
        String name,
        Long ownerId,
        WorkspaceRole role,
        Instant createdAt
) {
}
