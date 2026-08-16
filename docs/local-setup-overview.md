# Local setup overview

Ports, credentials, and the full set of run-mode combinations for the stack. For the quick path (start infra, run backend + frontend locally, first-run check), see the main [README](../README.md#quickstart-for-local).

## Ports

| Service | URL |
|---|---|
| Keycloak | http://localhost:8080 |
| idp-server | http://localhost:9000 |
| resource-backend | http://localhost:8081 |
| client-frontend | http://localhost:5173 |
| backend-postgres | localhost:5432 |
| idp-postgres | localhost:5433 |

## Default credentials

| Where | User | Password |
|---|---|---|
| Keycloak admin console | `admin` | `admin` |
| idp-server (Conan) | `conan` | `conan123` |
| idp-server (Matrix) | `matrix` | `matrix123` |
| backend-postgres | `appuser` | `apppass123` |
| keycloak-postgres | `keycloak` | `keycloak123` |
| idp-postgres | `idpuser` | `idppass123` |

These are intentionally weak — local dev only.

## Running locally — other modes

Infrastructure (Keycloak + all three databases) always runs in Docker. The idp-server, resource-backend, and frontend can each run locally or containerized, independently. **idp-server is not yet dockerized** — run it locally via Gradle for now.

The README covers the recommended dev setup (infra in Docker, everything else local). The modes below cover the other combinations.

### Mode 1 — everything containerized except idp-server

Closest-to-prod smoke test available today. Keycloak, the resource-backend, and the nginx-served frontend all run in containers; idp-server still needs to run locally via Gradle until it's dockerized (see [BACKLOG.md](../BACKLOG.md)).

```bash
docker compose up --build                                  # everything in compose
( cd authorization-server/idp-server && ./gradlew bootRun ) # idp-server, separate terminal
```

> Vite caveat: `VITE_*` env vars are baked at build time, so values in `docker-compose.yml` only apply when you actually `docker compose build` the frontend. The defaults compiled into the bundle match the local stack, so this works in practice.

### Mode 3 — infra + resource-backend containerized, frontend local

Iterate on the SPA without rebuilding the resource server. idp-server runs locally as always.

```bash
docker compose up -d keycloak-postgres keycloak idp-postgres backend-postgres resource-backend
( cd authorization-server/idp-server && ./gradlew bootRun )
( cd web-app/client-frontend && npm run dev )
```

### Mode 4 — infra containerized, resource-backend local, frontend containerized

Iterate on the resource-backend against the production-style nginx-served frontend.

```bash
docker compose up -d keycloak-postgres keycloak idp-postgres backend-postgres
docker compose up -d --build client-frontend
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
