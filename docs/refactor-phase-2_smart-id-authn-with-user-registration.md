# Refactor Phase 2 — Smart-ID authentication + user registration

> **Status:** design, nothing implemented yet. Written to be picked up cold.
>
> **Depends on:** [Phase 1](refactor-phase-1_user-data-master-and-idp.md). The `users` +
> `user_credentials` schema, the `playground-services` realm, and `sub` being the master's UUID are
> all prerequisites. Do not start this phase until Phase 1's definition of done is green.
>
> **Self-contained.** Every Smart-ID protocol fact this plan relies on is reproduced in §6. Earlier
> analysis drafts in `docs/` are legacy and superseded; do not read them as current design.
>
> **Scope:** authentication only. Digital signing is out of scope. SK's DEMO environment only.
> The HTTP client is handwritten; SK's Java client is read as reference, not taken as a dependency.

---

## 1. The registration model

### One principle drives everything

**Only an authoritative method can *establish* an identity. Weak methods can only *re-assert* one
already established.**

A password proves nothing about who you are. A Smart-ID certificate carries a state-issued national
identity code. In NIST 800-63 terms this is an IAL question (identity proofing), not an AAL one
(authenticator strength). Everything below follows from that one sentence:

- Registration requires Smart-ID. There is no "sign up with username and password" endpoint — not
  disabled, **absent**.
- Username+password works only for users who already exist.
- A password can only ever be *added* to an account, from an already-authenticated session.

### Open population, and why

An earlier draft concluded that a bank has a **closed population** and should reject unknown Smart-ID
holders. **That conclusion is withdrawn.** It imports an assumption —
"the bank knows you before you show up" — that holds for a US bank or a corporate staff IdP and is
false for an Estonian retail bank.

In Estonia a resident with Smart-ID opens an account fully online: identify with Smart-ID, answer a
questionnaire, done. There is no prior offline step creating the record. The state has already done
the identity proofing; the bank inherits it from the certificate and only decides whether to accept
the relationship. **Self-service registration at first login is the accurate model, not a playground
concession.**

The policy seam that conclusion implied is still worth having, but relabel what its arms mean. It is not
consumer-vs-bank — it is **self-service onboarding vs. pre-provisioned population**, and both exist
in banking:

```java
// UserResolutionPolicy
//   OPEN_POPULATION   → register unknown identity   (Estonian retail bank; our default)
//   CLOSED_POPULATION → reject unknown identity     (corporate IdP, branch-based onboarding)
```

### The gate moved

| Gate | Owner | Question |
|---|---|---|
| **Assurance** | idp-server | "Is this identity proven, and do we have a record?" No record → **register one**. |
| **Membership** | Keycloak / entitlement | "Is this identity an active customer, for this client, with which roles?" |

The IdP's gate is on **identity assurance, not membership**. That is what allows it to register people
(it can prove who they are) while still not being the customer master (it does not decide whether
they are a customer). It is also why registration requires Smart-ID — a password cannot open an
assurance gate.

### Three things called "registration"

Keep the vocabulary separate or the code will conflate them:

1. **Identity + credential enrolment** — creating the `users` row and its first `user_credentials`
   row, plus collecting the email the certificate does not carry. Happens in **idp-server**. This is
   what "registration in the IdP" means.
2. **Customer onboarding / KYC** — questionnaire, screening, the accept/reject decision. Happens in
   **resource-backend** (`features.onboarding`). Business process, not auth.
3. **App profile provisioning** — the existing `/api/user/register` JIT flow. Unchanged; not identity
   registration at all.

One user journey, three records, three owners.

### State machine

```
IDENTIFIED   Smart-ID verified, no record in master.        (transient — registration or reject)
REGISTERING  users row exists, mandatory IdP fields missing. Authorize cannot complete.
PENDING      IdP done. Token issued but restricted. KYC incomplete.
ACTIVE       Full customer.
SUSPENDED    Revoked. (Later — but this is where the drift story lands.)
```

`IDENTIFIED → REGISTERING` is a required-action state inside idp-server. `PENDING → ACTIVE` is an
entitlement question owned by resource-backend and reflected through Keycloak. The SPA branches on
the status, which is why it has to reach the token (§4).

