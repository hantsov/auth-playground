# AuthN + AuthZ Playground

A self-contained playground for experimenting with OAuth2 / OpenID Connect, anchored on **Keycloak** as the authorization server. The repository contains everything needed to bring up a full local stack — auth server, resource server, SPA client, databases — with `docker compose up`.

The project name is intentionally generic. Keycloak is the *current* implementation of the OIDC role; the structure leaves room to swap or add other identity providers later.

## What's in here

- **Keycloak** authorization server with a pre-configured realm (`playground`), one public client (`react-client`), one test user, and a `USER` realm role.
- **resource-backend** — Spring Boot 4 / Java 25 OAuth2 resource server. Validates JWTs against Keycloak's JWKS, exposes a small REST API, persists data via Flyway-managed PostgreSQL.
- **client-frontend** — React 19 / Vite 7 SPA acting as an OAuth2 public client. Uses Authorization Code + PKCE; talks to `keycloak-js` directly through a custom `AuthProvider` context.
- Two PostgreSQL instances — one for Keycloak's own state, one for the resource-backend's app data.

## Architecture

```
                ┌──────────────────────┐    JDBC    ┌─────────────────────┐
                │  Keycloak 26         │◄──────────►│  keycloak-postgres  │
                │  (auth server)       │            │  (Keycloak state)   │
                └────────┬─────────────┘            └─────────────────────┘
                         ▲ ▲
        OIDC / PKCE      │ │ JWKS
        ┌────────────────┘ └───────────────┐
        │                                  │
        │                                  │
┌───────┴─────────┐   Bearer JWT   ┌───────┴──────────┐    JDBC    ┌─────────────────────┐
│ client-frontend │───────────────►│ resource-backend │◄──────────►│  backend-postgres   │
│  (React SPA,    │  REST /api/*   │  (Spring Boot,   │            │  (app data, Flyway) │
│   public client)│                │   resource srv.) │            └─────────────────────┘
└─────────────────┘                └──────────────────┘
```

**Flow:**
1. Browser hits client-frontend → `keycloak-js` checks for an existing session; if none, redirects to Keycloak.
2. User authenticates → Keycloak issues ID + access + refresh tokens via Authorization Code with PKCE.
3. SPA calls the resource-backend with `Authorization: Bearer <access_token>`.
4. resource-backend validates the JWT signature against Keycloak's JWKS endpoint and maps `realm_access.roles` to Spring Security authorities.
5. Protected endpoints query backend-postgres and return data scoped to the authenticated user.

## Tech stack

| Layer | Tech |
|---|---|
| Auth server | Keycloak 26.4 |
| Resource server | Java 25, Spring Boot 4.0 (Spring Security 7, Hibernate 7), Gradle 9.5 (Kotlin DSL), Flyway |
| Client | React 19, Vite 7, React Router 7, keycloak-js 26, Axios |
| Databases | PostgreSQL 16 (×2) |
| Orchestration | Docker Compose v2 |

## Folder structure

```
auth-playground/
├── docker-compose.yml             # full-stack orchestration
├── keycloak/
│   └── realms/
│       └── playground-realm.json  # imported on Keycloak startup
├── web-app/
│   ├── client-frontend/           # OAuth2 public client (React SPA)
│   │   └── README.md
│   └── resource-backend/          # OAuth2 resource server (Spring Boot)
│       └── README.md
└── README.md
```

`keycloak/realms/` is mounted into the Keycloak container at `/opt/keycloak/data/import`; every JSON file in it gets imported on first boot. To add another realm, drop a JSON file alongside `playground-realm.json`.

## Ports

| Service | URL |
|---|---|
| Keycloak | http://localhost:8080 |
| resource-backend | http://localhost:8081 |
| client-frontend | http://localhost:5173 |
| backend-postgres | localhost:5432 |

## Default credentials

| Where | User | Password |
|---|---|---|
| Keycloak admin console | `admin` | `admin` |
| `playground` test user | `testuser` | `test123` |
| backend-postgres | `appuser` | `apppass123` |
| keycloak-postgres | `keycloak` | `keycloak123` |

These are intentionally weak — local dev only.

---

## Running locally

Infrastructure (Keycloak + both databases) always runs in Docker. The backend and frontend can each run locally or containerized, independently.

### Step 0 — start the infra

```bash
docker compose up -d keycloak-postgres keycloak backend-postgres
docker compose logs -f keycloak    # ready when it logs "Running the server in development mode"
```

### Mode 1 — everything containerized

Smoke test the whole stack. No hot-reload anywhere.

```bash
docker compose up --build
```

> Vite caveat: `VITE_*` env vars are baked at build time, so values in `docker-compose.yml` only apply when you actually `docker compose build` the frontend. The defaults compiled into the bundle match the local stack, so this works in practice.

### Mode 2 — infra in Docker, backend + frontend local *(recommended for development)*

Hot-reload on both. Backend uses Spring DevTools, frontend uses Vite HMR.

```bash
# After Step 0 — in two separate terminals:
( cd web-app/resource-backend && ./gradlew bootRun )
( cd web-app/client-frontend && npm install && npm run dev )
```

### Mode 3 — infra + backend containerized, frontend local

Iterate on the SPA without touching the JVM.

```bash
docker compose up -d keycloak-postgres keycloak backend-postgres resource-backend
( cd web-app/client-frontend && npm run dev )
```

### Mode 4 — infra containerized, backend local, frontend containerized

Iterate on the backend while testing against the production-style nginx-served frontend.

```bash
docker compose up -d keycloak-postgres keycloak backend-postgres
docker compose up -d --build client-frontend
( cd web-app/resource-backend && ./gradlew bootRun )
```

### Common compose commands

```bash
docker compose up -d --build resource-backend   # rebuild + restart one service
docker compose logs -f resource-backend         # tail logs
docker compose down                             # stop everything (keep volumes)
docker compose down -v                          # stop + wipe volumes (fresh start)
```

---

## First-run

The first time you sign in as `testuser`, the SPA detects that the resource-backend has no row for your Keycloak `sub` and routes you to `/register`. Confirm the imported claims and the row is provisioned via `POST /api/user/register`. Every subsequent login fires `POST /api/user/sync` in the background so the row mirrors the latest JWT claims (name, email, username); the Token Inspector page shows the `lastSyncedAt` timestamp.

Smoke test: `curl http://localhost:8081/actuator/health` should return `{"status":"UP"}`. Sign in via the SPA → confirm registration → land on `/dashboard`. The Token Inspector (user menu → Token Inspector) shows the JWT and the synced DB row side by side.

---

## Subproject docs

For deeper details on each app — environment variables, security model, build/run commands, internal layout — see:

- [`web-app/resource-backend/README.md`](web-app/resource-backend/README.md)
- [`web-app/client-frontend/README.md`](web-app/client-frontend/README.md)
