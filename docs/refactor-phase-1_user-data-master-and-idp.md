# Refactor Phase 1 — user-data-master + idp-server

> **Status:** implemented and verified (see §7). Kept as the record of *why* each decision went the
> way it did — the code carries the what.
>
> **Goal:** introduce a `user-data-master` service that owns all user data, strip idp-server down
> to a stateless authentication front end that reads from it, and land at **exact behavioural parity
> with today's username+password login**. No new user-facing features in this phase.
>
> **Followed by:** [Phase 2 — Smart-ID + registration](refactor-phase-2_smart-id-authn-with-user-registration.md).
> Read this one first; Phase 2 depends on the schema and topology established here.
>
> **Self-contained.** Earlier analysis drafts in `docs/` are legacy and superseded — nothing in this
> plan requires reading them, and nothing in them should be treated as current design.

---

## 1. Why this phase exists

Today the identity story is confused in three specific ways:

1. **`sub` is the username.** [`UserDataDetailsService`](../authorization-server/idp-server/src/main/java/ee/authplayground/idpserver/features/users/service/UserDataDetailsService.java)
   builds `User.builder().username(user.getUsername())`, and Spring Authorization Server derives
   `sub` from `Authentication.getName()`. So the stable federated identifier Keycloak links its
   shadow user to is a mutable display string. Renaming a user breaks the link. The javadocs on
   `UserData` and `OidcClaimsCustomizer` both claim otherwise; both are wrong today.

2. **Identity and credentials are the same row.** `users.password_hash` is `NOT NULL`. There is no
   way to express "this human authenticates by Smart-ID" without contorting the schema, and no way
   for two authentication methods to resolve to one identity.

3. **idp-server owns user data it has no business owning.** `email`, `given_name`, `family_name` are
   person attributes. Many services will eventually want them. Putting them behind the login server
   means every consumer either duplicates them or asks the IdP, and the IdP becomes a de-facto
   customer directory by accident.

Phase 1 fixes all three at once, because fixing them separately means migrating the same rows twice.

**Do this before anything else.** Keycloak's federated identity link is keyed on `sub`. While the
databases are disposable (`docker compose down -v`), changing it is free. Once real shadow users
exist it orphans every one of them.

---

## 2. Target architecture

### Ownership

Four owners, no overlapping facts. Every row below has a reason it cannot live anywhere else.

| Owner | Owns | Why here |
|---|---|---|
| **user-data-master** | `users` (identity + person attributes), `user_credentials` (password hashes, Smart-ID bindings) | Golden record. Any service may need person data; exactly one service may hand out credential material. |
| **idp-server** | Nothing persistent about users | Authenticates. Reads credentials, verifies them, asserts the result. Owns *authentication logic*, not user data. |
| **Keycloak** | Roles, sessions, shadow users | Authorization projection + SSO. Not a customer master. |
| **resource-backend** | `custom_data`, app feature data | App-owned state, per AGENTS.md. Unchanged in this phase. |

The one apparent duplication is deliberate and should be commented in the schema: the **national ID
appears in two places** — `users.national_id` (a person attribute — compliance reads it, statements
print it) and `user_credentials.identifier` for `SMART_ID` rows (the index you look up to answer
"who just authenticated"). Attribute vs. index.

They are not the *same string*, which is the part to get right. The person attribute is the bare code
(`40404040009`) paired with `users.nationality` (`EE`); the credential identifier is the ETSI
semantics identifier (`PNOEE-40404040009`), because that is the exact token Smart-ID hands back in the
certificate subject and the only sane thing to index a credential lookup on. The identifier is
derivable — `"PNO" + nationality + "-" + national_id` — so store the derived form on the credential
row rather than reassembling it per lookup, and comment the relationship at both ends. The two cannot
drift in practice: if someone's issuing country changed it would be a new identity document, and
therefore a new credential row rather than an edit to an existing one.

### Topology

```
                            ┌──────────────────────┐
  browser ──── /login ─────>│     idp-server       │  no database
                            │  (Spring Auth Srv)   │
                            └───────┬──────────────┘
                                    │ client_credentials
                                    │ credentials:read, customer:read
                                    v
                            ┌──────────────────────┐
                            │  user-data-master    │──── user-master-postgres
                            └───────^──────────────┘
                                    │ client_credentials (Phase 2+)
                                    │
  ┌──────────┐   brokered   ┌───────┴──────────────┐   user tokens   ┌──────────────────┐
  │ Keycloak │<─────────────│   realm: playground  │────────────────>│ resource-backend │
  │          │              │   realm: playground- │                 └──────────────────┘
  │          │              │          services    │
  └──────────┘              └──────────────────────┘
```

