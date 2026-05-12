# Playground Web App's Resource Server

OAuth2 **Resource Server** for the auth-playground project. Validates JWTs issued by Keycloak and exposes a small REST API backed by PostgreSQL.

## Tech stack

- Java 25
- Spring Boot 4.0 (Spring Security 7, Hibernate 7)
- Spring Boot OAuth2 Resource Server starter
- Spring Data JPA + Flyway (schema migrations)
- PostgreSQL 16
- Gradle 9.5 (Kotlin DSL)
- Lombok

## Project layout

Package convention: **package by feature at the root, by layer within a feature**. Two top-level packages under `ee.authplayground.resourceserver`:

- `appcore/` — application-wide plumbing (security, future cross-cutting config). Nothing here is tied to a single domain.
- `features/<domain>/` — one package per domain feature, layered inside (`controller`, `service`, `repository`, `entity`). When a feature outgrows a single directory, sub-package by *concept* (e.g. `features.user.profile`), never by adding more layer dirs.

Class names keep the feature prefix even when redundant with the package (`UserData`, `UserDataRepository`) — they read fine in isolation and across imports.

```
resource-backend/
├── src/main/
│   ├── java/ee/authplayground/resourceserver/
│   │   ├── ResourceServerApplication.java
│   │   ├── appcore/
│   │   │   └── security/SecurityConfig.java        # OAuth2 + CORS + role mapping
│   │   └── features/
│   │       └── user/                               # account record + JWT-side reconciliation
│   │           ├── controller/UserController.java
│   │           ├── service/UserService.java
│   │           ├── repository/UserDataRepository.java
│   │           └── entity/{UserData,UserCustomData}.java
│   └── resources/
│       ├── application.yml
│       └── db/migration/
│           ├── V1__init_user_data.sql
│           └── V2__add_last_synced_at.sql
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile
└── README.md
```

## Running locally

The backend needs Keycloak and `backend-postgres` running. Easiest path: start the infra via the root compose file, then run the JVM locally for hot-reload.

```bash
# From repo root
docker compose up -d keycloak-postgres keycloak backend-postgres

# Then in this folder
./gradlew bootRun
```

The app listens on **http://localhost:8081**.

For a fully containerized run, see the root README.

### Environment variables

`application.yml` has sensible localhost defaults; override only when needed (the docker-compose `resource-backend` service does this so it can address sibling containers):

| Variable | Default |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/appdb` |
| `SPRING_DATASOURCE_USERNAME` | `appuser` |
| `SPRING_DATASOURCE_PASSWORD` | `apppass123` |
| `KEYCLOAK_ISSUER_URI` | `http://localhost:8080/realms/playground` |
| `KEYCLOAK_JWK_SET_URI` | `…/protocol/openid-connect/certs` |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` |

## API

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/actuator/health` | public | health check |
| GET | `/actuator/info` | public | build info |
| GET | `/api/user` | `ROLE_USER` | returns the account row matched by `sub`. 404 if not yet provisioned. |
| POST | `/api/user/register` | `ROLE_USER` | provisions the row from the validated JWT's claims. 409 if it already exists. Empty body. |
| POST | `/api/user/sync` | `ROLE_USER` | mirrors current JWT claims into the existing row (Keycloak is canonical for name / email / username; `custom_data` is untouched). 404 if not yet provisioned. Empty body. |

## Security model

- JWT signature verified against Keycloak's JWKS endpoint.
- `realm_access.roles` claim → mapped to `ROLE_*` Spring authorities by `KeycloakRoleConverter` in `SecurityConfig`.
- Stateless — no HTTP session, no CSRF (Bearer token auth).
- CORS origins driven by `APP_CORS_ALLOWED_ORIGINS`; `Authorization` and `Content-Type` headers only.

## Schema migrations

Flyway owns the schema. `ddl-auto: validate` so JPA only checks alignment.

To add a column or table, drop a `V<n>__<description>.sql` into `src/main/resources/db/migration/` — it runs on next boot. Never edit an applied migration; add a new one.

## Testing the JWT flow manually

```bash
# 1. Get a token (password grant — only enabled for the demo client)
TOKEN=$(curl -sX POST http://localhost:8080/realms/playground/protocol/openid-connect/token \
  -d "client_id=react-client" \
  -d "username=testuser" \
  -d "password=test123" \
  -d "grant_type=password" | jq -r .access_token)

# 2. Hit the protected endpoint
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/user
```

If `/api/user` returns 404 with "User not registered", the account hasn't been provisioned yet. Either sign in via the SPA at <http://localhost:5173> (which walks you through `/register`) or POST directly:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/user/register
```
