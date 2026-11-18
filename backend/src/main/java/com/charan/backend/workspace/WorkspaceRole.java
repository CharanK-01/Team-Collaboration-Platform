package com.charan.backend.workspace;

/**
 * Roles that a user can have within a workspace.
 * Mapped to the database as VARCHAR strings ('OWNER', 'ADMIN', 'MEMBER').
 */
public enum WorkspaceRole {
    OWNER,
    ADMIN,
    MEMBER
}