---

## 2. Phase 2A — Smart-ID for existing users

Prove the whole protocol and validation layer against a **known, seeded** identity before adding the
registration state machine on top. Phase 1 seeds `national_id` on the seed users precisely so this
works with no data change.

### 2A.1 — Feature skeleton + handwritten client

Package `ee.authplayground.idpserver.features.smartid`, sub-packaged by concept per AGENTS.md
(`client`, `session`, `validation`, `service`, `controller`; `devicelink` later).

`@ConfigurationProperties("playground.idp.smart-id")`: base URL, RP UUID, RP name, certificate level
(`QUALIFIED`), poll timeouts, truststore location, revocation-check toggle.

Spring Boot 4 → `RestClient`, ideally behind a declarative `@HttpExchange` interface so the endpoint
list reads like the documentation. Record DTOs.

**Milestone:** a notification session is created against the demo environment.

### 2A.2 — Notification flow front end

Build the ETSI identifier from the country + national-ID fields **already present** in
[login.html](../authorization-server/idp-server/src/main/resources/templates/login.html) — they stop
being placeholders. Replace the disabled "Coming soon" button with a live submit.

Compute and display the 4-digit verification code. Poll **our own** backend endpoint, never SK's.

> The login page is currently deliberately JS-free (the CSS-only method picker is called out in a
> comment). Status polling needs JavaScript. Keep it confined to the Smart-ID panel.

### 2A.3 — Session polling + response validation

`GET /v3/session/{sessionID}` with long-poll timeout; `RUNNING` → `COMPLETE`; map `endResult`.

Then the part worth writing verbosely, because it is the entire trust model:

- Reconstruct the `ACSP_V2` payload byte-exactly (§6).
- Verify the RSASSA-PSS signature with the certificate's public key.
- Check `rpChallenge` matches what we generated.
- Validate the chain against the **explicitly configured demo CA truststore**, not the JVM default.
- Check validity dates and `certificateLevel >= QUALIFIED`.
- Extract the identity code from the subject DN `serialNumber` (OID 2.5.4.5), format `PNOEE-...`.

A naive integration that checks `endResult == OK` and trusts the returned certificate is trivially
forgeable. **This layer is flow-agnostic and is reused verbatim by the QR flow. Design it that way.**

### 2A.4 — Spring Security wiring

`SmartIdAuthenticationToken` + `AuthenticationProvider`, so it reads like idiomatic Spring Security
rather than a hand-stuffed `SecurityContext`. Permit `/login/smart-id/**` in `DefaultSecurityConfig`.

Resolution: national ID → `user_credentials(type=SMART_ID, identifier=...)` → `users.id` → principal.
Identical shape to the password path from Phase 1, which is the payoff of the credential split.

Emit `amr: ["smartid"]` and `acr: strong`, against Phase 1's `amr: ["pwd"]` / `acr: weak`. **The two
methods must be distinguishable downstream or there was no point differentiating them.**

**Milestone:** seeded user logs in with Smart-ID, brokered chain completes, SPA gets tokens carrying
`acr: strong`.

### 2A.5 — Credential enrolment

One `CredentialEnrolmentService`, called only from an **authenticated** session:

- "Add Smart-ID to my password account" — sign in with password → account page → authenticate with
  Smart-ID → bind.
- "Add a password to my Smart-ID account" — the same method, opposite direction.

Both are the same operation with different arguments; the existing session is the proof. Build them
as one code path or they will drift.

> The governing rule: **credential enrolment must happen from a session at or above the assurance
> level the credential will grant.** A password grants AAL1, so any authenticated session can enrol
> one. There is still no anonymous enrolment endpoint.

Note the framing this gives the seeded users: they are "customers migrated from the legacy system,"
and adding Smart-ID from the account page is the real migration path Estonian banks actually walked.
Password is a **legacy + convenience** method here, not a peer of Smart-ID.

---

## 3. Phase 2B — registration for unknown identities

### 3.1 — Master-first creation

Unknown national ID → registration, not rejection (§1). Creation order matters:

```
Smart-ID verified  →  master: create users row (mints the UUID)
                   →  idp-server: create user_credentials(SMART_ID, PNOEE-...) → that UUID
```

