package com.charan.backend.workspace;

import com.charan.backend.invite.CreateInviteRequest;
import com.charan.backend.invite.InviteResponse;
import com.charan.backend.invite.InviteService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final InviteService inviteService;

    public WorkspaceController(WorkspaceService workspaceService, InviteService inviteService) {
        this.workspaceService = workspaceService;
        this.inviteService = inviteService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse createWorkspace(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateWorkspaceRequest request
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return workspaceService.createWorkspace(userId, request);
    }

    @GetMapping
    public List<WorkspaceResponse> getWorkspaces(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.parseLong(jwt.getSubject());
        return workspaceService.getUserWorkspaces(userId);
    }

    @GetMapping("/{id}/members")
    public List<WorkspaceMemberResponse> getMembers(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return workspaceService.getWorkspaceMembers(userId, id);
    }

    @PostMapping("/{id}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse createInvite(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody CreateInviteRequest request
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return inviteService.createInvite(userId, id, request);
    }
}
