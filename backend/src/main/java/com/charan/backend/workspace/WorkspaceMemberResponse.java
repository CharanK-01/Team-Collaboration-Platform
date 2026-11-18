package com.charan.backend.workspace;

import java.time.Instant;

public record WorkspaceMemberResponse(
        Long userId,
        String email,
        String displayName,
        WorkspaceRole role,
        Instant joinedAt
) {
}
