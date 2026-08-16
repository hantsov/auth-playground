# Architecture & tech overview

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

**Flow:**
1. Browser hits client-frontend → `keycloak-js` checks for an existing session; if none, redirects to Keycloak.
2. Keycloak's realm has local login disabled and a single upstream IdP configured (`playground-idp`). The browser is auto-redirected to **idp-server** at `/oauth2/authorize`.
3. The user authenticates on idp-server's login page (username/password, eventually Smart-ID). idp-server issues an OIDC token to Keycloak via Authorization Code (confidential client, server-to-server).
4. Keycloak validates the upstream token, creates a shadow user on first login (via the trimmed-down "first broker login" flow), and issues its **own** tokens to the SPA via Authorization Code + PKCE.
5. SPA calls the resource-backend with `Authorization: Bearer <access_token>`.
6. resource-backend validates the JWT signature against Keycloak's JWKS endpoint and maps `realm_access.roles` to Spring Security authorities.
7. Protected endpoints query backend-postgres and return data scoped to the authenticated user.

The resource-backend doesn't know upstream brokering exists — it only sees Keycloak-issued JWTs. The Token Inspector page surfaces the JWT's `identity_provider` claim, which is how you can tell brokering happened.

## Tech stack

| Layer | Tech                                                                                                                     |
|---|--------------------------------------------------------------------------------------------------------------------------|
| Auth server | Keycloak 26.4 (brokered-only — local login disabled)                                                                     |
| Upstream IdP | Java 25, Gradle 9.5 (Kotlin DSL), Spring Boot 4.0, Spring Security 7 with Spring Authorization Server, Thymeleaf, Flyway |
| Resource server | Java 25, Gradle 9.5 (Kotlin DSL), Spring Boot 4.0, Spring Security 7 with OAuth2 Resource Server, Flyway                 |
| Client | React 19, Vite 7, React Router 7, keycloak-js 26, Axios                                                                  |
| Databases | PostgreSQL 16 (×3 — one each for Keycloak, idp-server, resource-backend)                                                 |
| Orchestration | Docker Compose v2                                                                                                        |
