# Progress Log: Team Collaboration Platform

Update this file at the end of each task.

## Current status
- Current phase: Phase 2 (Channels and messaging over REST)
- Last completed task: T1.4 (Integration tests for auth and workspaces using Testcontainers)
- Next task: T2.1 (Channels: create, join, list (public and private), DMs)

## Decisions made
- Project: Slack-like team collaboration platform with AI features
- Backend: Java 21, Spring Boot 4.1.2; frontend: React (later)
- Database: PostgreSQL 16 (pgvector image), schema owned by Flyway; Redis for pub/sub, presence, streams
- Docker Postgres uses host port 5433 because a local Windows Postgres uses 5432
- Database name, user and password are all `teamcollab`; containers are `teamcollab-postgres` and `teamcollab-redis`
- IDs are BIGINT identity columns; the embeddings table is deferred to T5.3
- Auth: stateless JWT (HS256) via Spring's OAuth2 resource server; secret from env `JWT_SECRET`
- JWT claims: sub = user id (string), email, iat, exp. Decoded in UserController via @AuthenticationPrincipal Jwt
- UserController lives in the user package; UserResponse record lives in the auth package (shared by signup, login, /me)
- Test script lives at scripts/test-auth.ps1; run from repo root with: powershell -ExecutionPolicy Bypass -File .\scripts\test-auth.ps1
- V2 migration: updated workspace_members role constraint to uppercase ('OWNER', 'ADMIN', 'MEMBER'); created workspace_invites table with token, expires_at, accepted_at.
- Workspace roles: OWNER, ADMIN, MEMBER. Permissions: OWNER can invite ADMIN/MEMBER; ADMIN can invite only MEMBER; MEMBER cannot invite; nobody can invite as OWNER. Non-members querying members or inviting receive 404 to prevent workspace ID probing.
- SecureRandom 32-byte URL-safe Base64 token generation with 7-day expiration for workspace invites.
- Test script lives at scripts/test-workspaces.ps1; covers workspace creation, non-member 404, invite creation, wrong email 403, correct email acceptance, member invite prohibition 403, and members list validation.
- Repo: github.com/CharanK-01/Team-Collaboration-Platform

## Open problems
- Set a real `JWT_SECRET` before the live demo (T6.4)

## Session history
- 2026-11-16: Phase 0 done (T0.1 to T0.5); T1.1 schema migration done; T1.2A signup done and pushed
- 2026-11-17: T1.2B login and JWT code written; fixed a duplicate UserController class; paused because Docker was not running. Switching to Antigravity to continue.
- 2026-11-18: T1.2B completed. UserController.java was missing from the earlier commit; created it. All 5 auth tests passed: login returns token, /api/users/me works with valid token (200 + correct email), no token → 401, wrong password → 401, tampered JWT payload → 401.
- 2026-11-18: T1.3 completed. Added Flyway V2 migration, workspace domain entities, repositories, services, controllers, and scripts/test-workspaces.ps1. All 16 comprehensive tests passed: workspace creation, non-member 404, invite role OWNER rejection (403), OWNER invites ADMIN (201), wrong email acceptance (403), right email acceptance (200), duplicate acceptance rejection (400), duplicate pending invite conflict (409), member re-invite conflict (409), MEMBER invite restriction (403), ADMIN invites ADMIN restriction (403), ADMIN invites MEMBER success (201), members list verification, and workspace isolation filtering.
- 2026-11-19: T1.4 completed. Configured Testcontainers 2.0.5 with Spring Boot 4.1.2 managed dependencies and throwaway PostgreSQL 16 container (`pgvector/pgvector:pg16`). Created `BaseIntegrationTest`, `FlywayMigrationIntegrationTest` (2/2), `AuthIntegrationTest` (9/9), and `WorkspaceIntegrationTest` (18/18). All 29 integration tests pass via `.\mvnw test`.