**The master mints the ID.** It is the golden record; `sub` is its customer ID. IdP-first creation
would put identity minting in the wrong tier.

This makes the master's write path real, so idp-server needs `customer:write` in addition to the
Phase 1 scopes. It is the one place the IdP cannot complete a flow alone — a write on the
authentication path, narrow and infrequent.

### 3.2 — The required action

Smart-ID authentication succeeds *before* we have an email. The certificate never carries one, and
Keycloak needs it or brokered login falls through to "Update Account Information."

So there is a genuine intermediate state: authenticated, but `/oauth2/authorize` cannot complete. The
flow diverts to a form, then resumes. Keycloak calls these "required actions"; same shape.

Collect the **minimum** — email, and nothing else. Optionally offer "set a password," but route it
through `CredentialEnrolmentService` (§2A.5) rather than making it a branch of registration. The
registration form is a *caller*, not an owner.

Emit `email_verified: false` for a collected-but-unverified address. See Phase 1 §6 — this is the
point where the hardcoded `true` becomes an actual lie.

### 3.3 — Onboarding in resource-backend

`features.onboarding`. Owns the questionnaire, validation, state transitions, and the (stubbed)
screening decision. Keep it in its own feature package so the seam stays clean — extracting a feature
package later is easy; extracting data tangled into other features is not.

The SPA's existing `/register` flow becomes the KYC step rather than a generic profile step. **The
second form already exists** — Phase 2B adds the *first* one (in the IdP) and re-points the second.

`/api/user/**` → `hasRole("USER")` in
[SecurityConfig.java](../web-app/resource-backend/src/main/java/ee/authplayground/resourceserver/appcore/security/SecurityConfig.java)
must be relaxed: a `PENDING` user needs to reach onboarding endpoints while being denied everything
else. Small change, annoying to retrofit once more endpoints exist.

> **Deliberately out of scope:** real KYC, sanctions/PEP screening, document upload, manual review.
> Stub the decision to always-accept. The point is that the seam exists and is **not in idp-server**.
> If that check ever moves into the IdP, the IdP has become the customer master.

---

## 4. Getting status into the token

The design commitment is **stateless authorization** — everything the resource server needs is in the
token. That has a price and a mechanism.

### The price, stated once

Claims can deliver two of these three:

| | |
|---|---|
| Eventual consistency (within one token lifetime) | ✅ |
| Immediate on user action | ✅ |
| Immediate on external event | ❌ |

The third is not a flaw, it is the definition of the tradeoff. Every bank accepts either a staleness
window or a stateful check on the few operations that warrant one. **The access token TTL *is* the
consistency guarantee** — which is why it should be set explicitly rather than inherited.

> The realm currently sets **no token lifespans at all**, so it runs on Keycloak defaults (5 min
> access token, 30 min SSO idle, 10 h SSO max). Fine values; set them explicitly anyway. Also enable
> refresh token rotation (`revokeRefreshToken: true`) — bank-shaped and a one-liner.
>
> Unrelated trap: the `TokenSettings` in `AuthorizationServerConfig` (15 min / 8 h) governs the
> **Keycloak↔IdP hop only**. It has nothing to do with what the SPA holds. Easy to tune the wrong one
> and conclude the mechanism is broken.

### The taxonomy — and why one mechanism is not enough

Claims split on an axis that is easy to miss:

| | Registration status | Active profile (representation, later) |
|---|---|---|
| Scope | Property of the **principal** | Property of the **session** |
| Cardinality | One value per human | Different per session, concurrently |
| Home in Keycloak | Role / group / user attribute | **User session note** |

Storing a session-scoped fact as a user attribute means switching context in the browser also
switches it in the mobile app. Real bug, only reproducible with two live sessions, miserable to
diagnose. Any "which context am I acting in right now" claim is session-scoped.

Registration status is principal-scoped, so it goes in the user model. `registration_status` as a
**realm role** (`PROSPECT` / `CUSTOMER`) rather than an attribute, because it is genuinely an
authorization distinction and roles get realm-scoped enforcement for free.

### The mechanism: enricher authenticator + `prompt=none`

