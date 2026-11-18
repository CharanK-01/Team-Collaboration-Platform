package com.charan.backend.workspace;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "workspace_members")
@Getter
@Setter
@NoArgsConstructor
public class WorkspaceMember {

    @EmbeddedId
    private WorkspaceMemberId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkspaceRole role;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @PrePersist
    void onCreate() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
    }

    public WorkspaceMember(Long workspaceId, Long userId, WorkspaceRole role) {
        this.id = new WorkspaceMemberId(workspaceId, userId);
        this.role = role;
    }
}