### Why idp-server has no database

This is the **user federation** pattern: an IdP with no local user store that reads credentials from
an external directory on the fly. It is what Keycloak's `UserStorageProvider` SPI does and what every
Spring Security + LDAP deployment does. In that world the directory holds *both* person attributes
and credential material — so this is not a simplification of a real architecture, it is one of the
two standard architectures.

**The consequence, stated plainly: user-data-master becomes tier-0 infrastructure.** Master down =
nobody logs in, anywhere. In the LDAP world that is understood and priced in — directories get
replication, HA, and a tighter SLO than anything they serve. Our master inherits that classification.

**The shortcut we are actually taking** is not "the IdP would normally have its own DB" — it might
well not. It is that a real bank splits this into two systems:

- **Directory / credential store** — tier-0, small, hot, read-heavy, extreme access restriction
- **Customer master** — business tier, larger, colder, KYC answers, compliance data, many consumers

We merge them into one service. Document that; it is the interesting part, not the component count.

### Why credentials are read, not verified remotely

The master returns the credential record; **idp-server does the BCrypt compare**. The alternative — a
`POST /credentials/verify` on the master — drags authentication policy (lockout, attempt counting,
`acr` determination, what counts as success) into the master or splits it across both. It is also
asymmetric for no reason: the Smart-ID path has no secret to verify at all, it is a pure lookup.

Keep the master a **store**, not a verifier. Both credential types then read the same way.

The cost is password hashes crossing a network hop. That is exactly why `credentials:read` is scoped
to **one client only** (§3). No other caller ever holds it.

Worth a comment at the endpoint itself: the scope restriction is the *authorization* control, and TLS
would be the *transport* one. Everything in this playground is plain `http://`, so that hop is
protected by exactly one of the two mechanisms a real deployment uses. Say so, rather than letting a
reader infer from "it's scoped" that it's safe.

---

## 3. The services realm

`client_credentials` never touches the browser flow, so it would technically work inside the
`playground` realm. Don't. **Keycloak service accounts are backed by real user records**
(`service-account-<clientId>`), which would put machine principals inside a realm whose entire
documented premise is "no local users, everyone is brokered from the IdP." The two populations also
want different token settings — customer tokens short with refresh and SSO sessions, service tokens
longer with neither.

A Keycloak realm is a separate authorization server in every way that matters: own signing keys, own
issuer, own user store, own client registry, own admin boundary. `/realms/playground` and
`/realms/playground-services` share nothing but a JVM and a database. So the realm split is not
standing in for "a separate internal AS" — it *is* that, deployed together.

```
realm: playground             customers, brokered-only, react-client        (unchanged)
realm: playground-services    machines, client_credentials only             (new)
  ├─ idp-server         → credentials:read, customer:read
  ├─ resource-backend   → customer:read, customer:write          (registered now, used in Phase 2)
  ├─ kc-enricher        → customer:read                          (Phase 2)
  └─ user-data-master   (the audience — not a caller)
```

`credentials:read` on exactly one client is the point of the whole exercise: "only the IdP may see
password hashes" becomes a config fact rather than a convention.

### Two gotchas

**Audience.** Keycloak's default `aud` is not what you want — typically the client itself or
`account`. Add an explicit **audience mapper** so tokens carry `aud: user-data-master`, and make the
master validate it. Spring's `JwtDecoder` does **not** check audience unless you add the validator.
Audience validation is what stops a token minted for one service being replayed against another, and
it is the most commonly skipped step in M2M setups. The repo currently validates signature and issuer
and nothing else, so this is new ground.

**The apparent circularity that isn't.** idp-server gets its service token from Keycloak, and
Keycloak brokers user logins to idp-server. This looks like a deadlock and is not:
`client_credentials` involves no user and no brokering, so it resolves entirely inside Keycloak's own
client registry. Worth a comment in the config — the first reader will squint at it.

### Client authentication: where we sit on the ladder

We use `client_secret_basic`, consistent with the repo's weak-creds-on-purpose convention. Document
the ladder, because for a bank it is normative rather than aspirational — **FAPI 2.0 permits only
`private_key_jwt`, `tls_client_auth`, or `self_signed_tls_client_auth`; `client_secret` in any form
is not allowed.**

