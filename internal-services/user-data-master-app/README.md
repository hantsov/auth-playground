# User Data Master

The golden record. Owns every user and every credential in the system, and nothing else owns either.

```
idp-server ──── client_credentials (credentials:read + customer:read) ────► user-data-master
                                                                                   │ JDBC
                                                                                   ▼
                                                                          user-master-postgres
```

## What it is for

Before this service existed, idp-server held `users` — including `email`, `given_name`, `family_name`. Those are **person attributes**, not authentication data. Putting them behind the login server means every consumer either duplicates them or asks the IdP, and the IdP becomes a customer directory by accident.

Three ownership rules follow:

| Owner | Owns | Why here |
|---|---|---|
| **user-data-master** | `users` (identity + person attributes), `user_credentials` (issued credentials — password hashes today) | Any service may need person data; exactly one service may hand out credential material. |
| **idp-server** | nothing persistent about users | Authenticates. Owns *authentication logic*, not user data. |
| **Keycloak** | roles, sessions, shadow users | Authorization projection + SSO. Not a customer master. |
| **resource-backend** | `custom_data`, app feature data | App-owned state. |

**This is tier-0 infrastructure.** idp-server has no database, so this service being down means nobody logs in, anywhere. That is the standard trade in the user-federation pattern and the same classification an LDAP directory carries in a real deployment.

### The shortcut we are taking

A real bank splits this into two systems:

- **Directory / credential store** — tier-0, small, hot, read-heavy, extreme access restriction
- **Customer master** — business tier, larger, colder, KYC answers, compliance data, many consumers

We merge them into one service. That is the interesting simplification here — not the component count.

## Schema

Two tables. `users` answers "who is this person"; `user_credentials` answers "what did we issue them to prove it with".

The split is not symmetric, and that asymmetry is the interesting part. Authentication methods come in two shapes:

- **Inherent** — usable because of *who you are*. An external authority holds the authenticator and has already done the identity proofing; we only need the identifier on the person record. **Smart-ID** is the example: the state issued the identity, SK holds the key, and `users.national_id` is the entire binding. Nothing to enrol, nothing to store, nothing for us to revoke.
- **Issued** — usable because *we gave you something*. We hold a secret, so there is a row, an enrolment step, and a revocation lever. Passwords today; TOTP or WebAuthn would land here too.

`user_credentials` is the table of **issued** credentials. The test for any method added later: *does authenticating require something we store?* Then it is a row. If not, it is an attribute on `users`.

So a Smart-ID-only person has **zero** credential rows — which is exactly what a separate table buys, and what a `users.password_hash` column could never express: a nullable column would conflate "no password yet" with "authenticates by other means".

An earlier draft gave Smart-ID a credential row keyed on the ETSI identifier. That was wrong twice over: the identifier is derivable from `nationality + national_id`, so the row was duplication with a synchronisation problem — and it implied an opt-in step that does not exist.

`users.id` is minted here and becomes the **`sub` claim everywhere downstream** — idp-server asserts it, Keycloak links its shadow user to it, resource-backend keys its rows on it. It is the only identifier in the system that is stable by construction.

Four schema decisions worth knowing, all commented at length in `V1__init_user_master.sql`:

- **`email` is not `UNIQUE` and never a join key.** OIDC Core §5.7 makes `sub` + `iss` the only claims a relying party may rely on as a stable identifier. Auto-linking accounts on a matching email is a documented account-takeover primitive — and in Phase 2 a Smart-ID user will type an email into a form with no mailbox verification. The schema should make the wrong thing hard.
- **`UNIQUE (nationality, national_id)`, not `UNIQUE (national_id)`.** National ID numbers are unique *within a country*. Smart-ID's own demo set proves it: `PNOEE-40404040009` and `PNOLT-40404040009` are different people sharing a number.
- **`email_verified` is a column, not a constant.** idp-server used to emit `email_verified: true` as a hardcoded literal. Harmless while every address was a seeded fixture; an assertion nobody performed the moment one arrives from a form.

- **`secret_hash` is nullable, but a `CHECK` constraint keeps it honest.** `password_requires_secret` enforces that a `PASSWORD` row must carry a hash. The column stays nullable so a future issued method with something other than a comparable secret has somewhere to go; the constraint means today's only type cannot be half-populated. Enforced in the database rather than in application code because a constraint holds for every writer, including the psql session someone opens at 2am.

The national ID lives in exactly **one** place: `users.national_id`, paired with `users.nationality`. The ETSI semantics identifier (`PNOEE-40404040009`) is derived when needed and never stored — Smart-ID authentication splits the incoming identifier into its two halves and matches on the indexed `(nationality, national_id)` pair.

## API

Every endpoint requires a `client_credentials` token from Keycloak's `playground-services` realm, with `aud: user-data-master`.

