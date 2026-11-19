# Progress Log: Team Collaboration Platform

Update this file at the end of each task.

## Current status
- Current phase: Phase 2 (Channels and messaging over REST)
- Last completed task: T2.2 (Messages: send, fetch history with cursor pagination)
- Next task: T2.3 (Permission checks (only members read or post))

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
- V3 migration: standardized channels type constraint to uppercase ('PUBLIC', 'PRIVATE', 'DM'); added `dm_key VARCHAR(50)` with `UNIQUE (workspace_id, dm_key)` for canonical user pair unique constraint (`minId:maxId`); added case-insensitive unique index `idx_channels_workspace_lower_name` on `(workspace_id, lower(name))`.
- Channel rules: names validated with regex `^[a-z0-9-]+$` (1-80 chars) and unique per workspace (case-insensitive); creator auto-joined; non-members get 403 (or 404 if workspace doesn't exist); joining private channel when not already a member returns 404 to avoid leaking existence; DMs use canonical pair key `minId:maxId` with `ON CONFLICT DO NOTHING` for race-free concurrency.
- V4 migration: added index idx_messages_channel_id_id_desc on messages(channel_id, id DESC) for efficient cursor pagination.
- Messages: body column in DB exposed as content in DTOs; cursor pagination using id < before with limit + 1 lookahead; senders batch-loaded via findAllById to avoid N+1 queries.
- Channel write locking: send() acquires a PESSIMISTIC_WRITE lock on the channel row to serialize seq generation per channel, ensuring atomic monotonic sequence numbering (1..N) without duplicate key errors under concurrent sends.
- Repo: github.com/CharanK-01/Team-Collaboration-Platform

## Open problems
- Set a real `JWT_SECRET` before the live demo (T6.4)

## Known limitations
- Pending-invite check is check-then-insert, so two simultaneous invites to the same email can both pass (fix idea: a partial unique index on `(workspace_id, email) WHERE accepted_at IS NULL` plus catching the data integrity violation as 409 Conflict).
- Accepting the same invite from two simultaneous requests can return a 500 instead of 409 (no duplicate membership results because of the composite primary key `(workspace_id, user_id)`, but unhandled database constraint exceptions bubble up as 500).
- Inviting with role OWNER returns 403 Forbidden; 400 Bad Request would be more accurate because OWNER is an invalid client payload for an invite, not a valid request blocked by authorization.
- JWT_SECRET must be set as an environment variable for the live demo to override the dev default.
- Integration tests share one static PostgreSQL Testcontainers instance across the test run and clean tables in `@BeforeEach` to keep test run times fast without state leakage.
- Creating two channels with the same name at the same moment can return 500 instead of 409. createChannel checks whether the name exists and then inserts. The unique index on (workspace_id, lower(name)) prevents the duplicate row, but the violation is not caught. Fix idea: catch DataIntegrityViolationException and return 409.
- POST /channels/{id}/members returns 403 when the caller is not in a private channel, while join returns 404 for the same case. This lets a workspace member detect that a private channel exists. Fix idea: return 404 for private channels the caller is not in.
- DM creation is race-safe by design (INSERT ... ON CONFLICT DO NOTHING plus a unique constraint on workspace_id and dm_key), but the tests only call it sequentially. There is no concurrent test.
- Content is validated before trimming, so a message with 4000 characters plus trailing spaces is rejected with 400.
- Sends to the same channel are serialized by a row lock on the channel (PESSIMISTIC_WRITE), so one channel can only process one send at a time. This is fine at demo scale. Alternative: a per-channel counter or sequence.

## Session history
- 2026-11-16: Phase 0 done (T0.1 to T0.5); T1.1 schema migration done; T1.2A signup done and pushed
- 2026-11-17: T1.2B login and JWT code written; fixed a duplicate UserController class; paused because Docker was not running. Switching to Antigravity to continue.
- 2026-11-18: T1.2B completed. UserController.java was missing from the earlier commit; created it. All 5 auth tests passed: login returns token, /api/users/me works with valid token (200 + correct email), no token → 401, wrong password → 401, tampered JWT payload → 401.
- 2026-11-18: T1.3 completed. Added Flyway V2 migration, workspace domain entities, repositories, services, controllers, and scripts/test-workspaces.ps1. All 16 comprehensive tests passed: workspace creation, non-member 404, invite role OWNER rejection (403), OWNER invites ADMIN (201), wrong email acceptance (403), right email acceptance (200), duplicate acceptance rejection (400), duplicate pending invite conflict (409), member re-invite conflict (409), MEMBER invite restriction (403), ADMIN invites ADMIN restriction (403), ADMIN invites MEMBER success (201), members list verification, and workspace isolation filtering.
- 2026-11-19: T1.4 completed. Configured Testcontainers 2.0.5 with Spring Boot 4.1.2 managed dependencies and throwaway PostgreSQL 16 container (`pgvector/pgvector:pg16`). Created `BaseIntegrationTest`, `FlywayMigrationIntegrationTest` (2/2), `AuthIntegrationTest` (9/9), and `WorkspaceIntegrationTest` (18/18). All 29 integration tests pass via `.\mvnw test`.
- 2026-11-19: T2.1 completed. Added Flyway V3 migration (`V3__channels_and_dms.sql`), channel and DM domain entities, repositories, service, and controllers (`ChannelController`, `DmController`). Created `ChannelIntegrationTest` with 18 comprehensive tests covering public/private creation, validation, case-insensitive uniqueness, visibility scoping, idempotent joins, private channel existence hiding (404), member invitations, and race-free DM find-or-create using canonical pair keys. All 47 integration tests pass via `.\mvnw test`.
- 2026-11-19: T2.2 completed. Added Flyway V4 migration (idx_messages_channel_id_id_desc), message entity, repository, service, and controller with cursor-based pagination (before, limit) and batch sender loading. Resolved concurrent sequence race condition via PESSIMISTIC_WRITE channel row locking. Created MessageIntegrationTest with 23 integration tests covering send validation, workspace/channel access controls, existence hiding for private channels/DMs, boundary conditions, gapless pagination, and 20-thread concurrent send verification. Total 70/70 integration tests green.
