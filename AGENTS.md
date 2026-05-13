# AGENTS.md

Guidance for AI coding assistants (Claude Code, Cursor, Codex, etc.) working in this repo.

> Project overview, architecture, ports, run modes, and first-run setup live in [README.md](README.md). Read it before suggesting structural changes. Subprojects have their own READMEs ([idp-server](authorization-server/idp-server/README.md), [resource-backend](web-app/resource-backend/README.md), [client-frontend](web-app/client-frontend/README.md)) — consult them when working in those subtrees.

## What this project is

A **playground**, not a production system. Primary value is clarity: someone should be able to read this code and learn how authentication and authorization work end-to-end in OIDC + OAuth2 ecosystem. Optimize for that.

- Weak default credentials are intentional. Do not "harden" them.
- Verbose comments explaining auth concepts are intentional. Do not strip them as noise.
- Do not introduce abstractions or indirection that obscure the OAuth2 flow.

## Conventions

- **Versions are deliberately current** (Java 25, Spring Boot 4, Spring Security 7, React 19, etc. — full stack table in [README.md](README.md#tech-stack)). Do not downgrade to "more common" versions when adding deps; demonstrating modern Spring + React OIDC patterns is part of what the playground exists to show.

- **Backend package layout:** each Spring Boot module roots at `ee.authplayground.<artifact>` (e.g. `ee.authplayground.resourceserver`, `ee.authplayground.idpserver`). Under each root, two top-level packages:
  - `appcore` — application-wide plumbing (e.g. `appcore.security` holds the security configs). Anything cross-cutting that isn't tied to a domain belongs here.
  - `features.<domain>` — one package per domain feature. Inside a feature, package by layer (`controller / service / repository / entity`). When a feature outgrows a single directory, sub-package by *concept* (e.g. `features.users.profile`, `features.users.permissions`) — never by adding more layers.

  Class names intentionally retain feature context even when redundant with the package (`UserData`, `UserDataRepository`, `UserCustomData`). Don't strip the prefix.
- **Frontend:** auth state lives in a single `AuthProvider` context wrapping `keycloak-js`. Do not introduce a second auth library or state store for auth concerns.
- **DB migrations:** Flyway only — per-module under `src/main/resources/db/migration/`. Never edit a migration that has already been applied — add a new one.
- **Realm config:** edit `authorization-server/keycloak/realms/playground-realm.json` directly. It re-imports on container recreate (`docker compose down && up`), not on restart.
- **Brokering chain:** the `playground` realm is **brokered-only** — local Keycloak login is disabled and the browser flow auto-redirects to `playground-idp` (the upstream Spring IdP at `:9000`). Users authenticate there with username/password (later Smart-ID); Keycloak then issues its own tokens to the SPA. Don't reintroduce local Keycloak users or a username/password form on Keycloak's login page; the seed users live in idp-server's Postgres, not in Keycloak.

## Commands you'll need

```bash
# Recommended dev loop (infra in Docker, all three apps local — hot-reload)
docker compose up -d keycloak-postgres keycloak idp-postgres backend-postgres
( cd authorization-server/idp-server && ./gradlew bootRun )
( cd web-app/resource-backend && ./gradlew bootRun )
( cd web-app/client-frontend && npm run dev )

# Compile checks
( cd authorization-server/idp-server && ./gradlew compileJava )
( cd web-app/resource-backend && ./gradlew test )

# Frontend lint / build
( cd web-app/client-frontend && npm run lint && npm run build )

# Reset everything (wipes DB volumes — Keycloak realm re-imports, both backends' Flyway re-runs, idp-server regenerates signing key)
docker compose down -v
```

## Gotchas

- **Vite envs are build-time.** `VITE_*` values in `docker-compose.yml` only apply when you `docker compose build` the frontend image. For local `npm run dev`, edit `.env`/`.env.local` instead.
- **Account provisioning is JIT via the SPA.** First sign-in routes the user to `/register`, which POSTs `/api/user/register`; subsequent logins fire `/api/user/sync` to mirror JWT claims into the row. Keycloak is canonical for name/email/username; `custom_data` (JSONB) is app-owned and never touched by sync. Don't reintroduce a Flyway-seeded user row — that pattern was removed in V3.
- **Three Postgres instances.** `keycloak-postgres` (Keycloak's own state), `idp-postgres` (idp-server user accounts), `backend-postgres` (resource-server app data). Do not merge them; one DB per component is part of the playground's pedagogy.
- **idp-server signing key persistence.** The RSA key idp-server signs tokens with lives at `authorization-server/idp-server/keys/signing-key.json` in JWKS format. The file is gitignored and regenerated on first boot when absent. If you rotate it, Keycloak's cached JWKS must also expire (or restart Keycloak).
- **Docker hostname duality.** Inside containers Keycloak reaches the locally-running idp-server via `host.docker.internal:9000` (Docker Desktop wires this; Linux uses the `host-gateway` `extra_hosts` entry already in `docker-compose.yml`). The browser reaches it via `localhost:9000`. The realm config uses `localhost:9000` for browser-facing URLs and `host.docker.internal:9000` for server-to-server URLs; the issuer claim is `http://localhost:9000`. Don't conflate these.
- **JWT validation is signature-based via JWKS**, not introspection. The backend never calls Keycloak per-request. Keep it that way.
