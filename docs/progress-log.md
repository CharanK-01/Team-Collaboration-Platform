# Progress Log: Team Collaboration Platform

Update this file at the end of each task.

## Current status
- Current phase: Phase 3 (Real-time)
- Last completed task: T3.1 (WebSocket + STOMP authenticated with JWT)
- Next task: T3.2 (Persist first, then broadcast to channel subscribers)

## Decisions made
- WebSocket: STOMP endpoint /ws (native WebSocket, no SockJS), in-memory simple broker for /topic and /queue, application prefix /app. The /ws handshake is permitted without authentication because browsers cannot set an Authorization header on a WebSocket handshake.
- WebSocket auth: the JWT is validated on the STOMP CONNECT frame by a ChannelInterceptor (WebSocketConfig), using the same JwtDecoder bean as the REST API. The session principal is set to the sub claim (user id). Missing, malformed, tampered or expired tokens throw and the client gets an ERROR frame. Tests verify the principal on the server side through a test-only /app/whoami controller.
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
- Channel authorization: centralized into ChannelAccessService as single source of truth for workspace membership validation and channel visibility checks across ChannelService and MessageService.
- Channel creation concurrency: saveAndFlush() inside createChannel catches DataIntegrityViolationException on concurrent duplicate names and returns 409 Conflict.
- Private channel information leak fix: POST /channels/{id}/members returns 404 (not 403) when caller is not in private channel, preventing existence leakage.
- Repo: github.com/CharanK-01/Team-Collaboration-Platform

## Open problems
- Set a real `JWT_SECRET` before the live demo (T6.4)

## Known limitations
- The WebSocket token is checked only at CONNECT, so an open connection stays alive after the token expires.
- WebSocket allowed origins are a wildcard for development and must be restricted for production.
- The in-memory STOMP broker only works on a single server instance; a Redis-backed relay is needed to scale out (planned with T3.4).
- The expired-token rejection case is not covered by a test, because building an expired JWT with the app's key was not practical.
- Subscriptions are not yet authorized per channel; any authenticated user could subscribe to any /topic destination. This is closed in T3.2.
- Pending-invite check is check-then-insert, so two simultaneous invites to the same email can both pass (fix idea: a partial unique index on `(workspace_id, email) WHERE accepted_at IS NULL` plus catching the data integrity violation as 409 Conflict).
- Accepting the same invite from two simultaneous requests can return a 500 instead of 409 (no duplicate membership results because of the composite primary key `(workspace_id, user_id)`, but unhandled database constraint exceptions bubble up as 500).
- Inviting with role OWNER returns 403 Forbidden; 400 Bad Request would be more accurate because OWNER is an invalid client payload for an invite, not a valid request blocked by authorization.
- JWT_SECRET must be set as an environment variable for the live demo to override the dev default.
- Integration tests share one static PostgreSQL Testcontainers instance across the test run and clean tables in `@BeforeEach` to keep test run times fast without state leakage.
- DM creation is race-safe by design (INSERT ... ON CONFLICT DO NOTHING plus a unique constraint on workspace_id and dm_key), but the tests only call it sequentially. There is no concurrent test.
- Content is validated before trimming, so a message with 4000 characters plus trailing spaces is rejected with 400.
- Sends to the same channel are serialized by a row lock on the channel (PESSIMISTIC_WRITE), so one channel can only process one send at a time. This is fine at demo scale. Alternative: a per-channel counter or sequence.

## REST API summary

| Method | Path | Status |
|--------|------|--------|
| POST | /api/auth/signup | 201 |
| POST | /api/auth/login | 200 |
| GET | /api/users/me | 200 |
| GET | /health | 200 |
| POST | /api/workspaces | 201 |
| GET | /api/workspaces | 200 |
| GET | /api/workspaces/{id}/members | 200 |
| POST | /api/workspaces/{id}/invites | 201 |
| POST | /api/invites/{token}/accept | 200 |
| POST | /api/workspaces/{wid}/channels | 201 |
| GET | /api/workspaces/{wid}/channels | 200 |
| POST | /api/workspaces/{wid}/channels/{cid}/join | 200 |
| POST | /api/workspaces/{wid}/channels/{cid}/members | 200 (or 404 for private) |
| POST | /api/workspaces/{wid}/dms | 200 |
| GET | /api/workspaces/{wid}/dms | 200 |
| POST | /api/workspaces/{wid}/channels/{cid}/messages | 201 |
| GET | /api/workspaces/{wid}/channels/{cid}/messages | 200 |

