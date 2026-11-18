package com.charan.backend.invite;

import com.charan.backend.workspace.WorkspaceRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateInviteRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        String email,

        @NotNull(message = "Role is required (ADMIN or MEMBER)")
        WorkspaceRole role
) {
}
