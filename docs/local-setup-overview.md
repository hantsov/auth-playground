# Local setup overview

Ports, credentials, and the full set of run-mode combinations for the stack. For the quick path (start infra, run backend + frontend locally, first-run check), see the main [README](../README.md#quickstart-for-local).

## Ports

| Service | URL |
|---|---|
| Keycloak | http://localhost:8080 |
| idp-server | http://localhost:9000 |
| user-data-master | http://localhost:9100 |
| resource-backend | http://localhost:8081 |
| client-frontend | http://localhost:5173 |
| backend-postgres | localhost:5432 |
| user-master-postgres | localhost:5434 |

Port 5433 is deliberately unused — it belonged to the retired `idp-postgres`, and reusing it would let a stale volume answer on a port that has been re-pointed.

## Default credentials

| Where | User | Password |
|---|---|---|
| Keycloak admin console | `admin` | `admin` |
| Login as Conan | `conan` | `conan123` |
| Login as Matrix | `matrix` | `matrix123` |
| backend-postgres | `appuser` | `apppass123` |
| keycloak-postgres | `keycloak` | `keycloak123` |
| user-master-postgres | `masteruser` | `masterpass123` |

The two login users live in **user-master-postgres**, seeded on first boot by `UserSeedRunner` in `user-data-master-app`. idp-server has no user store of its own.

### Service clients (realm `playground-services`)

| Client | Secret | Scopes |
|---|---|---|
| `idp-server` | `idp-server-secret` | `credentials:read`, `customer:read` |
| `resource-backend` | `resource-backend-secret` | `customer:read`, `customer:write` |
| `kc-enricher` | `kc-enricher-secret` | `customer:read` |

`credentials:read` is held by exactly one client. That is the point of the separate realm: "only the IdP may see password hashes" is a config fact you can read out of the realm JSON rather than a convention someone has to remember.

These are intentionally weak — local dev only.

## Running locally — other modes

Infrastructure (Keycloak + all three databases) always runs in Docker. The idp-server, user-data-master-app, resource-backend, and frontend can each run locally or containerized, independently. **idp-server and user-data-master-app are not yet dockerized** — run them locally via Gradle for now.

Start `user-data-master-app` before idp-server: it holds every user record and credential in the system, so nobody can log in without it.

The README covers the recommended dev setup (infra in Docker, everything else local). The modes below cover the other combinations.

### Mode 1 — everything containerized except idp-server

Closest-to-prod smoke test available today. Keycloak, the resource-backend, and the nginx-served frontend all run in containers; idp-server and user-data-master-app still need to run locally via Gradle until they're dockerized (see [BACKLOG.md](../BACKLOG.md)).

```bash
docker compose up --build                                          # everything in compose
( cd internal-services/user-data-master-app && ./gradlew bootRun )  # separate terminal
( cd authorization-server/idp-server && ./gradlew bootRun )         # separate terminal
```

> Vite caveat: `VITE_*` env vars are baked at build time, so values in `docker-compose.yml` only apply when you actually `docker compose build` the frontend. The defaults compiled into the bundle match the local stack, so this works in practice.

### Mode 3 — infra + resource-backend containerized, frontend local

Iterate on the SPA without rebuilding the resource server. idp-server runs locally as always.

```bash
docker compose up -d keycloak-postgres keycloak user-master-postgres backend-postgres resource-backend
( cd internal-services/user-data-master-app && ./gradlew bootRun )
( cd authorization-server/idp-server && ./gradlew bootRun )
( cd web-app/client-frontend && npm run dev )
```

### Mode 4 — infra containerized, resource-backend local, frontend containerized

Iterate on the resource-backend against the production-style nginx-served frontend.

```bash
docker compose up -d keycloak-postgres keycloak user-master-postgres backend-postgres
docker compose up -d --build client-frontend
( cd internal-services/user-data-master-app && ./gradlew bootRun )
( cd authorization-server/idp-server && ./gradlew bootRun )
( cd web-app/resource-backend && ./gradlew bootRun )
```

### Common compose commands

```bash
docker compose up -d --build resource-backend   # rebuild + restart one service
docker compose logs -f resource-backend         # tail logs
docker compose down                             # stop everything (keep volumes)
docker compose down -v                          # stop + wipe volumes (fresh start)
```
