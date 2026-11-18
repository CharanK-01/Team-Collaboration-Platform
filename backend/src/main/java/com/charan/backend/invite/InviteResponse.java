package com.charan.backend.invite;

import com.charan.backend.workspace.WorkspaceRole;
import java.time.Instant;

public record InviteResponse(
        Long id,
        Long workspaceId,
        String email,
        WorkspaceRole role,
        String token,
        Instant expiresAt,
        Instant createdAt
) {
}
