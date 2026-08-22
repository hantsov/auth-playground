# Playground Identity Provider

Custom OIDC Identity Provider built on **Spring Authorization Server 7** (which now lives inside Spring Security 7). Keycloak federates to this app as an upstream IdP — Keycloak remains the realm-level authorization server that issues tokens to the SPA, but the actual user authentication (username/password, later Smart-ID) happens here.

```
SPA → Keycloak (issues JWT) → idp-server  (handles user login)
                                ├─ username + password   (live)
                                └─ Smart-ID              (placeholder)
                                     │
                                     └─ reads credentials from user-data-master
```

## No database, on purpose

This server **stores nothing about users**. No datasource, no JPA, no Flyway. Credentials and person attributes live in [user-data-master](../../internal-services/user-data-master-app/README.md), and are fetched over HTTP on each login using a `client_credentials` token from Keycloak's `playground-services` realm.

That is the **user-federation** pattern: an IdP with no local user store, reading from an external directory on the fly. It is what Keycloak's own `UserStorageProvider` SPI does, and what every Spring Security + LDAP deployment does. Two consequences worth knowing:

- **The master is tier-0.** Master down means nobody logs in, anywhere. In the LDAP world that is understood and priced in — directories get replication, HA, and a tighter SLO than anything they serve.
- **The master is a store, not a verifier.** It returns the BCrypt hash; *this* server does the comparison. A `POST /credentials/verify` on the master would drag authentication policy (lockout, attempt counting, `acr` determination) into a service whose job is holding records — and would be asymmetric anyway, since the Smart-ID path has no secret to verify at all.

The circularity is only apparent: this server gets its service token from Keycloak, and Keycloak brokers user logins here. `client_credentials` involves no user and no brokering, so it resolves entirely inside Keycloak's own client registry, in a different realm.

## Tech stack

- Java 25
- Spring Boot 4.0
- Spring Security 7 / Spring Authorization Server 7
- Spring Security OAuth2 Client (for the `client_credentials` call to the master)
- Thymeleaf (server-rendered login page)
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
│   │   │       ├── JwkConfig.java                   # RSA key load/generate from disk
│   │   │       ├── OidcClaimsCustomizer.java        # ID token claims, incl. acr/amr
│   │   │       └── UserMasterClientConfig.java      # client_credentials + RestClient
│   │   └── features/
│   │       └── users/
│   │           ├── controller/LoginController.java
│   │           ├── client/
│   │           │   ├── UserMasterClient.java              # the only route to user data
│   │           │   ├── UserCredentialResponse.java
│   │           │   ├── UserDataResponse.java
│   │           │   └── UserMasterUnavailableException.java
│   │           └── service/
│   │               ├── UserDataDetailsService.java   # bridges to Spring Security
│   │               └── UserDataDetails.java          # principal; its name IS the `sub`
│   └── resources/
│       ├── application.yml
│       ├── templates/login.html                      # Thymeleaf method picker
│       └── static/css/login.css
├── keys/                                              # gitignored, generated on first boot
│   └── signing-key.json                               # RSA private key as JWKS
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Running locally

The IdP needs **Keycloak** (for its service token) and **user-data-master** (for user data) reachable. It has no database of its own.

```bash
# From repo root
docker compose up -d keycloak-postgres keycloak user-master-postgres
( cd internal-services/user-data-master-app && ./gradlew bootRun )

# Then in this folder
./gradlew bootRun
```

It will *start* without either — there is no startup-time dependency, deliberately (`token-uri` rather than `issuer-uri`, so no OIDC discovery at bean creation). It just cannot authenticate anyone until both are up.

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

Seeded by `UserSeedRunner` in **user-data-master-app**, not here — this server has no user store. Passwords are hashed with BCrypt at runtime rather than frozen into a Flyway migration.

## What goes in the ID token

| Claim | Value | Notes |
|---|---|---|
| `sub` | the master's `users.id` (UUID) | Derived from `UserDataDetails`, whose username *is* that UUID. This is the identifier Keycloak's federated identity link is keyed on — never let it become a username. |
| `acr` | `weak` | Password login. Smart-ID becomes `strong` in Phase 2. |
| `amr` | `["pwd"]` | Smart-ID adds `["smartid"]`. |
| `email_verified` | the real column value | Not a hardcoded `true`. An IdP must not assert a check nobody performed. |
| `preferred_username`, `name`, `given_name`, `family_name`, `email` | from the master | Scope-gated on `profile` / `email`. |

`acr` and `amr` are not scope-gated — they describe the authentication event itself rather than profile data the relying party asked for.

**One master call per login.** The credential lookup returns the person's attributes alongside the hash, and they ride forward on the authenticated principal. Token issuance is a *different HTTP request* (Keycloak calling the token endpoint back-channel, after a browser redirect), so fetching them again there would be a second round trip on the hot path.

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
| `PLAYGROUND_IDP_USER_MASTER_BASE_URL` | `http://localhost:9100` |
| `PLAYGROUND_IDP_MASTER_CLIENT_ID` | `idp-server` |
| `PLAYGROUND_IDP_MASTER_CLIENT_SECRET` | `idp-server-secret` |
| `PLAYGROUND_IDP_KEYCLOAK_TOKEN_URI` | `http://localhost:8080/realms/playground-services/protocol/openid-connect/token` |
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
