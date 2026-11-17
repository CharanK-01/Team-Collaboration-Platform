# Team Collaboration Platform (Slack-like): context for AI coding agents

Read this file first, then `docs/PROJECT_PLAN.md` (full plan, architecture, task list) and `docs/progress-log.md` (current status).

## Who I am and how to work with me

- I am Charan, a CS student and a beginner with Spring Boot. Target role: Backend SDE-1. This is my resume project, so I must be able to explain every part in an interview.
- Work on ONE task at a time, in T-number order (see `docs/PROJECT_PLAN.md`). When a task is finished, tell me the exact next task and WAIT until I say it is done before starting it.
- Give exact steps: exact file paths, exact commands (Windows PowerShell), full file contents. Do not assume I know where a file or folder goes.
- Explain what each piece of code does and why, in plain language, so I can repeat it in an interview.
- Be direct. If something fails, ask me for the exact error text (the first `ERROR` or `Caused by` line) before guessing.
- Before running terminal commands or changing many files, tell me what you are about to do.
- Only claim measured numbers (load tests, latency) on the resume.
- If time is short, cut Phase 5 and Phase 4 extras first. Never cut Phases 1 to 3 and the core of Phase 4 (T4.1 to T4.4).

## Project

Slack-style real-time team messaging platform with AI features (thread summary, catch-me-up, semantic search). The goal is backend correctness and scalability: horizontally scaled WebSocket servers, reliable delivery, ordering, idempotency, reconnect-and-replay, async workers, measured load tests.

Stack: Java 21, Spring Boot 4.1.2 (Spring Security 7, Hibernate 7, Flyway 12), Maven, PostgreSQL 16 (pgvector image), Redis 7, Docker Compose. Later: React + Vite + TypeScript, Nginx, S3/MinIO, Micrometer/Prometheus/Grafana, Testcontainers, GitHub Actions.

## Environment

- Windows, PowerShell. IDE: Antigravity / VS Code.
- Repo root: `C:\Users\ASUS\Team-Collaboration-Platform`. GitHub: `CharanK-01/Team-Collaboration-Platform`, branch `main`.
- `backend/` is the Spring Boot app. `frontend/` is empty so far.
- Docker Desktop must be running. From the repo root: `docker compose up -d`, then `docker compose ps`.
- Run the backend from the `backend` folder: `.\mvnw spring-boot:run`. If stale classes cause trouble, use `.\mvnw clean spring-boot:run`. Stop with Ctrl+C.
- `mvnw` only works inside `backend/`. Many of my past errors came from running commands in the wrong folder, so always say which folder a command runs from.

## Decisions and gotchas

- Docker Postgres is mapped to host port **5433** (a local Windows Postgres service already uses 5432). JDBC URL: `jdbc:postgresql://localhost:5433/teamcollab`, user and password `teamcollab`. Containers: `teamcollab-postgres`, `teamcollab-redis`.
- Schema is owned by Flyway (`backend/src/main/resources/db/migration/`). `ddl-auto=validate`. Never edit a migration that has already run; add `V2__...sql` instead. To reset the dev database: `docker compose down -v`, then `docker compose up -d`.
- All Java code lives under `backend/src/main/java/com/charan/backend/` in the packages `config`, `health`, `auth`, `user`. New packages go inside `com/charan/backend/` (I once created folders outside it and Spring did not see them).
- IDs are `BIGINT` identity columns. Emails are lowercased before saving. Passwords are hashed with BCrypt. API responses use records, never entities (never expose `passwordHash`).
- Auth: stateless JWT (HS256) using `spring-security-oauth2-resource-server` and `spring-security-oauth2-jose` (Nimbus). Claims: `sub` = user id, `email`, `iat`, `exp`. Secret comes from env var `JWT_SECRET` with a dev default in `application.properties`. A real `JWT_SECRET` must be set for the live demo. CSRF is disabled and sessions are stateless.
- Public endpoints: `/health`, `/error`, `/api/auth/**`. Everything else needs `Authorization: Bearer <token>`.
- Login returns the same 401 message for an unknown email and a wrong password (no user enumeration).

## Where we are

Done and pushed: T0.1 to T0.5 (setup, Docker, `/health`), T1.1 (V1 schema: users, workspaces, workspace_members, channels, channel_members, messages, reactions, files), T1.2 Part A (signup with BCrypt; tested: 201, duplicate 409, short password 400).

In progress: T1.2 Part B (login and JWT). The code is written but NOT yet verified and NOT yet committed:
`config/JwtConfig`, `auth/JwtService`, `auth/LoginRequest`, `auth/AuthResponse`, `user/UserController` (`GET /api/users/me`), `AuthService.login`, `AuthController` `/login`, and `SecurityConfig` with the JWT resource server.

Recent problems (already handled): a stale duplicate `UserController` class in the `auth` package (fixed with a clean build), then "Connection to localhost:5433 refused" because Docker Desktop was not running.

Next steps for the current task:
1. Start Docker Desktop, then `docker compose up -d` (repo root) and confirm Postgres shows `0.0.0.0:5433->5432/tcp`.
2. From `backend/`: `.\mvnw clean spring-boot:run`.
3. Test (dev user `charan@example.com` / `password123` already exists in my local database):
   - `POST /api/auth/login` returns `token`, `tokenType`, `expiresInSeconds`, `user`.
   - `GET /api/users/me` with the token returns the user.
   - `GET /api/users/me` with no token returns 401.
   - Login with a wrong password returns 401.
   - `GET /api/users/me` with a tampered token returns 401.
4. Commit and push: `feat: add login with JWT and protected /api/users/me endpoint`.
5. Update `docs/progress-log.md`, then give me T1.3 (workspace creation, invites, membership with roles).

## After every task

Update `docs/progress-log.md` (current phase, last completed task, next task, decisions, open problems) and tick the box in `docs/PROJECT_PLAN.md`.