idp-server can emit the *initial* status but will never learn about the transition to ACTIVE. **A
status claim sourced from the IdP is correct exactly once and stale forever after** — worse than no
claim, because people trust it. So Keycloak fetches it instead.

A custom **authenticator** in the browser flow calls the master (`customer:read`), writes the result
to a **user session note**, and a session-note protocol mapper lifts it into every token minted from
that session. Fetch happens once per login, not once per refresh — a much better availability profile
than putting the master on every token mint.

**Re-triggering it without logout:** a fresh authorization request with **`prompt=none`**, in a hidden
iframe. Standard OIDC: run the flow, but fail rather than prompt if interaction is needed. With a live
SSO cookie it completes silently — no UI, no Smart-ID PIN — and returns a fresh code.

The distinction that matters:

- **`refresh_token` grant** — reuses the session. Protocol mappers run. Authenticators do **not**. A
  session note is re-read but never recomputed.
- **New authorize request** — the flow executes. Authenticators run. Notes get rewritten.

So `updateToken(-1)` will never pick up enrichment, however many times you call it. You need
re-authorization, not refresh. Expose one `reauthorize()` from `AuthProvider`; the representation
feature will need the same call later.

Logging the user out to force this would be a bad answer: for a Smart-ID user it means re-entering
PIN1 on their phone immediately after finishing a form.

### The realm flow must be restructured first

Current:

```
playground browser flow (top level)
  ├─ [ALTERNATIVE] auth-cookie                   priority 10
  └─ [ALTERNATIVE] identity-provider-redirector  priority 25
```

Both obvious ways to add an enricher are wrong:

- **Third ALTERNATIVE** → `auth-cookie` succeeds first and the flow short-circuits. The enricher never
  runs on a returning session — exactly the case that matters.
- **REQUIRED alongside them** → Keycloak does not execute ALTERNATIVE elements in a flow that also
  contains REQUIRED elements. Login breaks entirely, and the failure looks like "the redirector
  stopped working."

Required shape:

```
playground browser flow (top level)
  ├─ [REQUIRED] sub-flow "authenticate"
  │     ├─ [ALTERNATIVE] auth-cookie
  │     └─ [ALTERNATIVE] identity-provider-redirector
  └─ [REQUIRED] session-enricher
```

The enricher must be strictly non-interactive or `prompt=none` requests fail with
`interaction_required`.

**Decided:** if enrichment fails, the login fails. Give the fetch a tight timeout. Note the
consequence honestly — "master down" now means "nobody can start a session," which is the tier-0
classification from Phase 1 §2 being cashed in.

### The cross-realm oddity

The enricher runs inside Keycloak in the `playground` realm and needs a service token from
`playground-services`. That is an HTTP POST from Keycloak to its own token endpoint on a different
realm path. It works, it is correct, and it looks bizarre. **Cache the token** — it is on the login
hot path.

### Packaging cost

A custom authenticator is a JAR built against Keycloak's SPI, mounted at `/opt/keycloak/providers/`.
That means `docker compose up` stops being self-contained: a Gradle build must run first, and the
Keycloak version pin now moves in lockstep with it. The `ProtocolMapper`/authenticator SPIs are among
Keycloak's more stable extension points, so upgrade churn is modest — but the build coupling is real
and should be documented in the README's run instructions.

### End-to-end

```
Smart-ID (idp-server)      identity proven from certificate
IdP required action        email → master: users row + user_credentials row
Keycloak enricher          fetch status → session note → claim
                           shadow user, ROLE_PROSPECT
SPA                        status PENDING → onboarding UI
resource-backend           questionnaire + (stubbed) screening → ACTIVE
                           → Keycloak Admin API: ROLE_PROSPECT → ROLE_CUSTOMER
SPA                        reauthorize() → full access
```

---

## 5. Phase 2C — QR / device-link flow (deferred, but designed for)

The notification flow is first **deliberately**: SK publishes explicit test accounts that trigger
`USER_REFUSED`, `WRONG_VC`, and `TIMEOUT`, and the Mock Service returns mapped results immediately off
the document number. Device-link requires simulating the scan, and its published test accounts are
success cases and minors only. Notification-first proves the shared validation layer against real
failure cases before QR mechanics are layered on.

