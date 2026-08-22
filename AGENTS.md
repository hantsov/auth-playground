# AGENTS.md

Guidance for AI coding assistants (Claude Code, Cursor, Codex, etc.) working in this repo.

> Project overview, architecture, ports, run modes, and first-run setup live in [README.md](README.md). Read it before suggesting structural changes. Subprojects have their own READMEs ([idp-server](authorization-server/idp-server/README.md), [user-data-master-app](internal-services/user-data-master-app/README.md), [resource-backend](web-app/resource-backend/README.md), [client-frontend](web-app/client-frontend/README.md)) — consult them when working in those subtrees.
>
> Subproject-specific conventions for AI assistants live alongside each app's code: [resource-backend/AGENTS.md](web-app/resource-backend/AGENTS.md). Read the relevant one when working in that subtree (others will be added as conventions accumulate).

## What this project is

A **playground**, not a production system. Primary value is clarity: someone should be able to read this code and learn how authentication and authorization work end-to-end in OIDC + OAuth2 ecosystem. Optimize for that.

- Weak default credentials are intentional. Do not "harden" them.
- Verbose comments explaining auth concepts are intentional. Do not strip them as noise.
- Do not introduce abstractions or indirection that obscure the OAuth2 flow.

## Conventions

- **Versions are deliberately current** (Java 25, Spring Boot 4, Spring Security 7, React 19, etc. — full stack table in [README.md](README.md#tech-stack)). Do not downgrade to "more common" versions when adding deps; demonstrating modern Spring + React OIDC patterns is part of what the playground exists to show.

- **Repo layout is by architectural tier, not by deployable.** Root folders name tiers: `authorization-server/` (Keycloak + the upstream IdP), `internal-services/` (backend services belonging to neither the auth tier nor the web app — today just `user-data-master-app`), `web-app/` (the SPA and its resource server). A new service goes in the tier it belongs to; if none fits, that is a signal to add a tier, not to wedge it into the nearest folder.

- **Backend package layout:** each Spring Boot module roots at `ee.authplayground.<artifact>` (e.g. `ee.authplayground.resourceserver`, `ee.authplayground.idpserver`, `ee.authplayground.userdatamaster`). Under each root, two top-level packages:
  - `appcore` — application-wide plumbing (e.g. `appcore.security` holds the security configs). Anything cross-cutting that isn't tied to a domain belongs here.
  - `features.<domain>` — one package per domain feature. Inside a feature, package by layer (`controller / service / repository / entity`). When a feature outgrows a single directory, sub-package by *concept* (e.g. `features.users.profile`, `features.users.permissions`) — never by adding more layers.

  Class names intentionally retain feature context even when redundant with the package (`UserData`, `UserDataRepository`, `UserCustomData`). Don't strip the prefix.
- **Frontend:** auth state lives in a single `AuthProvider` context wrapping `keycloak-js`. Do not introduce a second auth library or state store for auth concerns.
- **DB migrations:** Flyway only — per-module under `src/main/resources/db/migration/`. **Edit migrations in place** rather than stacking corrective ones. This inverts the usual rule on purpose: nothing here is deployed, the databases are disposable, and the schema comments are a large part of what the playground teaches — a reader should find the current design in `V1`, not reconstruct it from a chain of `ALTER`s. Flyway checksums the file (comments included), so an edit means `docker compose down -v` before the next run. Add a new migration only when the user explicitly asks for versioned history.
- **Credentials vs. identity.** `user_credentials` holds **issued** credentials only — methods where *we* handed something out and hold a secret (password today; TOTP, WebAuthn later). **Inherent** methods, where an external authority holds the authenticator and we only need an identifier on the person row, get no credential row at all: Smart-ID resolves against `users.national_id` + `users.nationality`. The test for any new method is *does authenticating require something we store?* Do not re-add a `SMART_ID` credential type — it duplicates a derivable identifier and implies an enrolment step that does not exist.
- **Realm config:** edit the JSON under `authorization-server/keycloak/realms/` directly — `playground-realm.json` for customers, `playground-services-realm.json` for machine clients. Both re-import on container recreate (`docker compose down && up`), not on restart.
- **Brokering chain:** the `playground` realm is **brokered-only** — local Keycloak login is disabled and the browser flow auto-redirects to `playground-idp` (the upstream Spring IdP at `:9000`). Users authenticate there with username/password (later Smart-ID); Keycloak then issues its own tokens to the SPA. Don't reintroduce local Keycloak users or a username/password form on Keycloak's login page; the seed users live in **user-master-postgres**, reached by idp-server over HTTP.
- **Who owns user data:** `user-data-master-app` owns `users` and `user_credentials` and is the only place either exists. **idp-server has no datasource** — it reads credentials from the master on the fly (the user-federation pattern) and stores nothing. Do not give idp-server a database "for caching" or move person attributes into it; that is the exact confusion this layout was refactored to remove. resource-backend keeps owning `custom_data` and app feature data.
- **`sub` is the master's `users.id`.** idp-server builds its `UserDetails` with that UUID as the username, which is what Spring Authorization Server turns into `sub`, and what Keycloak's federated identity link is keyed on. Never make `sub` derive from a username, email, or anything else a person can change.
- **Two realms, and the split is load-bearing.** `playground-services` exists so machine principals (which Keycloak backs with real `service-account-*` user records) stay out of a realm whose premise is "no local users". `credentials:read` is granted to exactly one client there — that is how "only the IdP may see password hashes" becomes config rather than convention. Don't grant it more widely.

## Commands you'll need

```bash
# Recommended dev loop (infra in Docker, all four apps local — hot-reload)
docker compose up -d keycloak-postgres keycloak user-master-postgres backend-postgres
( cd internal-services/user-data-master-app && ./gradlew bootRun )   # start first — nobody logs in without it
( cd authorization-server/idp-server && ./gradlew bootRun )
( cd web-app/resource-backend && ./gradlew bootRun )
( cd web-app/client-frontend && npm run dev )

# Compile checks
( cd internal-services/user-data-master-app && ./gradlew compileJava )
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
- **Three Postgres instances.** `keycloak-postgres` (Keycloak's own state), `user-master-postgres` (users + credentials, port 5434), `backend-postgres` (resource-server app data). Do not merge them; one DB per component is part of the playground's pedagogy. Port 5433 is deliberately vacant — it belonged to the retired `idp-postgres`.
- **Audience validation is not automatic.** The master validates signature, issuer **and** `aud`. Spring's `JwtDecoder` does not check audience unless you add the validator, and Keycloak's default `aud` is not what you want — an explicit audience mapper on the service client scopes emits `aud: user-data-master`. If you add a service-to-service hop, do both halves.
- **`acr` has exactly one owner.** idp-server emits `acr` / `amr` describing the authentication event; Keycloak imports them onto the shadow user with `syncMode: FORCE` (they describe *this* login, not the user record) and re-emits them. The `acr` client scope is deliberately removed from `react-client` so Keycloak's built-in `oidc-acr-mapper` does not also write the claim. Two mappers writing one claim is not something to leave to chance.
- **idp-server signing key persistence.** The RSA key idp-server signs tokens with lives at `authorization-server/idp-server/keys/signing-key.json` in JWKS format. The file is gitignored and regenerated on first boot when absent. If you rotate it, Keycloak's cached JWKS must also expire (or restart Keycloak).
- **Docker hostname duality.** Inside containers Keycloak reaches the locally-running idp-server via `host.docker.internal:9000` (Docker Desktop wires this; Linux uses the `host-gateway` `extra_hosts` entry already in `docker-compose.yml`). The browser reaches it via `localhost:9000`. The realm config uses `localhost:9000` for browser-facing URLs and `host.docker.internal:9000` for server-to-server URLs; the issuer claim is `http://localhost:9000`. Don't conflate these.
- **JWT validation is signature-based via JWKS**, not introspection. The backend never calls Keycloak per-request. Keep it that way.
