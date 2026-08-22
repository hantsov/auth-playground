# Refactor Phase 2 — Smart-ID authentication (notification flow)

> **Status:** design, nothing implemented yet. Written to be picked up cold.
>
> **Depends on:** [Phase 1](refactor-phase-1_user-data-master-and-idp.md) — **done and verified.**
> The `users` schema with `national_id` + `nationality`, the `playground-services` realm, `sub` being
> the master's UUID, and the `acr` / `amr` transport through Keycloak are all in place. Seed users
> carry SK demo identity codes, so there is a known identity to authenticate.
>
> **Followed by:** [Phase 3 — registration + onboarding](refactor-phase-3_user-registration-and-onboarding.md)
> and [Phase 4 — device-link / QR](refactor-phase-4_smart-id-device-link-qr.md). Phase 3 needs this
> one's authentication path; Phase 4 needs its validation layer. They are independent of each other.
>
> **Self-contained.** Every Smart-ID protocol fact this plan relies on is reproduced in §4. Earlier
> analysis drafts in `docs/` are legacy and superseded; do not read them as current design.
>
> **Scope:** authenticating people who already exist, by Smart-ID notification flow. Authentication
> only — digital signing is out of scope. SK's DEMO environment only. The HTTP client is handwritten;
> SK's Java client is read as reference, not taken as a dependency.

---

## 1. What this phase is, and what it deliberately is not

Prove the whole protocol and validation layer against a **known, seeded** identity, before anything
else is layered on top. Phase 1 seeded `national_id` + `nationality` on both users with SK's
published demo identity codes precisely so this works with **no data change and no writes**:

| username | ETSI semantics identifier | password |
|---|---|---|
| `conan` | `PNOEE-40404040009` | `conan123` |
| `matrix` | `PNOEE-50001029996` | `matrix123` |

### The boundary, stated hard

**This phase writes nothing, anywhere.** No master writes, no new master endpoints, no new scopes, no
realm changes. It is idp-server code plus a truststore. That is not an accident of scoping — it is
what makes the phase safe to get wrong and cheap to iterate on, and it is worth defending when
something looks like it "may as well" go in here.

Three things are explicitly **out of scope**, each with a home:

| Not here | Where | Why not here |
|---|---|---|
| Registering unknown identities | [Phase 3](refactor-phase-3_user-registration-and-onboarding.md) | Needs `POST /internal/users`, `customer:write` for idp-server, and a required-action state machine. All writes. |
| Enrolling credentials from an account page | [Phase 3](refactor-phase-3_user-registration-and-onboarding.md) | "Add Smart-ID to my account" writes `national_id`; "add a password" only matters once Smart-ID-only users exist, which registration creates. |
| QR / device-link | [Phase 4](refactor-phase-4_smart-id-device-link-qr.md) | Different session mechanics on top of the same validation layer. |

If an unknown national ID authenticates during this phase, **reject it**. Registration is Phase 3's
job and doing it early is how the write path ends up in the authentication path by accident.

### Why the notification flow and not QR

SK publishes explicit test accounts that trigger `USER_REFUSED`, `WRONG_VC`, and `TIMEOUT`, and the
Mock Service returns mapped results immediately off the document number. Device-link requires
simulating the scan, and its published test accounts are success cases and minors only.

So notification-first proves the shared validation layer **against real failure cases** before QR
mechanics are layered on. That ordering is the point, not a convenience — see
[Phase 4](refactor-phase-4_smart-id-device-link-qr.md).

### Where password sits after this

Note the framing this gives the seeded users: they are "customers migrated from the legacy system,"
and Smart-ID is what they should have been using all along. Password is a **legacy + convenience**
method, not a peer of Smart-ID — a password proves someone knows a secret, while a Smart-ID
certificate carries a state-issued identity. Phase 3 §1 turns that observation into a rule; this
phase just needs both methods to reach the same `sub` at different `acr`.

---

## 2. Steps

### 2.1 — Feature skeleton + handwritten client

Package `ee.authplayground.idpserver.features.smartid`, sub-packaged by concept per AGENTS.md
(`client`, `session`, `validation`, `service`, `controller`; `devicelink` arrives in Phase 4).

