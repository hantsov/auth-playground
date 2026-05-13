# Playground Identity Provider

Custom OIDC Identity Provider built on **Spring Authorization Server 7** (which now lives inside Spring Security 7). Keycloak federates to this app as an upstream IdP — Keycloak remains the realm-level authorization server that issues tokens to the SPA, but the actual user authentication (username/password, later Smart-ID) happens here.

```
SPA → Keycloak (issues JWT) → idp-server  (handles user login)
                                ├─ username + password   (live)
                                └─ Smart-ID              (placeholder)
```

## Tech stack

- Java 25
- Spring Boot 4.0
- Spring Security 7 / Spring Authorization Server 7
- Thymeleaf (server-rendered login page)
- Spring Data JPA + Flyway
- PostgreSQL 16
- Gradle 9.5 (Kotlin DSL)
- Lombok

## Project layout

Same convention as the resource-backend: `appcore` for cross-cutting plumbing, `features/<domain>` for domain code with package-by-layer inside.

```
idp-server/
├── src/main/
│   ├── java/ee/authplayground/idpserver/
│   │   ├── IdpServerApplication.java
│   │   ├── appcore/
│   │   │   └── security/
│   │   │       ├── AuthorizationServerConfig.java   # OIDC OP wiring, registered clients
│   │   │       ├── DefaultSecurityConfig.java       # form-login filter chain
│   │   │       └── JwkConfig.java                   # RSA key load/generate from disk
│   │   └── features/
│   │       └── users/
│   │           ├── controller/LoginController.java
│   │           ├── entity/UserData.java
│   │           ├── repository/UserDataRepository.java
│   │           └── service/
│   │               ├── UserDataDetailsService.java   # bridges to Spring Security
│   │               └── UserSeedRunner.java           # seeds Conan + Matrix on boot
│   └── resources/
│       ├── application.yml
│       ├── templates/login.html                      # Thymeleaf method picker
│       ├── static/css/login.css
│       └── db/migration/
│           └── V1__init_users.sql
├── keys/                                              # gitignored, generated on first boot
│   └── signing-key.json                               # RSA private key as JWKS
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Running locally

The IdP needs `idp-postgres` running. Easiest is via the root compose file:

```bash
# From repo root
docker compose up -d idp-postgres

# Then in this folder
./gradlew bootRun
```

Listens on **http://localhost:9000**.

## Endpoints

OIDC discovery, JWKS, authorization, token, userinfo are all served by Spring Authorization Server at standard paths:

| Path | Purpose |
|---|---|
| `/.well-known/openid-configuration` | Discovery document |
| `/oauth2/jwks` | Public JWKS for token signature verification |
| `/oauth2/authorize` | Authorization endpoint (browser hits this) |
| `/oauth2/token` | Token endpoint (Keycloak hits this server-to-server) |
| `/userinfo` | OIDC userinfo |
| `/login` | Custom Thymeleaf login page (GET form, POST credentials) |
| `/actuator/health` | Health check |

## Seeded users

| username | name | email | password |
|---|---|---|---|
| `conan` | Conan Barbarian | `conan@playground.local` | `conan123` |
| `matrix` | John Matrix | `matrix@playground.local` | `matrix123` |

Seeded by `UserSeedRunner` on first boot. Passwords are hashed with BCrypt at runtime — not stored in Flyway migrations.

## Registered clients

Hardcoded in `AuthorizationServerConfig` (in-memory store):

| client_id | secret | redirect URI |
|---|---|---|
| `kc-broker-client` | `kc-broker-secret` | `http://localhost:8080/realms/playground/broker/playground-idp/endpoint` |

This is Keycloak's identity inside the IdP. The same secret appears in `authorization-server/keycloak/realms/playground-realm.json` so both sides agree.

## Token signing keys

The RSA private key used to sign issued tokens lives at `keys/signing-key.json` in JWKS format (`d/p/q/dp/dq/qi` private params + `n/e` public). On first startup `JwkConfig` checks whether the file exists:

- **Exists** → load it and use it.
- **Missing** → generate a fresh 2048-bit RSA key pair, write to disk, use it.

`keys/` is gitignored. Key rotation = `rm keys/signing-key.json && ./gradlew bootRun`. Note that rotation requires Keycloak to pick up the new JWKS — restarting Keycloak (or waiting for its JWKS cache to expire) does the trick.

For Docker: bind-mount `./keys` into the container so the key survives `docker compose down`. Currently the IdP runs locally; once dockerized, this gets wired up.

## Environment variables

`application.yml` has sensible localhost defaults. Override only when needed:

| Variable | Default |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5433/idpdb` |
| `SPRING_DATASOURCE_USERNAME` | `idpuser` |
| `SPRING_DATASOURCE_PASSWORD` | `idppass123` |
| `PLAYGROUND_IDP_ISSUER_URL` | `http://localhost:9000` |
| `PLAYGROUND_IDP_SIGNING_KEY_PATH` | `./keys/signing-key.json` |
| `PLAYGROUND_IDP_KEYCLOAK_CLIENT_ID` | `kc-broker-client` |
| `PLAYGROUND_IDP_KEYCLOAK_CLIENT_SECRET` | `kc-broker-secret` |
| `PLAYGROUND_IDP_KEYCLOAK_REDIRECT_URI` | `http://localhost:8080/realms/playground/broker/playground-idp/endpoint` |

## Issuer URL — the Docker gotcha

OIDC validation requires the JWT's `iss` claim to *exactly* match the issuer the relying party expects. With this stack:

- The **browser** reaches the IdP at `http://localhost:9000`.
- **Keycloak inside a container** reaches the IdP at `http://host.docker.internal:9000` (Docker Desktop wires this on Mac/Windows; Linux needs `extra_hosts: host.docker.internal:host-gateway` which is already in `docker-compose.yml`).
- The IdP must advertise itself with **one** issuer URL.

We use `http://localhost:9000` as the canonical issuer (Spring IdP's `playground.idp.issuer-url`). In the realm config, Keycloak's manual URL overrides point the *server-to-server* endpoints (`tokenUrl`, `jwksUrl`, `userInfoUrl`) at `host.docker.internal:9000` while the *browser-facing* `authorizationUrl` and `issuer` stay at `localhost:9000`. The `iss` claim in issued tokens equals `localhost:9000`, which matches what Keycloak expects.

If you ever see `iss` mismatch errors during federation, this is where to look.

## Smart-ID — placeholder

The login page renders Smart-ID's country selector + national ID input but the submit button is disabled with "Coming soon." Wiring this up means integrating with Smart-ID's relying-party REST API ([docs](https://www.smart-id.com/e-service-providers/integration-process/)) on the IdP side, not on Keycloak — the educational point is that Smart-ID is an *authentication method*, not an OIDC provider, so it belongs inside an IdP that handles the protocol translation.