Access rules: not a workspace member gets 403. A channel not in the workspace gets 404. A private channel or DM the caller is not in gets 404, so existence is not leaked. A public channel the caller has not joined gets 403 for reading and sending until they join.

## Session history
- 2026-11-16: Phase 0 done (T0.1 to T0.5); T1.1 schema migration done; T1.2A signup done and pushed
- 2026-11-17: T1.2B login and JWT code written; fixed a duplicate UserController class; paused because Docker was not running. Switching to Antigravity to continue.
- 2026-11-18: T1.2B completed. UserController.java was missing from the earlier commit; created it. All 5 auth tests passed: login returns token, /api/users/me works with valid token (200 + correct email), no token -> 401, wrong password -> 401, tampered JWT payload -> 401.
- 2026-11-18: T1.3 completed. Added Flyway V2 migration, workspace domain entities, repositories, services, controllers, and scripts/test-workspaces.ps1. All 16 comprehensive tests passed: workspace creation, non-member 404, invite role OWNER rejection (403), OWNER invites ADMIN (201), wrong email acceptance (403), right email acceptance (200), duplicate acceptance rejection (400), duplicate pending invite conflict (409), member re-invite conflict (409), MEMBER invite restriction (403), ADMIN invites ADMIN restriction (403), ADMIN invites MEMBER success (201), members list verification, and workspace isolation filtering.
- 2026-11-19: T1.4 completed. Configured Testcontainers 2.0.5 with Spring Boot 4.1.2 managed dependencies and throwaway PostgreSQL 16 container (`pgvector/pgvector:pg16`). Created `BaseIntegrationTest`, `FlywayMigrationIntegrationTest` (1/1), `AuthIntegrationTest` (9/9), and `WorkspaceIntegrationTest` (18/18). All 29 integration tests pass via `.\mvnw test`.
- 2026-11-19: T2.1 completed. Added Flyway V3 migration (`V3__channels_and_dms.sql`), channel and DM domain entities, repositories, service, and controllers (`ChannelController`, `DmController`). Created `ChannelIntegrationTest` with 18 comprehensive tests covering public/private creation, validation, case-insensitive uniqueness, visibility scoping, idempotent joins, private channel existence hiding (404), member invitations, and race-free DM find-or-create using canonical pair keys. All 47 integration tests pass via `.\mvnw test`.
- 2026-11-19: T2.2 completed. Added Flyway V4 migration (idx_messages_channel_id_id_desc), message entity, repository, service, and controller with cursor-based pagination (before, limit) and batch sender loading. Resolved concurrent sequence race condition via PESSIMISTIC_WRITE channel row locking. Created MessageIntegrationTest with 23 integration tests covering send validation, workspace/channel access controls, existence hiding for private channels/DMs, boundary conditions, gapless pagination, and 20-thread concurrent send verification. Total 70/70 integration tests green.
- 2026-11-20: T2.3 completed. Centralized access checks in ChannelAccessService as the single source of truth for workspace and channel authorization. Resolved channel creation race via saveAndFlush() and DataIntegrityViolationException mapping to 409 Conflict. Fixed private channel member addition to return 404 (not 403) for callers not in the private channel, preventing existence leakage. Added 10-thread channel creation concurrency test (exactly 1 201, 9 409s, 0 500s) and 48-case ChannelPermissionMatrixTest parameterized living documentation suite. Total 120/120 integration tests green.
- 2026-11-20: T2.4 completed. Added tests for the two uncovered endpoints (WorkspaceListTest for GET /api/workspaces, HealthControllerTest for /health); every other endpoint was already covered. Added ChatFlowEndToEndTest, one scenario through REST only with the real invite flow: three users sign up, Alice creates a workspace with a public and a private channel, invites Bob and he accepts, Bob joins the public channel, 5 messages are paged with limit=2 (newest first, no gaps), Bob replies, Alice adds Bob to the private channel, a DM is created twice with the same id, and an outsider gets 403 everywhere. Total 123/123 integration tests green. Phase 2 complete.
- 2026-11-20: T3.1 completed. Added WebSocketConfig (STOMP /ws, simple broker, JWT interceptor on CONNECT) and opened /ws in SecurityConfig. Added WebSocketConnectIntegrationTest (5 tests): valid token connects and the server-side principal equals the user id (round trip through a test-only /app/whoami controller); missing header, "Token abc", "Bearer " with no token and a tampered signature are all rejected. Mutation check: with validation disabled, 4 rejection tests failed and the valid-token test errored with a null principal. Total 128/128 integration tests green.