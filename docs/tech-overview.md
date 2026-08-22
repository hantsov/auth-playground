# Architecture & tech overview

## Architecture

```
   ┌──────────────────────┐
   │ user-master-postgres │
   │ (identity + creds)   │
   └──────────▲───────────┘
              │ JDBC
   ┌──────────┴───────────┐
   │  user-data-master    │  golden record — the only store of users and
   │  (resource server)   │  credentials anywhere in the system
   └──────────▲───────────┘
              │ client_credentials token from realm `playground-services`
              │ scopes: credentials:read, customer:read
              │
   ┌──────────┴───────────┐   Auth Code      ┌──────────────────────┐                  ┌──────────────────────┐
   │    idp-server        │   (broker)       │  keycloak-postgres   │                  │   backend-postgres   │
   │ (Spring Auth Server, │◄───────────────┐ │  (Keycloak state)    │                  │  (app data, Flyway)  │
   │  custom OIDC OP —    │                │ └──────────▲───────────┘                  └──────────▲───────────┘
   │  no database)        │                │            │ JDBC                                    │ JDBC
   └──────────────────────┘                │ ┌──────────┴───────────┐                  ┌──────────┴───────────┐
                                           └─│    Keycloak 26       │                  │  resource-backend    │
                                             │ realm: playground    │                  │ (Spring Boot OAuth2  │
                                             │   — brokered-only    │                  │  resource server)    │
                                             │ realm: playground-   │                  └──────────▲───────────┘
                                             │   services — M2M     │                             │
                                             └──────────▲───────────┘                             │
                                                        │ OIDC + PKCE                             │ Bearer JWT
                                                        │ (browser)                               │ REST /api/*
                                                        │                                         │
                                             ┌──────────┴─────────────────────────────────────────┴──────────┐
                                             │           client-frontend (React SPA, public client)          │
                                             └───────────────────────────────────────────────────────────────┘
```

**Flow:**
1. Browser hits client-frontend → `keycloak-js` checks for an existing session; if none, redirects to Keycloak.
2. Keycloak's `playground` realm has local login disabled and a single upstream IdP configured (`playground-idp`). The browser is auto-redirected to **idp-server** at `/oauth2/authorize`.
3. The user submits credentials on idp-server's login page (username/password, eventually Smart-ID).
4. idp-server holds no user data, so it fetches the credential from **user-data-master** in a single call — presenting a `client_credentials` token it obtained from Keycloak's `playground-services` realm. The response carries the BCrypt hash *and* the person's attributes.
5. idp-server does the BCrypt comparison itself (the master is a store, not a verifier) and builds the authenticated principal with the master's `users.id` as its name — which is what makes `sub` a stable UUID. The person attributes ride along on that principal, so token issuance needs no second call to the master.
6. idp-server issues an OIDC token to Keycloak via Authorization Code (confidential client, server-to-server), carrying `sub`, profile/email claims, and `acr` / `amr` describing how the user authenticated.
7. Keycloak validates the upstream token, creates a shadow user on first login (via the trimmed-down "first broker login" flow) linked by federated identity to that `sub`, and issues its **own** tokens to the SPA via Authorization Code + PKCE.
8. SPA calls the resource-backend with `Authorization: Bearer <access_token>`.
9. resource-backend validates the JWT signature against Keycloak's JWKS endpoint and maps `realm_access.roles` to Spring Security authorities.
10. Protected endpoints query backend-postgres and return data scoped to the authenticated user.

The resource-backend doesn't know upstream brokering exists — it only sees Keycloak-issued JWTs. The Token Inspector page surfaces the JWT's `identity_provider` claim, which is how you can tell brokering happened.

**Two subject identifiers, and they are not the same.** idp-server's `sub` is the master's `users.id`; Keycloak mints its *own* `sub` for its shadow user and puts that in the SPA's tokens. The link between them is Keycloak's federated identity record, which stores the master's UUID. That is deliberate — each authorization server owns its own subject namespace — and it is why the master's UUID has to be stable: it is the join key for the whole federation.

## Tech stack

| Layer | Tech                                                                                                                     |
|---|--------------------------------------------------------------------------------------------------------------------------|
| Auth server | Keycloak 26.4 — realm `playground` (brokered-only, local login disabled) + realm `playground-services` (M2M)             |
| Upstream IdP | Java 25, Gradle 9.5 (Kotlin DSL), Spring Boot 4.0, Spring Security 7 with Spring Authorization Server, Thymeleaf. No datasource — reads from the user data master |
| User data master | Java 25, Gradle 9.5 (Kotlin DSL), Spring Boot 4.0, Spring Security 7 with OAuth2 Resource Server, Flyway                |
| Resource server | Java 25, Gradle 9.5 (Kotlin DSL), Spring Boot 4.0, Spring Security 7 with OAuth2 Resource Server, Flyway                 |
| Client | React 19, Vite 7, React Router 7, keycloak-js 26, Axios                                                                  |
| Databases | PostgreSQL 16 (×3 — one each for Keycloak, user-data-master, resource-backend)                                           |
| Orchestration | Docker Compose v2                                                                                                        |