1. `client_secret_basic` — what we build
2. `private_key_jwt` — FAPI-compliant, no shared secret to leak
3. mTLS client certificates — FAPI-compliant
4. Workload identity (SPIFFE/SPIRE, cloud IAM) — where the industry is moving

Step 2 is genuinely reachable here later: both Keycloak and Spring Authorization Server support it,
and idp-server already generates and persists an RSA key. "Swap service clients from shared secrets
to `private_key_jwt`" is a good self-contained future phase with a real security story.

---

## 4. Schema

Lives in **user-data-master**, not idp-server. This is the final shape — do not land an intermediate
version with `password_hash` still on `users`, or Phase 2 becomes a second migration.

```sql
CREATE TABLE users (
    id             UUID PRIMARY KEY,        -- minted here; becomes `sub` everywhere
    national_id    VARCHAR(50),             -- bare code, e.g. '40404040009' (nullable: not everyone has one)
    nationality    CHAR(2),                 -- ISO 3166-1 alpha-2, e.g. 'EE'
    username       VARCHAR(100) UNIQUE,     -- display handle; see note below
    email          VARCHAR(255),            -- NOT unique, NOT a join key — see §6
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,  -- see §6; never a hardcoded claim again
    given_name     VARCHAR(100),
    family_name    VARCHAR(100),
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (nationality, national_id)       -- NOT national_id alone; see note below
);

CREATE TABLE user_credentials (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        VARCHAR(32) NOT NULL,       -- PASSWORD | SMART_ID
    identifier  VARCHAR(255) NOT NULL,      -- username | PNOEE-xxxxxxxxxxx
    secret_hash VARCHAR(255),               -- BCrypt for PASSWORD; NULL for SMART_ID
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (type, identifier)
);
```

Notes:

- **`users.id` is minted by the master and becomes `sub`.** Every service — idp-server, Keycloak,
  resource-backend, anything later — keys off one UUID the golden record owns. This is the clean
  ending for the `sub` bug: it stops being "fix the principal name" and becomes "the subject
  identifier is the master's user ID."
- **`username` is demoted to a display handle** and is free to change. Its role as a login identifier
  moves to `user_credentials.identifier` on the `PASSWORD` row. This finally makes the existing
  `UserData` javadoc true.
- **`email` is deliberately not unique and never a join key.** See §6.
- **`nationality` is not decoration.** It carries the country half of the ETSI semantics identifier
  Phase 2's Smart-ID lookup needs, and it is what makes the uniqueness constraint *correct*: national
  ID numbers are unique **within a country**, not globally. SK's own demo set proves it —
  `PNOEE-40404040009` and `PNOLT-40404040009` are different people sharing a number. A bare
  `UNIQUE (national_id)` would have collided the first time anyone seeded a non-Estonian test
  identity, which Phase 2's regional test accounts do.

  Strictly, ETSI's country is the *issuing country of the identity document*, which is not
  universally the same thing as nationality. For our purposes they coincide. Comment the distinction
  rather than pretending it isn't there.
- **`email_verified` is a column, not a constant.** §6 explains why at length. It lands in the first
  migration because retrofitting it after Phase 2 starts collecting unverified addresses is precisely
  the ordering mistake this document exists to prevent.
- `UNIQUE (type, identifier)` is what makes credential lookup a single indexed read for both methods.

### Seeding

Seed users move from `UserSeedRunner` in idp-server to the master. Keep the same approach — seed in
application code rather than SQL, so the BCrypt hash is computed by the current encoder rather than
frozen at a cost factor in a migration. Seed both a `users` row and its `PASSWORD` credential row.

Give the seeded users a `national_id` + `nationality` now, using SK's published demo identity codes,
so Phase 2A's happy path works with no data change:

| username | `national_id` | `nationality` | derived ETSI identifier |
|---|---|---|---|
| `conan`  | `40404040009` | `EE` | `PNOEE-40404040009` |
| `matrix` | `50001029996` | `EE` | `PNOEE-50001029996` |

Seed `email_verified = true` for both. Their addresses are fixtures we control, which is the only
circumstance under which asserting verification is honest — and it keeps the brokered flow clear of
Keycloak's "Update Account Information" page.

---

## 5. Steps

### 1.1 — Scaffold `user-data-master`

