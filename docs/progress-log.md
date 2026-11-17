# Progress Log: Team Collaboration Platform

Update this file at the end of each task.

## Current status
- Current phase: Phase 1 (Auth and workspaces)
- Last completed task: T1.2 Part A (signup with BCrypt, pushed)
- In progress: T1.2 Part B (login and JWT). Code written, not yet tested or committed.
- Next task after that: T1.3 (workspace creation, invites, membership with roles)

## Decisions made
- Project: Slack-like team collaboration platform with AI features
- Backend: Java 21, Spring Boot 4.1.2; frontend: React (later)
- Database: PostgreSQL 16 (pgvector image), schema owned by Flyway; Redis for pub/sub, presence, streams
- Docker Postgres uses host port 5433 because a local Windows Postgres uses 5432
- Database name, user and password are all `teamcollab`; containers are `teamcollab-postgres` and `teamcollab-redis`
- IDs are BIGINT identity columns; the embeddings table is deferred to T5.3
- Auth: stateless JWT (HS256) via Spring's OAuth2 resource server; secret from env `JWT_SECRET`
- Repo: github.com/CharanK-01/Team-Collaboration-Platform

## Open problems
- T1.2B needs Docker running, then the 5 login and JWT tests, then a commit
- Set a real `JWT_SECRET` before the live demo (T6.4)

## Session history
- 2026-11-16: Phase 0 done (T0.1 to T0.5); T1.1 schema migration done; T1.2A signup done and pushed
- 2026-11-17: T1.2B login and JWT code written; fixed a duplicate UserController class; paused because Docker was not running. Switching to Antigravity to continue.
