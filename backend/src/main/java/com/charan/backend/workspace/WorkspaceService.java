package com.charan.backend.workspace;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository workspaceMemberRepository) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    /**
     * Creates a workspace and registers the creator as its OWNER.
     * Executed within a single atomic database transaction.
     */
    @Transactional
    public WorkspaceResponse createWorkspace(Long userId, CreateWorkspaceRequest request) {
        Workspace workspace = new Workspace(request.name().trim(), userId);
        workspace = workspaceRepository.save(workspace);

        WorkspaceMember ownerMember = new WorkspaceMember(workspace.getId(), userId, WorkspaceRole.OWNER);
        workspaceMemberRepository.save(ownerMember);

        return new WorkspaceResponse(
                workspace.getId(),
                workspace.getName(),
                workspace.getOwnerId(),
                WorkspaceRole.OWNER,
                workspace.getCreatedAt()
        );
    }

    /**
     * Lists only the workspaces the caller belongs to, with their assigned role.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceResponse> getUserWorkspaces(Long userId) {
        return workspaceMemberRepository.findWorkspacesByUserId(userId);
    }

    /**
     * Lists all members of a workspace.
     * If the caller is not a member, returns 404 to prevent resource probing.
     */
    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> getWorkspaceMembers(Long userId, Long workspaceId) {
        boolean isMember = workspaceMemberRepository.existsByIdWorkspaceIdAndIdUserId(workspaceId, userId);
        if (!isMember) {
            // Return 404 instead of 403 to prevent workspace ID probing
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found");
        }

        return workspaceMemberRepository.findMembersByWorkspaceId(workspaceId);
    }
}
