# AGENTS.md

Guidance for AI coding assistants (Claude Code, Cursor, Codex, etc.) working in this repo.

> Project overview, architecture, ports, run modes, and first-run setup live in [README.md](README.md). Read it before suggesting structural changes. The two subprojects also have their own READMEs ([resource-backend](web-app/resource-backend/README.md), [client-frontend](web-app/client-frontend/README.md)) — consult them when working in those subtrees.

## What this project is

A **playground**, not a production system. Primary value is clarity: someone should be able to read this code and learn how authentication and authorization work end-to-end in OIDC + OAuth2 ecosystem. Optimize for that.

- Weak default credentials are intentional. Do not "harden" them.
- Verbose comments explaining auth concepts are intentional. Do not strip them as noise.
- Do not introduce abstractions or indirection that obscure the OAuth2 flow.

## Tech stack quick reference

- **Backend:** Java 25, Spring Boot 4.0, Spring Security 7, Hibernate 7, Gradle 9.5 (Kotlin DSL), Flyway, PostgreSQL 16
- **Frontend:** React 19, Vite 7, React Router 7, keycloak-js 26, Axios
- **Auth:** Keycloak 26.4 (realm `playground`, client `react-client`, user `testuser`)
- **Orchestration:** Docker Compose v2

These versions are deliberately current. Do not downgrade to "more common" versions when adding deps.

## Conventions

- **Backend package layout:** `ee.authplayground.resourceserver` is the root. Under it, two top-level packages:
  - `appcore` — application-wide plumbing (e.g. `appcore.security` holds `SecurityConfig`). Anything cross-cutting that isn't tied to a domain belongs here.
  - `features.<domain>` — one package per domain feature. Inside a feature, package by layer (`controller / service / repository / entity`). When a feature outgrows a single directory, sub-package by *concept* (e.g. `features.user.profile`, `features.user.permissions`) — never by adding more layers.

  Class names intentionally retain feature context even when redundant with the package (`UserData`, `UserDataRepository`, `UserCustomData`). Don't strip the prefix.
- **Frontend:** auth state lives in a single `AuthProvider` context wrapping `keycloak-js`. Do not introduce a second auth library or state store for auth concerns.
- **DB migrations:** Flyway only, in `web-app/resource-backend/src/main/resources/db/migration/`. Never edit a migration that has already been applied — add a new one.
- **Realm config:** edit `keycloak/realms/playground-realm.json` directly. It re-imports on container recreate (`docker compose down && up`), not on restart.

## Commands you'll need

```bash
# Recommended dev loop (infra in Docker, apps local — hot-reload both)
docker compose up -d keycloak-postgres keycloak backend-postgres
( cd web-app/resource-backend && ./gradlew bootRun )
( cd web-app/client-frontend && npm run dev )

# Backend tests
( cd web-app/resource-backend && ./gradlew test )

# Frontend lint / build
( cd web-app/client-frontend && npm run lint && npm run build )

# Reset everything (wipes DB volumes — Keycloak realm re-imports, backend Flyway re-runs)
docker compose down -v
```

## Gotchas

- **Vite envs are build-time.** `VITE_*` values in `docker-compose.yml` only apply when you `docker compose build` the frontend image. For local `npm run dev`, edit `.env`/`.env.local` instead.
- **Account provisioning is JIT via the SPA.** First sign-in routes the user to `/register`, which POSTs `/api/user/register`; subsequent logins fire `/api/user/sync` to mirror JWT claims into the row. Keycloak is canonical for name/email/username; `custom_data` (JSONB) is app-owned and never touched by sync. Don't reintroduce a Flyway-seeded user row — that pattern was removed in V3.
- **Two Postgres instances.** `keycloak-postgres` (Keycloak's own state) and `backend-postgres` (app data). Do not merge them; separating them is part of the playground's pedagogy.
- **JWT validation is signature-based via JWKS**, not introspection. The backend never calls Keycloak per-request. Keep it that way.
