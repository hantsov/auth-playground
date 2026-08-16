# AuthN + AuthZ Playground For Spring + KC Ecosystem

A self-contained playground for experimenting with OAuth2 / OpenID Connect end-to-end. **Keycloak** sits at the centre as the realm-level authorization server — it issues OIDC tokens to the SPA and carries authorization data (roles) inside those tokens — but it does no authentication of its own. **Authentication is delegated** to a custom upstream identity provider built on Spring Authorization Server (where username/password lives today and Smart-ID will plug in). A Spring Boot **resource server** validates the issued access tokens to gate its REST API.

This setup leverages the **identity brokering** pattern. New authentication methods land on the IdP without touching Keycloak's realm config or the resource server.

## What's in here

- **Keycloak** as the realm-level authorization server. Pre-configured realm (`playground`), one public client (`react-client`), one upstream identity-provider entry (`playground-idp`), and a `USER` realm role. Brokered-only: Keycloak's own username/password login is disabled, and the browser auto-redirects to the upstream IdP.
- **idp-server** — custom upstream identity provider built on Spring Authorization Server 7 (Java 25, Spring Boot 4). Owns the actual user authentication: username/password is live, Smart-ID is stubbed with placeholder fields. Federated to Keycloak as an OIDC provider; users live in its own Postgres.
- **resource-backend** — Spring Boot 4 / Java 25 OAuth2 resource server. Validates JWTs against Keycloak's JWKS, exposes a small REST API, persists data via Flyway-managed PostgreSQL.
- **client-frontend** — React 19 / Vite 7 SPA acting as an OAuth2 public client. Uses Authorization Code + PKCE against Keycloak; talks to `keycloak-js` directly through a custom `AuthProvider` context.
- Three PostgreSQL instances — one each for Keycloak's own state, idp-server's user accounts, and the resource-backend's app data.

## Architecture

```
   ┌──────────────────────┐                  ┌──────────────────────┐                  ┌──────────────────────┐
   │    idp-postgres      │                  │  keycloak-postgres   │                  │   backend-postgres   │
   │  (user accounts)     │                  │  (Keycloak state)    │                  │  (app data, Flyway)  │
   └──────────▲───────────┘                  └──────────▲───────────┘                  └──────────▲───────────┘
              │ JDBC                                    │ JDBC                                    │ JDBC
              │                                         │                                         │
   ┌──────────┴───────────┐   Auth Code      ┌──────────┴───────────┐                  ┌──────────┴───────────┐
   │    idp-server        │   (broker)       │    Keycloak 26       │                  │  resource-backend    │
   │ (Spring Auth Server, │◄─────────────────│  (auth server,       │                  │ (Spring Boot OAuth2  │
   │  custom OIDC OP)     │                  │   brokered-only)     │                  │  resource server)    │
   └──────────────────────┘                  └──────────▲───────────┘                  └──────────▲───────────┘
                                                        │                                         │
                                                        │ OIDC + PKCE                             │ Bearer JWT
                                                        │ (browser)                               │ REST /api/*
                                                        │                                         │
                                             ┌──────────┴─────────────────────────────────────────┴──────────┐
                                             │           client-frontend (React SPA, public client)          │
                                             └───────────────────────────────────────────────────────────────┘
```

See [docs/tech-overview.md](docs/tech-overview.md) for the full request/token flow and tech stack.

## Folder structure

```
auth-playground/
├── docker-compose.yml                       # full-stack orchestration
├── authorization-server/
│   ├── keycloak/
│   │   └── realms/
│   │       └── playground-realm.json        # imported on Keycloak startup
│   └── idp-server/                          # custom upstream IdP (Spring Authorization Server)
│       └── README.md
├── web-app/
│   ├── client-frontend/                     # OAuth2 public client (React SPA)
│   │   └── README.md
│   └── resource-backend/                    # OAuth2 resource server (Spring Boot)
│       └── README.md
└── README.md
```

`authorization-server/keycloak/realms/` is mounted into the Keycloak container at `/opt/keycloak/data/import`; every JSON file in it gets imported on first boot. To add another realm, drop a JSON file alongside `playground-realm.json`.

`authorization-server/idp-server/` is a custom Spring-based OIDC OpenID Provider that handles user authentication. Keycloak brokers to it as an upstream IdP — it remains the authorization server issuing tokens to the SPA, but the actual login (username/password, later Smart-ID) happens on the IdP. See its [README](authorization-server/idp-server/README.md) for details.

## Quickstart for Local

Infrastructure (Keycloak + all three databases) always runs in Docker. The idp-server, resource-backend, and frontend can each run locally or containerized, independently. **idp-server is not yet dockerized** — run it locally via Gradle for now.

### Start the infra

```bash
docker compose up -d keycloak-postgres keycloak idp-postgres backend-postgres
docker compose logs -f keycloak    # ready when it logs "Running the server in development mode"
```

### Run backend + frontend outside Docker *(recommended for development)*

Hot-reload on all three apps. Backend uses Spring DevTools, frontend uses Vite HMR, idp-server restarts when you edit its sources.

```bash
# After starting the infra — in three separate terminals:
( cd authorization-server/idp-server && ./gradlew bootRun )
( cd web-app/resource-backend && ./gradlew bootRun )
( cd web-app/client-frontend && npm install && npm run dev )
```

### First-run

Smoke test: `curl http://localhost:8081/actuator/health` should return `{"status":"UP"}`. Sign in via the SPA → land on the IdP login page → enter `conan` / `conan123` → confirm registration → land on `/dashboard`. The Token Inspector (user menu → Token Inspector) shows the JWT (note the `identity_provider: playground-idp` claim) and the synced DB row side by side.

*The first time you sign in (as `conan` or `matrix`), Keycloak silently brokers to the upstream IdP, you authenticate there, Keycloak creates a shadow user, and the SPA lands on `/register`. At that point the resource-backend has no row for your Keycloak `sub`, so the SPA routes you through `POST /api/user/register` to confirm the imported claims. Every subsequent login fires `POST /api/user/sync` in the background so the row mirrors the latest JWT claims (name, email, username); the Token Inspector page shows the `lastSyncedAt` timestamp.*

For other run-mode combinations (fully containerized, mixed modes), ports, default credentials, and common compose commands, see [docs/local-setup-overview.md](docs/local-setup-overview.md).

---

## Subproject docs

For deeper details on each app — environment variables, security model, build/run commands, internal layout — see:

- [`authorization-server/idp-server/README.md`](authorization-server/idp-server/README.md)
- [`web-app/resource-backend/README.md`](web-app/resource-backend/README.md)
- [`web-app/client-frontend/README.md`](web-app/client-frontend/README.md)

For architecture, request/token flow, and the full tech stack, see [`docs/tech-overview.md`](docs/tech-overview.md). For ports, default credentials, and other local run modes, see [`docs/local-setup-overview.md`](docs/local-setup-overview.md).

Deferred work and known gaps are tracked in [`BACKLOG.md`](BACKLOG.md).
