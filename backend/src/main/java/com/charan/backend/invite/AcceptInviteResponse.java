package com.charan.backend.invite;

import com.charan.backend.workspace.WorkspaceRole;

public record AcceptInviteResponse(
        Long workspaceId,
        WorkspaceRole role,
        String message
) {
}