`@ConfigurationProperties("playground.idp.smart-id")`: base URL, RP UUID, RP name, certificate level
(`QUALIFIED`), poll timeouts, truststore location, revocation-check toggle.

Spring Boot 4 → `RestClient`, ideally behind a declarative `@HttpExchange` interface so the endpoint
list reads like the documentation. Record DTOs.

**Milestone:** a notification session is created against the demo environment.

### 2.2 — Notification flow front end

Build the ETSI identifier from the country + national-ID fields **already present** in
[login.html](../authorization-server/idp-server/src/main/resources/templates/login.html) — they stop
being placeholders. Replace the disabled "Coming soon" button with a live submit.

Compute and display the 4-digit verification code. Poll **our own** backend endpoint, never SK's.

> The login page is currently deliberately JS-free (the CSS-only method picker is called out in a
> comment). Status polling needs JavaScript. Keep it confined to the Smart-ID panel.

### 2.3 — Session polling + response validation

`GET /v3/session/{sessionID}` with long-poll timeout; `RUNNING` → `COMPLETE`; map `endResult`.

Then the part worth writing verbosely, because it is the entire trust model:

- Reconstruct the `ACSP_V2` payload byte-exactly (§4).
- Verify the RSASSA-PSS signature with the certificate's public key.
- Check `rpChallenge` matches what we generated.
- Validate the chain against the **explicitly configured demo CA truststore**, not the JVM default.
- Check validity dates and `certificateLevel >= QUALIFIED`.
- Extract the identity code from the subject DN `serialNumber` (OID 2.5.4.5), format `PNOEE-...`.

A naive integration that checks `endResult == OK` and trusts the returned certificate is trivially
forgeable.

**This layer is flow-agnostic and Phase 4 reuses it verbatim. Design it that way** — see §3.

### 2.4 — Spring Security wiring

`SmartIdAuthenticationToken` + `AuthenticationProvider`, so it reads like idiomatic Spring Security
rather than a hand-stuffed `SecurityContext`. Permit `/login/smart-id/**` in `DefaultSecurityConfig`.

Resolution goes through the **person record, not a credential row**. Take the ETSI semantics
identifier out of the certificate's subject DN (`PNOEE-40404040009`), split it into country and
national ID, and look the person up with Phase 1's
`GET /internal/users/by-national-id/{nationalId}?nationality={c}` — which takes both halves because a
national ID is only unique within a country. Then `users.id` → principal, exactly as the password
path ends.

**There is deliberately no `SMART_ID` credential row to match against.** Smart-ID is an *inherent*
method: the state issued the identity, SK holds the key, and the national ID on the person record is
the entire binding. `user_credentials` holds *issued* credentials only — things we handed out and
hold a secret for. Phase 1 §2 carries the full reasoning.

What the two login paths share is the shape *after* resolution, not the lookup: identifier →
`users.id` → principal → same `sub`. That is the payoff of Phase 1's split, and it is why the same
person can arrive by either method at the same subject.

> Practical note: `UserMasterClient.findPasswordCredential(...)` is hardcoded to `PASSWORD` and
> returns a credential. The Smart-ID path needs a *different* call — the person lookup above — not a
> generalisation of that one.

**A miss is a rejection in this phase**, not a registration. Give it a clear message and a distinct
log line; Phase 3 replaces the rejection with a required action.

Emit `amr: ["smartid"]` and `acr: strong`, against Phase 1's `amr: ["pwd"]` / `acr: weak`. **The two
methods must be distinguishable downstream or there was no point differentiating them.**

**The Keycloak side of that already exists — do not rebuild it.** Phase 1 built and verified the
identity-provider attribute importers (`syncMode: FORCE`, so the values track each authentication
event rather than freezing at first login), the `react-client` protocol mappers that re-emit them,
and the removal of `acr` from that client's default scopes so there is exactly one writer. Changing
the constants in `OidcClaimsCustomizer` is the whole job. **Do not add a second `acr` mapper** — that
collision with Keycloak's built-in `oidc-acr-mapper` is precisely what Phase 1 removed.

