package com.charan.backend.workspace;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, WorkspaceMemberId> {

    Optional<WorkspaceMember> findByIdWorkspaceIdAndIdUserId(Long workspaceId, Long userId);

    boolean existsByIdWorkspaceIdAndIdUserId(Long workspaceId, Long userId);

    @Query("""
        SELECT new com.charan.backend.workspace.WorkspaceResponse(
            w.id, w.name, w.ownerId, m.role, w.createdAt
        )
        FROM WorkspaceMember m, Workspace w
        WHERE m.id.workspaceId = w.id
          AND m.id.userId = :userId
        ORDER BY w.createdAt DESC
    """)
    List<WorkspaceResponse> findWorkspacesByUserId(@Param("userId") Long userId);

    @Query("""
        SELECT new com.charan.backend.workspace.WorkspaceMemberResponse(
            u.id, u.email, u.displayName, m.role, m.joinedAt
        )
        FROM WorkspaceMember m, com.charan.backend.user.User u
        WHERE m.id.userId = u.id
          AND m.id.workspaceId = :workspaceId
        ORDER BY m.joinedAt ASC
    """)
    List<WorkspaceMemberResponse> findMembersByWorkspaceId(@Param("workspaceId") Long workspaceId);

    @Query("""
        SELECT COUNT(m) > 0
        FROM WorkspaceMember m, com.charan.backend.user.User u
        WHERE m.id.userId = u.id
          AND m.id.workspaceId = :workspaceId
          AND LOWER(u.email) = LOWER(:email)
    """)
    boolean isEmailMemberOfWorkspace(@Param("workspaceId") Long workspaceId, @Param("email") String email);
}
