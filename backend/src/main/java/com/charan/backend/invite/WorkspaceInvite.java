package com.charan.backend.invite;

import com.charan.backend.workspace.WorkspaceRole;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "workspace_invites")
@Getter
@Setter
@NoArgsConstructor
public class WorkspaceInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkspaceRole role;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "invited_by", nullable = false)
    private Long invitedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public WorkspaceInvite(Long workspaceId, String email, WorkspaceRole role,
                           String token, Long invitedBy, Instant expiresAt) {
        this.workspaceId = workspaceId;
        this.email = email;
        this.role = role;
        this.token = token;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
    }
}