**The QR flow remains the eventual target.** Adds: `DeviceLinkBuilder` (byte-exact parameter
assembly), `authCode` HMAC-SHA256, `elapsedSeconds` tracking, an in-memory session registry, a
`GET /login/smart-id/qr` endpoint serving a fresh SVG per poll, and a 1-second frontend refresh loop.
ZXing for QR encoding is acceptable — that is not "the web request part" that must be handwritten.

**Constraints to preserve while building 2A:** `sessionSecret` must never reach the frontend, and the
session registry must hold per-session secrets and timing. Do not design session handling assuming a
single request/response round trip.

---

## 6. Protocol reference

Everything below came from SK's own documentation during analysis. **⚠️ marks facts that were never
confirmed and must be checked against the OpenAPI spec before they are written into code.**

### Environments and credentials

| | |
|---|---|
| Demo base (v3) | `https://sid.demo.sk.ee/smart-id-rp/v3/` |
| Demo portal | `https://sid.demo.sk.ee/portal` |
| Demo cert upload (OCSP) | `https://demo.sk.ee/upload_cert/` |
| Demo RP UUID | `00000000-0000-4000-8000-000000000000` |
| Demo RP name | `DEMO` |

### Endpoints

```
POST  {BASE}/v3/authentication/notification/etsi/{semantics-identifier}   ← 2A target
POST  {BASE}/v3/authentication/device-link/anonymous                      ← 2C target
GET   {BASE}/v3/session/{sessionID}                                       ← shared
```

Semantics identifier: `PNO` + ISO country + `-` + national ID, e.g. `PNOEE-30303039914`.

### ACSP_V2 payload

Pipe-separated, UTF-8, verified with the algorithm in `signature.signatureAlgorithm` (RSASSA-PSS):

```
smart-id|ACSP_V2|serverRandom|rpChallenge|userChallenge|BASE64(relyingPartyName)|BASE64(brokeredRpName)|BASE64(SHA-256(interactions))|interactionTypeUsed|initialCallbackUrl|flowType
```

Gotchas that will cost hours:

- `brokeredRpName` is **empty for us** — but the empty field still occupies its slot.
- `initialCallbackUrl` is **empty in the QR flow**, likewise still occupying its slot.
- Retain the **exact bytes** of the `interactions` JSON sent; re-serialising produces a different hash.

⚠️ **`signatureProtocol` for the notification flow was never confirmed.** `ACSP_V2` is documented for
device-link authentication; the notification page states only that auth and signature use separate
protocols — a split by operation, not by flow. Reasonable inference, still an inference. Settle
against the OpenAPI spec before writing the request DTO.

### Verification code (notification flow)

SHA-256 the `rpChallenge`, take the **2 rightmost bytes**, big-endian unsigned, last 4 decimal digits.
Use the **raw bytes**, not their Base64 encoding.

⚠️ SK's own pseudocode reads `integer(SHA-256(rpChallenge)[-2:-1]) mod 10000`, which as a Python slice
is **one** byte, contradicting the prose. Two bytes is almost certainly right — cross-check against
the Java client before implementing.

### Test accounts (demo)

| Purpose | Identifier |
|---|---|
| Success, adult, EE | `PNOEE-40404040009-MOCK-Q` |
| Notification success (EE) | `PNOEE-50001029996-DEMO-Q` |
| Underage (born 2011-01-01) | `PNOEE-61101012257-DEMO-Q` |
| `USER_REFUSED` | `PNOEE-30403039917-MOCK-Q` |
| `WRONG_VC` | `PNOEE-30403039972-MOCK-Q` |
| `TIMEOUT` | `PNOEE-30403039983-MOCK-Q` |

LT / LV / BE equivalents exist. The failure accounts are why notification flow goes first.

### Trusted CAs

The truststore holds **SK's issuing CA certificates**, validated explicitly rather than against the
JVM default truststore.

**Critical: the demo CA chain differs from production.** A truststore built for `sid.demo.sk.ee`
rejects every production certificate and vice versa. Scope by environment from day one —
`src/main/resources/smart-id/demo/...`, not a flat pile.