**Milestone:** seeded user logs in with Smart-ID, brokered chain completes, SPA gets tokens carrying
`acr: strong`.

---

## 3. Constraints to preserve for Phase 4

Phase 4 is deferred, not abandoned, and two decisions made here are expensive to reverse later. Both
cost nothing now.

- **`sessionSecret` must never reach the frontend.** The QR flow has per-session secret material; if
  session handling is built assuming everything is safe to hand the browser, that assumption gets
  baked into the polling endpoint and the templates.
- **Do not design session handling around a single request/response round trip.** The session
  registry must be able to hold per-session secrets and timing (`elapsedSeconds`), because
  device-link needs both. A notification-only design that stashes state in the HTTP session will not
  extend.
- **Keep the validation layer (§2.3) free of notification-specific assumptions.** It receives a
  session response and a set of expected values; it should not know which flow produced them. This is
  the single largest piece of work in this phase and the whole reason Phase 4 is cheap.

---

## 4. Protocol reference

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
POST  {BASE}/v3/authentication/notification/etsi/{semantics-identifier}   ← this phase
POST  {BASE}/v3/authentication/device-link/anonymous                      ← Phase 4
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
- `initialCallbackUrl` is **empty in the QR flow**, likewise still occupying its slot. It is populated
  in this phase's flow; the empty-slot rule is what Phase 4 inherits.
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

LT / LV / BE equivalents exist. The failure accounts are why the notification flow goes first.

Note the first two match the seeded users' national IDs — that is deliberate, and it is what makes
this phase's happy path work with no data change.

### Trusted CAs

The truststore holds **SK's issuing CA certificates**, validated explicitly rather than against the
JVM default truststore.

**Critical: the demo CA chain differs from production.** A truststore built for `sid.demo.sk.ee`
rejects every production certificate and vice versa. Scope by environment from day one —
`src/main/resources/smart-id/demo/...`, not a flat pile.

Demo certificates are **not automatically in OCSP** — they must be uploaded manually. Make revocation
checking configurable and **off by default**, with a prominent comment that production must do it.

---

## 5. Definition of done

- [ ] Seeded user authenticates with Smart-ID against the demo environment; brokered chain completes.
- [ ] Signature, certificate chain, `rpChallenge`, validity dates and certificate level all verified
      explicitly — not `endResult == OK`.
- [ ] `USER_REFUSED`, `WRONG_VC`, `TIMEOUT` each produce a distinct, sensible message on the login page.
- [ ] Token carries `amr: ["smartid"]` and `acr: strong`.
- [ ] Same user, both methods, **same `sub`** — at `acr: weak` vs `acr: strong`. This is the whole
      point of Phase 1's split; verify it by decoding both tokens.
- [ ] An unknown national ID is **rejected** with a clear message. (Phase 3 turns this into a
      registration; until then, a silent pass-through is the failure mode to avoid.)
- [ ] **Nothing was written.** After a full Smart-ID login, `users` and `user_credentials` are
      byte-identical to before it. No new master endpoint, no new scope grant, no realm edit.
- [ ] The validation layer takes no parameter and makes no assumption specific to the notification
      flow — confirm by reading it, since Phase 4 is what proves it and Phase 4 is not now.

---

## 6. Open questions

### Blocking

| # | Question | Needed by |
|---|---|---|
| 1 | Is `signatureProtocol` `ACSP_V2` for the notification flow? Confirm against the OpenAPI spec. | 2.1 |
| 2 | Verification code: 2 bytes or 1? Cross-check SK's prose against the Java client. | 2.2 |

### Resolved

- ✅ Notification flow first; QR designed-for and deferred to Phase 4.
- ✅ **Smart-ID has no `user_credentials` row.** It is an *inherent* method: the state issued the
  identity, SK holds the key, and `users.national_id` + `users.nationality` are the whole binding.
  `user_credentials` holds *issued* credentials only. Phase 1 §2 carries the reasoning, and
  `UserCredentialType` carries it in the code.
- ✅ Keep the country + national ID login form fields; do not delete them.
- ✅ Trusted CA certificates get their own store, scoped by environment.
- ✅ This phase is read-only. Registration and enrolment are Phase 3.
