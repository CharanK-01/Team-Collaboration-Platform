-- V2: fix workspace_members role constraint (uppercase) and add workspace_invites table
--
-- Rule: never edit V1. Always add a new migration file for schema changes.

-- -------------------------------------------------------------------------
-- 1. Fix the role check constraint on workspace_members
--    The V1 constraint may have been created with lowercase or no values.
--    We uppercase any existing data, drop the old constraint, and add a new
--    one that enforces exactly the three uppercase role values we use in Java.
-- -------------------------------------------------------------------------

-- Uppercase any existing role values so the new constraint won't reject them.
UPDATE workspace_members SET role = upper(role);

-- Drop the old check constraint by name (created in V1).
-- If it doesn't exist this will error; in that case remove this line.
ALTER TABLE workspace_members DROP CONSTRAINT IF EXISTS workspace_members_role_check;

-- Add the corrected constraint.
ALTER TABLE workspace_members
    ADD CONSTRAINT workspace_members_role_check
    CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'));

-- -------------------------------------------------------------------------
-- 2. Create the workspace_invites table
--
--    Each row is a pending (or accepted) invitation for one email address
--    to join one workspace with a specific role.
--
--    Columns:
--      id           - surrogate primary key
--      workspace_id - which workspace the invite is for
--      email        - the invited person's email address (not necessarily a user yet)
--      role         - the role they will get on acceptance (ADMIN or MEMBER only;
--                     OWNER is not invitable - the creator is always the owner)
--      token        - the one-time secret the invitee presents to accept
--      invited_by   - the user who created the invite
--      created_at   - when the invite was sent
--      expires_at   - after this instant the invite is no longer valid
--      accepted_at  - NULL while pending; set to now() when the invite is accepted
-- -------------------------------------------------------------------------

CREATE TABLE workspace_invites (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id BIGINT      NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    email        VARCHAR(255) NOT NULL,
    role         VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    token        VARCHAR(64)  NOT NULL UNIQUE,
    invited_by   BIGINT       NOT NULL REFERENCES users(id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    accepted_at  TIMESTAMPTZ
);

-- Index on email so we can quickly check whether an address already has
-- a pending invite for a given workspace.
CREATE INDEX idx_workspace_invites_email ON workspace_invites(email);
