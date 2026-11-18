package com.charan.backend.invite;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceInviteRepository extends JpaRepository<WorkspaceInvite, Long> {

    Optional<WorkspaceInvite> findByToken(String token);

    @Query("""
        SELECT COUNT(i) > 0
        FROM WorkspaceInvite i
        WHERE i.workspaceId = :workspaceId
          AND LOWER(i.email) = LOWER(:email)
          AND i.acceptedAt IS NULL
          AND i.expiresAt > :now
    """)
    boolean hasPendingInvite(
            @Param("workspaceId") Long workspaceId,
            @Param("email") String email,
            @Param("now") Instant now
    );
}