| Endpoint | Scope | Notes |
|---|---|---|
| `GET /internal/credentials?type={t}&identifier={i}` | `credentials:read` **+** `customer:read` | The credential **and its owner**, in one response. The **issued**-method login path. idp-server only. |
| `GET /internal/users/{id}` | `customer:read` | Person attributes by `sub`. |
| `GET /internal/users/by-national-id/{nid}?nationality={c}` | `customer:read` | The **inherent**-method login path — Smart-ID resolves here — and Phase 2's registration lookup. Takes both halves of the composite key. |

Note the two login paths are deliberately different endpoints, because the two method shapes are different: one fetches something we stored, the other identifies a person from something the state issued. They converge on the same `users.id`, which is what lets one person reach one `sub` by either route.

### Why credentials are read, not verified here

The master returns the hash; **idp-server does the BCrypt compare**. A `POST /credentials/verify` would drag authentication policy — lockout, attempt counting, `acr` determination, what counts as success — into this service or split it across both. Read-only lookup leaves every authentication decision in the one service whose job that is.

Keep this a **store**, not a verifier.

### The cost of that, stated plainly

Password hashes cross a network hop. Two controls would normally cover it; this playground has one:

- **Authorization — present.** `credentials:read` is granted to exactly one client in the entire services realm. "Only the IdP may see password hashes" is a config fact you can read out of the realm JSON, not a convention. Combined with audience validation, a token must be minted by the right realm, for this service, by that one client.
- **Transport — absent.** Everything here is plain `http://`. A real deployment puts TLS (plausibly mTLS) under this hop. Do not read "it is scoped" as "it is safe".

## Token validation

Three things are checked, and the third is the one people skip:

1. **Signature** against the realm's JWKS.
2. **Issuer** — `playground-services`, not `playground`. A customer token is not merely under-privileged here; it is from the wrong authority entirely.
3. **Audience** — see `AudienceValidator`. Spring's `JwtDecoder` does **not** check `aud` unless you add a validator, and Keycloak's default audience is not what you want (typically the calling client, or `account`). An explicit audience mapper on the service client scopes emits `aud: user-data-master`.

`jwk-set-uri` is configured rather than `issuer-uri`, so no OIDC discovery happens at bean creation and this service does not refuse to start when Keycloak is down.

## Running locally

Not containerized yet — like idp-server, it runs via Gradle (see [BACKLOG.md](../../BACKLOG.md)).

```bash
# From repo root
docker compose up -d user-master-postgres keycloak-postgres keycloak

# Then in this folder
./gradlew bootRun
```

Listens on **http://localhost:9100**. Start it *before* idp-server — nobody can log in without it.

## Seeded users

`UserSeedRunner` seeds on first boot, idempotently. Two rows per person — a `users` row and a `PASSWORD` credential — which is the credential split made concrete.

| username | password | national_id | nationality | ETSI identifier |
|---|---|---|---|---|
| `conan` | `conan123` | `40404040009` | `EE` | `PNOEE-40404040009` |
| `matrix` | `matrix123` | `50001029996` | `EE` | `PNOEE-50001029996` |

The national IDs are Smart-ID's published demo identity codes, seeded now so Phase 2's happy path works with **no data change and no writes** — the national ID *is* the Smart-ID binding, so both users can already authenticate that way the moment the protocol layer exists. Both get `email_verified = true`; their addresses are fixtures we control, which is the only circumstance under which asserting verification is honest.

Passwords are hashed by the current encoder at runtime rather than frozen into a migration at a fixed cost factor.

## Environment variables

`application.yml` has sensible localhost defaults. Override only when needed:

| Variable | Default |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5434/masterdb` |
| `SPRING_DATASOURCE_USERNAME` | `masteruser` |
| `SPRING_DATASOURCE_PASSWORD` | `masterpass123` |
| `KEYCLOAK_JWK_SET_URI` | `http://localhost:8080/realms/playground-services/protocol/openid-connect/certs` |
| `PLAYGROUND_MASTER_EXPECTED_ISSUER` | `http://localhost:8080/realms/playground-services` |
| `PLAYGROUND_MASTER_EXPECTED_AUDIENCE` | `user-data-master` |

## Poking at it by hand

```bash
TOKEN=$(curl -s -u idp-server:idp-server-secret -d grant_type=client_credentials \
  http://localhost:8080/realms/playground-services/protocol/openid-connect/token | jq -r .access_token)

curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:9100/internal/credentials?type=PASSWORD&identifier=conan" | jq
```

Swap the client for `resource-backend:resource-backend-secret` and the same call returns **403** — it holds `customer:read` but not `credentials:read`. That denial is the design working, and is worth exercising whenever the realm config changes.