New Spring Boot module at **`internal-services/user-data-master-app/`**. The extra directory level is
deliberate: this repo's root layout is one folder per **architectural tier** (`authorization-server/`,
`web-app/`), not one folder per deployable — and a customer master belongs to neither of the existing
two. `internal-services/` names the tier it does belong to, and gives the next internal service an
obvious place to land. One module inside it looks like overkill today; wedging the master into
`web-app/` would misfile it permanently.

Names to keep straight, because they are deliberately not identical:

| | Value |
|---|---|
| Directory / Gradle project | `user-data-master-app` |
| Service, Keycloak client, token `aud` | `user-data-master` |
| Package root | `ee.authplayground.userdatamaster` |
| Database container | `user-master-postgres` |

Port **9100**. `appcore` + `features.<domain>` per AGENTS.md. Standalone Gradle build with its own
`settings.gradle.kts` (`rootProject.name = "user-data-master-app"`), matching how `idp-server` and
`resource-backend` are built — there is no root aggregate build in this repo.

**Not containerized.** Like idp-server it runs via `./gradlew bootRun`; only its database goes into
docker-compose. (BACKLOG's "Dockerize `idp-server`" item now covers two locally-run apps and the same
issuer/hostname wrinkle — worth updating there.)

New Postgres instance `user-master-postgres` on host port **5434** (5432 and 5433 are taken). Per
AGENTS.md's one-DB-per-component rule. Note 5433 frees up when `idp-postgres` is deleted in §1.5 —
do **not** reuse it. A stale volume answering on a port you have re-pointed is an expensive way to
lose an hour.

Dependencies: web, security, oauth2-resource-server, data-jpa, flyway, validation, actuator, lombok.

### 1.2 — Schema, entities, seed

Flyway migration per §4. Entities, repositories, seed runner.

### 1.3 — The `playground-services` realm

New file `authorization-server/keycloak/realms/playground-services-realm.json`. It is picked up
automatically — docker-compose already mounts the whole `realms/` directory and Keycloak's
`--import-realm` imports every JSON in it.

Register the clients from §3 with service accounts enabled, client scopes `credentials:read`,
`customer:read`, `customer:write`, and an **audience mapper** emitting `aud: user-data-master`.

> Realm JSON re-imports on container **recreate** (`docker compose down && up`), not on restart.

### 1.4 — Master API

Resource-server config validating issuer, signature, **and audience**. Method-level authorization on
scopes.

| Endpoint | Scope | Notes |
|---|---|---|
| `GET /internal/credentials?type={t}&identifier={i}` | `credentials:read` **+** `customer:read` | Returns the credential (incl. hash) **and the user it belongs to**, in one response. **idp-server only.** |
| `GET /internal/users/{id}` | `customer:read` | Person attributes by `sub`. Not on the Phase 1 login path — registered for resource-backend and Phase 2. |
| `GET /internal/users/by-national-id/{nid}?nationality={c}` | `customer:read` | Phase 2 registration lookup. Takes both halves of §4's composite key. |

#### One call, not two — and not for the reason it looks like

The obvious argument for combining ("fewer round trips on the login path") **does not apply**. The
form-login POST and the ID-token issuance are *different HTTP requests*, separated by a browser
redirect and a back-channel token call from Keycloak. No single endpoint can serve both moments, so
no amount of endpoint design collapses them.

What actually makes one call work is §1.5's custom `UserDetails`: the login POST fetches credential
**and** person attributes together, the attributes ride along on the authenticated principal, and the
token customizer reads them off the principal instead of calling the master again. Two master calls
per login becomes one — via the principal, not via the API shape.

The endpoint therefore stays a **store read** — "give me this credential and its owner" — rather than
`POST /internal/authn/lookup`. The verb matters: an authn-shaped endpoint on the master is the first
step toward authentication policy migrating into it, which is exactly what §2 argues against.

Because the response carries person attributes, this endpoint requires **both** scopes. That reads as
awkward and is actually correct: it is one read returning two kinds of data, to a caller legitimately
entitled to both. It also keeps the §3 invariant intact — `credentials:read` alone still buys nothing,
and only idp-server holds it.

Keep it a store. No authentication logic, no policy, no verification.

### 1.5 — Strip idp-server

**Delete:** `UserData`, `UserDataRepository`, `UserSeedRunner`, `V1__init_users.sql`, the
`idp-postgres` service and its volume in docker-compose, and the JPA / Flyway / PostgreSQL
dependencies and config in `build.gradle` and `application.yml`.