Demo certificates are **not automatically in OCSP** — they must be uploaded manually. Make revocation
checking configurable and **off by default**, with a prominent comment that production must do it.

---

## 7. Definition of done

**Phase 2A**
- [ ] Seeded user authenticates with Smart-ID against the demo environment; brokered chain completes.
- [ ] Signature, certificate chain, `rpChallenge`, validity dates and certificate level all verified
      explicitly — not `endResult == OK`.
- [ ] `USER_REFUSED`, `WRONG_VC`, `TIMEOUT` each produce a distinct, sensible message on the login page.
- [ ] Token carries `amr: ["smartid"]` and `acr: strong`.
- [ ] Same user, both methods, **same `sub`** — at `acr: weak` vs `acr: strong`. This is the whole
      point of the credential split; verify it by decoding both tokens.
- [ ] Add-password and add-Smart-ID both work from an authenticated session and share one code path.

**Phase 2B**
- [ ] Unknown national ID → registration → master mints the row → login completes.
- [ ] `email_verified: false` on a collected address; no "Update Account Information" page.
- [ ] New user lands with `ROLE_PROSPECT`, reaches onboarding endpoints, is denied everything else.
- [ ] Completing onboarding + `reauthorize()` yields `ROLE_CUSTOMER` **without a logout**.
- [ ] `prompt=none` re-authorization completes silently with a live SSO session.
- [ ] Enricher failure fails the login, cleanly and legibly.

---

## 8. Open questions

### Blocking

| # | Question | Needed by |
|---|---|---|
| 1 | Is `signatureProtocol` `ACSP_V2` for the notification flow? Confirm against the OpenAPI spec. | 2A.1 |
| 2 | Verification code: 2 bytes or 1? Cross-check SK's prose against the Java client. | 2A.2 |
| 3 | Does Keycloak create a usable shadow user when email arrives from the **enricher** rather than the ID token? The realm has `updateProfileFirstLoginMode: "off"` and `trustEmail: true`, so it will not prompt — but whether it tolerates a null email and lets the enricher fill it is exactly the kind of thing that behaves unexpectedly. **Test this path early.** Fallback: idp-server fetches email from the master purely to emit it. | 2B.2 |

### Design

| # | Question | Notes |
|---|---|---|
| 4 | Does resource-backend call the Keycloak Admin API directly for the role flip, or is there a narrower seam? | Admin API is a broad privileged surface. Scope the service account as tightly as Keycloak allows — ideally "may add/remove exactly these two roles." |
| 5 | Does `syncMode` move `IMPORT` → `FORCE` in this phase? | Under the banking lens this stops being a preference: `IMPORT` means the shadow user is a permanently stale copy, so a suspension never propagates. It is what makes revocation-on-next-login real. Strong lean: yes, do it here. |
| 6 | Does back-channel logout get pulled in? | Note: Spring Security 7's `OidcBackChannelLogoutHandler` is **client-side** — it lets a Spring app *receive* logout tokens. Spring Authorization Server as an OP does **not** send them. So the BACKLOG item is solved by Keycloak calling idp-server's `end_session_endpoint` (RP-initiated), not by back-channel logout. |
| 7 | What goes in `username` for a Smart-ID-only user? | Much lower stakes now that it is a display handle. Derive from the certificate name, or leave null and display the person's name. |
| 8 | Is the representation/delegation feature in scope after this? | It is the stress test that justified the session-note design. Note that **Keycloak's token-exchange delegation is experimental** (needs `token-exchange-delegation` + `parameterized-scopes`), so the RFC 8693 `act`/`may_act` path is not available on 26.4.x. Session notes + re-authorize is the mature route. |

### Resolved

- ✅ Open population — register unknown Smart-ID holders. Withdraws an earlier closed-population
  conclusion; see §1.
- ✅ Notification flow first, QR designed-for and deferred.
- ✅ Registration requires Smart-ID; password is enrolment-only, from an authenticated session.
- ✅ Status reaches the token via a Keycloak enricher authenticator, refreshed by `prompt=none`
  re-authorization rather than logout.
- ✅ Keep the country + national ID login form fields; do not delete them.
- ✅ Trusted CA certificates get their own store, scoped by environment.
- ✅ Enrichment failure fails the login.