**Add:** an OAuth2 client for the master (`client_credentials`, token cached — it is on the login hot
path), and a `features.users` client package replacing the repository.

Configure the Keycloak provider with an explicit `token-uri`, not `issuer-uri`. `issuer-uri` performs
OIDC discovery **at bean creation**, so idp-server would refuse to start whenever Keycloak is down —
a hard startup coupling bought for nothing, given we need exactly one endpoint. Same reasoning on the
master's resource-server side: prefer `jwk-set-uri` over `issuer-uri`, and configure the expected
issuer string as a literal for the validator.

**Rewrite:** `UserDataDetailsService` becomes a master-API client. It fetches the `PASSWORD`
credential and its owner in one call, hands the hash to Spring Security, and — critically — **builds
the `UserDetails` with `users.id` as the username**, not the login name. That single change fixes
`sub`.

Return a **custom `UserDetails`** (extend Spring's `User`, or implement the interface directly)
carrying the person attributes from that same response. Spring Authorization Server stores the login
`Authentication` on the `OAuth2Authorization` and hands it back at token-issuance time, so
`OidcClaimsCustomizer` can read attributes off `context.getPrincipal().getPrincipal()` and needs no
master client at all.

Two consequences worth a comment in the code:

- The attributes are a **snapshot taken at authentication time**. That is not a limitation to
  apologise for — it is correct for claims describing *this* authentication event, and it is what
  every OIDC provider does.
- The custom `UserDetails` must stay **Jackson-serializable** if the authorization store ever moves to
  JDBC. `AuthorizationServerConfig`'s javadoc already promises that move.

`OidcClaimsCustomizer` keeps its current shape and simply sources from the principal instead of the
repository — including emitting the real `email_verified` rather than a hardcoded `true` (§6).

**Also fix while you are in there:** `AuthorizationServerConfig`'s javadoc refers to "Phase 1",
"Phase 3" and "Phase 4" from an older, unrelated phase scheme, and `OidcClaimsCustomizer` claims that
`sub` "is already the user's stable UUID" — untrue today, true after this step. After the refactor
those stale phase numbers will read as pointing at *these* documents. Retire them; make the `sub`
comment accurate and keep it.

> **Phase 1 is deliberately not the final claim architecture.** The end state — idp-server emits only
> `sub`/`acr`/`amr` and Keycloak's enricher supplies everything else — depends on an enricher that
> does not exist until Phase 2. Email must keep travelling in the ID token here, or brokered login
> falls through to Keycloak's "Update Account Information" page. Do not try to reach the end state in
> one move.

### 1.6 — `acr` / `amr`

Emit both from idp-server now, while there is exactly one possible combination and it is impossible to
get wrong. Adding them in Phase 2 means changing a token contract that Keycloak and resource-backend
already consume. This is the cheapest it will ever be.

**The value space, fixed now:**

| | Phase 1 | Phase 2 adds |
|---|---|---|
| `acr` | `weak` | `strong` |
| `amr` | `["pwd"]` | `["smartid"]` |

Password login is `acr: weak` / `amr: ["pwd"]`. Named for what they mean rather than as LoA integers:
the entire point in Phase 2 is that the same `sub` arrives at two different assurance levels, and
`weak` / `strong` says that out loud in a decoded token.

#### Getting them through Keycloak, which is fiddlier than it sounds

Two hops. An **identity-provider attribute-importer mapper** stores the upstream claim on the shadow
user; a **user-attribute protocol mapper** on `react-client` emits it into the SPA's tokens.

**Set `syncMode: FORCE` on both importer mappers.** The provider is `syncMode: IMPORT`, which syncs
attributes on *first login only*. But `acr` and `amr` describe **this authentication event**, not the
user record. Freezing them at first login is already wrong in Phase 1 and breaks outright in Phase 2,
where the same person authenticating by Smart-ID would still carry `amr: ["pwd"]` from whenever they
first signed up. Mappers carry their own `syncMode`, so this is a per-mapper override — the
provider-level `IMPORT` stays as it is, and BACKLOG's "reconsider `syncMode`" item is about that
provider level and remains open.

**`acr` has an incumbent.** Keycloak emits `acr` itself, via the built-in `oidc-acr-mapper` on the
`acr` client scope, computed from its *own* flow's LoA — which knows nothing about how the upstream
IdP authenticated the user. Two mappers writing one claim is not something to leave to chance.
`react-client` currently declares no `defaultClientScopes` and so inherits the realm defaults, `acr`
among them; declare the list explicitly on the client, minus `acr`, so ours is the only writer.

That is the pedagogically interesting part of this whole step: in a brokered chain **`acr` has to have
exactly one owner**, and here the upstream IdP is the only participant that actually knows what
happened. `amr` has no incumbent and needs no such surgery.

### 1.7 — Update the documentation this refactor falsifies

Not cosmetic, and not a follow-up. Five files assert things that stop being true, and AGENTS.md is
loaded as *instructions* by every AI assistant working in this repo — a stale rule there actively
misdirects future work.

| File | What breaks |
|---|---|
| `AGENTS.md` | "Three Postgres instances… `idp-postgres` (idp-server user accounts)"; the brokering-chain note saying "the seed users live in idp-server's Postgres"; the dev-loop `docker compose up` line |
| `README.md` | the architecture diagram's `idp-postgres` box; the `docker compose up` line; the tech-stack/ports material |
| `docs/tech-overview.md` | the same architecture diagram |
| `docs/local-setup-overview.md` | ports table, credentials table, two `docker compose up` lines |
| `BACKLOG.md` | "Dockerize `idp-server`" now covers two locally-run apps |

Both diagrams lose `idp-postgres` and gain `user-master-postgres`, plus the `user-data-master` box
itself. AGENTS.md's one-DB-per-component rule still holds at three instances — just a different three.
Add the new module to AGENTS.md's package-layout section and its `internal-services/` tier to the
project-structure description, so the convention is stated rather than inferred from one example.

### 1.8 — Verify parity

`docker compose down -v && docker compose up -d`, then run all **four** apps (idp-server,
user-data-master-app, resource-backend, client-frontend). Definition of done in §7.

---

## 6. Two rules to write into the code as comments

**Email is never a join key.** OIDC Core §5.7 makes `sub` + `iss` the only claims a relying party may
rely on as a stable identifier. Auto-linking accounts on a matching email address is a documented
account-takeover primitive with a CVE class attached. In Phase 2 a Smart-ID user will type an email
into a form with no mailbox verification; joining on it would let anyone holding any Smart-ID inherit
an existing account. Collect it. Never join on it. Hence `email` is not `UNIQUE` in §4 — the schema
should make the wrong thing hard, not just discouraged.

**`email_verified` must stop being a hardcoded `true`.** `OidcClaimsCustomizer` currently emits
`claims.claim("email_verified", true)` unconditionally, and the realm sets `"trustEmail": true` on
the `playground-idp` provider. That is fine today because seeded users have known-good addresses. The
moment Phase 2 collects one from a form it becomes an assertion we never performed, and Keycloak is
configured to believe it. `users.email_verified` (§4) exists precisely so the claim can carry a real
value; emit that. Two lines now; nasty later.

One nuance not to gloss over, because it will otherwise be discovered at the worst moment: Keycloak's
`"trustEmail": true` means Keycloak **skips its own verification step** for addresses arriving from
this provider. So an honest `email_verified: false` travelling up from idp-server does not, by itself,
stop Keycloak marking the shadow user's email verified. Fixing the IdP's claim is still the right move
here — an IdP must not assert what it did not check, regardless of what its relying party does with
the assertion — but closing the loop end-to-end is a Phase 2 concern that arrives with the first
form-collected address. Leave `trustEmail` as it is and put a comment in the realm JSON saying why,
so the second half of the fix is findable rather than rediscovered.

---

## 7. Definition of done

Verified **manually** this phase — no automated tests. The repo has none today and this is still
PoC-shaped work. The two negative checks below are the ones to write first the moment that changes;
note which they are.

- [x] `docker compose down -v && up` brings up Keycloak, both realms, `user-master-postgres`,
      `backend-postgres`. No `idp-postgres`. Both realms logged `imported`, no errors.
- [x] Seeded users log in at `:9000/login` with username+password exactly as today.
- [x] Brokered chain completes: idp-server → Keycloak → SPA gets tokens, no "Update Account
      Information" page.
- [x] **Decoded ID token: `sub` is a UUID matching `users.id` in the master.** Confirmed on a real
      token: `sub = 9f97234e-…e335b`, identical to `users.id` for `conan`. Keycloak's
      `federated_identity.federated_user_id` holds the same UUID — which is the link the original bug
      would have broken.
- [x] SPA's token carries `acr: "weak"` and `amr: ["pwd"]` — and exactly one `acr`, ours. Confirmed
      by decoding the SPA's Keycloak-issued access and ID tokens; `react-client`'s default scopes are
      `basic, email, profile, roles, web-origins`, with `acr` absent.
- [x] **One master call per login.** One `Credential lookup` logged per login, zero
      `User lookup by id` — the attributes ride on the principal.
- [x] idp-server holds no user data, has no datasource configured, and starts in ~1.5s. A complete
      login also succeeded with **Keycloak stopped**, which additionally settles open question 4:
      the service token is genuinely cached rather than refetched per request.
- [x] Master rejects a wrong audience — replaying a *valid* idp-server token against a master
      expecting a different audience returns `401 invalid_token, Required audience … not present`.
      `credentials:read` denied to `resource-backend` (403) and to an anonymous caller (401), while
      `customer:read` still works for it (200).
- [x] `syncMode: FORCE` re-syncs. Poisoning `idp_acr` to `STALE-VALUE` and re-brokering restored it
      to `weak` — so `acr`/`amr` track the authentication event rather than freezing at first login,
      which is what Phase 2 depends on.
- [x] resource-backend and client-frontend **source** is unchanged. `react-client`'s realm-JSON entry
      does change (§1.6); that is configuration, not application code.
- [x] Docs from §1.7 no longer describe a system that exists.

One deviation from §4 worth recording: `nationality` is `VARCHAR(2)`, not `CHAR(2)`. Postgres reports
`CHAR` as `bpchar`, which Hibernate maps to `Types#CHAR` and rejects against a `String` field under
`ddl-auto: validate`. `VARCHAR` is the better choice regardless — `CHAR` blank-pads, and a padded
country code silently stops matching the one you searched for.

### Expected breakage, not bugs

- **Keycloak shadow users are orphaned** by the `sub` change, and `resource-backend`'s rows keyed on
  `keycloak_user_id` go stale. `down -v` handles both. Expect it rather than debugging it.

---

## 8. Decisions and open questions

### Decided

| # | Question | Decision |
|---|---|---|
| 1 | Module location — repo root, or a new `internal-services/` directory? | **`internal-services/user-data-master-app/`.** Root folders name architectural tiers, not deployables; the master gets its own tier rather than being wedged into one it does not belong to. §1.1. |
| 2 | Does resource-backend get its services-realm client registered now or in Phase 2? | **Now.** Free, and keeps realm-JSON edits in one place. It goes unused until Phase 2. |
| 3 | One combined lookup, or credential + user fetched separately? | **One** — but not for the stated reason, which turns out not to hold. §1.4. |
| 5 | Keep `username` on `users`? | **Keep.** A Smart-ID-only user still wants a display handle, and its login-identifier role has moved to `user_credentials.identifier` regardless. Comment the apparent duplication. |
| 6 | `acr` / `amr` value space | **`weak` / `strong`**, **`pwd` / `smartid`**. Phase 1 emits `weak` + `["pwd"]` only. §1.6. |
| 7 | `national_id` format | **Bare code**, with `nationality` (ISO 3166-1 alpha-2) alongside. The ETSI identifier is derived from the pair. §4. |
| 8 | Containerize the master? | **No.** `bootRun` like idp-server; only its Postgres goes into compose. §1.1. |
| 9 | Automated tests this phase? | **No.** Manual verification per §7. |

| 4 | Does `OAuth2AuthorizedClientManager` actually cache the service token, or refetch per request? | **It caches.** `AuthorizedClientServiceOAuth2AuthorizedClientManager` backed by an `OAuth2AuthorizedClientService` persists and reuses until expiry. Confirmed the blunt way: a full login succeeded with Keycloak stopped. |

### Still open

| # | Question | Notes |
|---|---|---|
| 10 | Does anything need to stop `"trustEmail": true` overriding an honest `email_verified: false`? | Not this phase — every seeded address is genuinely ours. Keycloak's `trustEmail` makes it skip its *own* verification for addresses from this provider, so an honest `false` travelling up does not by itself stop the shadow user being marked verified. Becomes real with Phase 2's first form-collected email. See §6. |
| 11 | Does the SPA need `acr`/`amr` surfaced in the Token Inspector? | They are in the token now and nothing displays them. Cheap to add, and it makes the Phase 2 payoff (same `sub`, two assurance levels) visible without decoding a JWT by hand. Out of scope here — client-frontend is unchanged this phase. |
