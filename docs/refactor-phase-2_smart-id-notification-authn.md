# Refactor Phase 2 — Smart-ID authentication (notification flow)

> **Status:** done. Smart-ID notification login works end to end against SK's DEMO environment for
> both seeded users, the brokered chain completes, and the same person reaches the same `sub` by
> password and by Smart-ID at `acr: weak` and `acr: strong` respectively. The three published failure
> accounts each produce their own message, and an identity SK knows but the master does not is
> rejected. Every §5 box is ticked against a running system rather than against the code.
>
> **One known defect is carried, not fixed:** a security review found that the Smart-ID session is
> not bound to the browser that started it, which permits login CSRF. It is recorded as
> [known issue #1](known-issues-to-investigate.md#1-smart-id-login-csrf-the-smart-id-session-is-not-bound-to-the-browser-that-started-it),
> along with the three code comments that currently claim the opposite.
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
> **Self-contained.** Every Smart-ID protocol fact this plan relies on is reproduced in §4, and §4 has
> since been **verified against SK's published RP API v3 documentation and the
> [`SK-EID/smart-id-java-client`](https://github.com/SK-EID/smart-id-java-client) reference
> implementation** (2026-08-22). That pass corrected four things — see §4 and §6. Earlier analysis
> drafts in `docs/` are legacy and superseded; do not read them as current design.
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

⚠️ **Only `conan` is on SK's published demo account list.** `PNOEE-50001029996` is not, and an earlier
draft of §4 asserted it was — see §4 and §6. If DEMO does not know that code, `conan` is this phase's
only happy path. Nothing structural changes; it changes which user the §5 checks run against, and it
is worth establishing with one session-create call before 2.4 rather than discovering at the end.

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

`@ConfigurationProperties("playground.idp.smart-id")`: base URL, RP UUID, RP name, **scheme name**
(`smart-id-demo` — §4), certificate level (`QUALIFIED`), **signature and hash algorithm**, poll
timeouts, truststore location, revocation-check toggle.

**Base URL, scheme name and truststore are one environment-scoped set and must move together.** A
demo truststore with a production scheme name is a configuration that fails at signature
verification, which is a long way from where the mistake was made.

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
- Verify the RSASSA-PSS signature with the certificate's public key, **using the same hash algorithm
  we asked for in the request** — it is a request field, not just a response field (§4).
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

Everything below is confirmed against SK's published RP API v3 documentation and the
`SK-EID/smart-id-java-client` reference implementation unless marked otherwise. **⚠️ marks what is
still an inference.** Where SK's prose and SK's own client disagree, the client wins — that happened
once below (the verification code), and the client was right.

### Environments and credentials

| | |
|---|---|
| Demo base | `https://sid.demo.sk.ee/smart-id-rp` — see the warning below |
| Demo portal | `https://sid.demo.sk.ee/portal` |
| Demo cert upload (OCSP) | `https://demo.sk.ee/upload_cert/` |
| Demo RP UUID | `00000000-0000-4000-8000-000000000000` |
| Demo RP name | `DEMO` |
| Demo **scheme name** | `smart-id-demo` (production: `smart-id`) |

### Endpoints

```
POST  {BASE}/v3/authentication/notification/etsi/{semantics-identifier}   ← this phase
POST  {BASE}/v3/authentication/device-link/anonymous                      ← Phase 4
GET   {BASE}/v3/session/{sessionID}                                       ← shared
```

Semantics identifier: `PNO` + ISO country + `-` + national ID, e.g. `PNOEE-30303039914`.

⚠️ **The base URL does not include `/v3`.** The OpenAPI `servers` entry is
`https://sid.demo.sk.ee/smart-id-rp` and every path above starts `/v3/`. SK's prose quotes the base
as `.../smart-id-rp/v3/`, so combining the two produces `/v3/v3/...` and a 404 that reads like the
endpoint moved.

### Request body

`signatureProtocol` is **`ACSP_V2`** — confirmed. The split is by *operation*, not by flow: `ACSP_V2`
for every authentication request, `RAW_DIGEST_SIGNATURE` for signing. The original inference was
right.

```json
{
  "relyingPartyUUID": "00000000-0000-4000-8000-000000000000",
  "relyingPartyName": "DEMO",
  "certificateLevel": "QUALIFIED",
  "signatureProtocol": "ACSP_V2",
  "signatureProtocolParameters": {
    "rpChallenge": "<Base64 of 32–64 random bytes, so 44–88 chars>",
    "signatureAlgorithm": "rsassa-pss",
    "signatureAlgorithmParameters": { "hashAlgorithm": "SHA-512" }
  },
  "interactions": "<Base64 of the interactions JSON>",
  "vcType": "numeric4"
}
```

**The literals are case-sensitive and not the shapes you would guess.** `rsassa-pss` and `numeric4`
are lower-case; the field is `vcType`, not `verificationCodeType`; and `hashAlgorithm` is nested one
level down in `signatureAlgorithmParameters`, not a sibling of `signatureAlgorithm`. `HashAlgorithm`
allows `SHA-256/384/512` and `SHA3-256/384/512`, spelled with the hyphen. Required fields are
`relyingPartyUUID`, `relyingPartyName`, `signatureProtocol`, `signatureProtocolParameters`,
`interactions` and `vcType`; `certificateLevel` defaults to `QUALIFIED` but we send it explicitly.

`capabilities` and `requestProperties` are optional; we send neither. **There is no
`initialCallbackUrl` in this request** — it is not even in the notification request schema, which is
the strongest form of the point made below.

The response is **only** `{"sessionID": "..."}`. No verification code comes back — we compute it
ourselves from the `rpChallenge` we generated, which is what makes it a *verification* code.

**The RP chooses the algorithms.** `signatureAlgorithm` and `hashAlgorithm` are *request* fields, not
merely response fields to read back, and whatever is sent here is what the returned signature must be
verified with. Sending one and verifying with another fails silently and totally.

### ACSP_V2 payload

Pipe-separated, UTF-8. Two separate response fields govern verification and both are needed:
`signature.signatureAlgorithm` (the scheme — RSASSA-PSS) and
`signature.signatureAlgorithmParameters.hashAlgorithm` (the digest — SHA-512 by default). The second
is easy to miss.

```
{schemeName}|ACSP_V2|serverRandom|rpChallenge|userChallenge|BASE64(relyingPartyName)|BASE64(brokeredRpName)|BASE64(SHA-256(interactions))|interactionTypeUsed||{flowType}
```

Slot 10 is deliberately written empty above. Gotchas, each of which will cost hours:

- **`schemeName` is `smart-id-demo` here, not `smart-id`.** It is a per-environment value rather than
  a constant, and this whole phase runs against DEMO — a hardcoded `smart-id` verifies nothing, ever.
  Config it alongside the truststore and scope it identically (§2.1).
- **`initialCallbackUrl` (slot 10) is empty in this flow — and in the QR flow too.** The split is not
  notification-versus-device-link, it is **cross-device versus same-device**: only `Web2App` and
  `App2App` populate it, because only they have a browser on the same handset to hand the user back
  to. `Notification` and `QR` have nowhere to call back to, so both send an empty slot. The slot is
  still there in every case; dropping the field rather than emptying it produces a signature that
  will not verify, with no useful error. An earlier draft of this document had the notification flow
  populating it, which is exactly the mistake the empty-slot discipline exists to survive.
- `brokeredRpName` is **empty for us** — likewise an empty field occupying its slot.
- **`flowType` is the description string, not the enum name:** `Notification`, capitalised. The four
  values are `QR`, `Web2App`, `App2App`, `Notification`.
- **The interactions digest covers the Base64 string as sent, not the JSON inside it.**
  `interactions` is the name of the request *field*, and that field holds Base64 — so slot 8 is
  `BASE64(SHA-256(utf8(base64String)))`. Decoding first is the more principled-feeling reading and
  it is wrong. **This one was caught by a live session, not by reading**, because a wrong digest
  still produces eleven well-formed slots and fails as nothing more specific than "signature did not
  verify". SK's own client settles it: `InteractionUtil.calculateDigest` hashes
  `String.getBytes(UTF_8)` of whatever it is handed, and the validator hands it the encoded field.
  <br>The upside is that retention gets *simpler*: hold the one Base64 string you sent and there is
  nothing a re-serialisation can drift out of.

### Verification code (notification flow)

SHA-256 the `rpChallenge`, take the **2 rightmost bytes**, big-endian unsigned, last 4 decimal digits,
zero-padded. Use the **raw bytes**, not their Base64 encoding.

✅ **Two bytes, confirmed.** SK's own pseudocode reads
`integer(SHA-256(rpChallenge)[-2:-1]) mod 10000`, which as a Python slice is **one** byte and
contradicts their prose. The prose is right: `VerificationCodeCalculator` reads a big-endian
`short` at `length - 2`, masks it with `0xffff`, and zero-pads to four digits.

### Test accounts (demo)

SK publishes these as **document numbers** — the `-MOCK-Q` / `-DEMO-Q` suffix belongs to the document
number, not to the identity. This phase's endpoint takes an **ETSI semantics identifier**, so the
suffix comes off; Phase 4's device-link endpoints take the document number as published.

| Purpose | Semantics identifier (this phase) | Document number (Phase 4) |
|---|---|---|
| Success, adult, EE | `PNOEE-40404040009` | `PNOEE-40404040009-MOCK-Q` |
| Underage, EE | `PNOEE-61101019999` | `PNOEE-61101019999-MOCK-Q` |
| `USER_REFUSED` | `PNOEE-30403039917` | `PNOEE-30403039917-MOCK-Q` |
| `WRONG_VC` | `PNOEE-30403039972` | `PNOEE-30403039972-MOCK-Q` |
| `TIMEOUT` | `PNOEE-30403039983` | `PNOEE-30403039983-MOCK-Q` |

LT / LV / BE equivalents exist. The failure accounts are why the notification flow goes first.

⚠️ The suffix-stripping rule is an inference from the endpoint's path parameter being
`{id-etsi-qcs-SemanticsId-Natural}`. It is strongly indicated and cheap to settle — one session-create
call answers it — so settle it there rather than by more reading.

Two corrections against the earlier draft of this table: the underage account is `61101019999`, not
`61101012257`; and **`PNOEE-50001029996` — `matrix`'s seeded code — is not on SK's published list at
all.** See §1.

### Trusted CAs

The truststore holds **SK's issuing CA certificates**, validated explicitly rather than against the
JVM default truststore.

**Critical: the demo CA chain differs from production.** A truststore built for `sid.demo.sk.ee`
rejects every production certificate and vice versa. Scope by environment from day one —
`src/main/resources/smart-id/demo/...`, not a flat pile.

Demo certificates are **not automatically in OCSP** — they must be uploaded manually. Make revocation
checking configurable and **off by default**, with a prominent comment that production must do it.

### Official API docs

Every fact in §4 traces to one of these. Checked 2026-08-22 against RP API documentation **v3.2.3** —
the site is versioned and carries the version in its banner, so check it before trusting anything here
against a newer release.

| Page | What it settles |
|---|---|
| [OpenAPI specification](https://sk-eid.github.io/smart-id-documentation/rp-api/api_specification.html) · [this phase's endpoint](https://sk-eid.github.io/smart-id-documentation/rp-api/api_specification.html#tag/authentication-session/POST/v3/authentication/notification/etsi/%7Bid-etsi-qcs-SemanticsId-Natural%7D) | Request and response schemas — **the authority for exact field names and literal casing.** Redoc-rendered, so the HTML reads poorly; fetch the raw spec instead (next row). |
| **[`RP-API_V3.yml`](https://sk-eid.github.io/smart-id-documentation/_/static/RP-API_V3.yml)** — the raw OpenAPI file the page above renders | ~1700 lines, greppable, and the fastest way to settle any wire-format question. Every literal in "Request body" above came from here. Prose pages paraphrase it and drift; this does not. |
| [Notification based flows](https://sk-eid.github.io/smart-id-documentation/rp-api/notification_based_flows.html) | This phase's flow end to end, and the verification-code algorithm in prose. |
| [Signature protocols](https://sk-eid.github.io/smart-id-documentation/rp-api/signature_protocols.html) | `ACSP_V2` vs `RAW_DIGEST_SIGNATURE`, the payload field order, the encoding rules. |
| [Response verification](https://sk-eid.github.io/smart-id-documentation/rp-api/response_verification.html) | **Read this before writing §2.3.** It is the validation the trust model rests on. |
| [Interactions](https://sk-eid.github.io/smart-id-documentation/rp-api/interactions.html) | Interaction types and their JSON — the bytes slot 8 hashes. |
| [Callback URLs](https://sk-eid.github.io/smart-id-documentation/rp-api/callback_urls.html) | Which flows populate `initialCallbackUrl` and which send it empty. |
| [API technical description](https://sk-eid.github.io/smart-id-documentation/rp-api/api_details.html) | Session polling, `endResult` values, certificate levels. |
| [Environments](https://sk-eid.github.io/smart-id-documentation/environments.html) | DEMO base URL, RP UUID and name, and **`schemeName`**. |
| [Test accounts](https://sk-eid.github.io/smart-id-documentation/test_accounts.html) | The demo identities above. |
| [Mock Service](https://sk-eid.github.io/smart-id-documentation/rp-api/mock_service.html) | Driving `USER_REFUSED` / `WRONG_VC` / `TIMEOUT` without a handset. |
| [Overview of API endpoints](https://sk-eid.github.io/smart-id-documentation/rp-api/overview_of_api_endpoints.html) | The whole endpoint list on one page. |

Also on the site and not consulted for this phase:
[Additional security measures](https://sk-eid.github.io/smart-id-documentation/rp-api/additional_security_measures.html),
[Smart-ID integration](https://sk-eid.github.io/smart-id-documentation/implementation.html), and the
[device-link pages](https://sk-eid.github.io/smart-id-documentation/rp-api/device_link_flows.html),
which are [Phase 4](refactor-phase-4_smart-id-device-link-qr.md)'s.

**Reference implementation:**
[`SK-EID/smart-id-java-client`](https://github.com/SK-EID/smart-id-java-client) — read as reference,
never taken as a dependency (see the scope note at the top). It is the tie-breaker when SK's own prose
is ambiguous or self-contradictory, which happened twice. The classes that settled §4:

| Class | Settles |
|---|---|
| `VerificationCodeCalculator` | Two bytes: a big-endian `short` at `length - 2`, masked `0xffff`. |
| `FlowType` | `QR` / `Web2App` / `App2App` / `Notification` — the description strings slot 11 takes. |
| `NotificationAuthenticationSessionRequestBuilder` | The request body above, including `signatureProtocol: ACSP_V2`. |
| `NotificationAuthenticationResponseValidator` | Payload reconstruction for this flow; slot 10 empty. |
| `DeviceLinkAuthenticationResponseValidator` | The cross-device/same-device rule behind slot 10. |
| `util/InteractionUtil` | Digest over the raw JSON, Base64 only for transport. |

⚠️ The [GitHub wiki](https://github.com/SK-EID/smart-id-documentation/wiki) is **superseded** and its
pages now partly fail to render, but it still ranks well in search and looks authoritative. Prefer the
site above; the wiki's own banner says the same.

---

## 5. Definition of done

- [x] Seeded user authenticates with Smart-ID against the demo environment; brokered chain completes.
      Both `conan` and `matrix` reach `AUTHENTICATED`, and the authorization endpoint issues a code to
      Keycloak's broker endpoint which exchanges for a populated ID token.
- [x] Signature, certificate chain, `rpChallenge`, validity dates and certificate level all verified
      explicitly — not `endResult == OK`. Confirmed live: a real demo response logs
      `validated for PNOEE-40404040009 (level QUALIFIED, flow Notification)`, and an earlier run with
      a one-field-wrong payload was **rejected**, which is the more useful half of the evidence.
- [x] `USER_REFUSED`, `WRONG_VC`, `TIMEOUT` each produce a distinct, sensible message. Exercised
      against all three published demo accounts.
- [x] Token carries `amr: ["smartid"]` and `acr: strong`. Read out of a decoded ID token, not
      inferred from the emitting code.
- [x] Same user, both methods, **same `sub`** — at `acr: weak` vs `acr: strong`. Verified by
      decoding both tokens for `conan`:

      Smart-ID  sub=8d1d0dc7-9333-4a69-babe-78d0e9be286d  acr=strong  amr=["smartid"]
      Password  sub=8d1d0dc7-9333-4a69-babe-78d0e9be286d  acr=weak    amr=["pwd"]
- [x] An unknown national ID is **rejected** with a clear message. Verified with demo account
      `PNOEE-61101019999` — a real Smart-ID identity our master does not hold: it authenticates at SK
      and is then refused here, which is exactly the boundary Phase 3 moves.
- [x] **Nothing was written.** After several full Smart-ID logins, `users` holds the two seeded rows
      and `user_credentials` holds two `PASSWORD` rows and nothing else. No new master endpoint, no
      new scope grant, no realm edit.
- [x] The validation layer takes no parameter and makes no assumption specific to the notification
      flow. Everything flow-specific is a value in `SmartIdExpectation`; the flow type is a set the
      caller supplies, not a branch.

---

## 6. Open questions

### Blocking

**None.** Both blockers were settled on 2026-08-22 against SK's published RP API v3 documentation and
the `SK-EID/smart-id-java-client` reference implementation. §4 now carries the confirmed values.

### Settled by running it

Both were answered by live sessions against DEMO, which is what they were flagged for.

- ✅ **`PNOEE-50001029996` (`matrix`) does exist in DEMO** and authenticates. SK's published
  test-account list simply does not include it, so the absence was in the documentation rather than
  in the environment. Both seeded users work, and the §5 "same `sub`" check has two subjects to run
  against rather than one.
- ✅ **Demo CA certificates located and shipped.** Issuing CAs from SK's published certificate list,
  test roots from the trust anchor store in `SK-EID/smart-id-java-demo`. Provenance, contents and
  rebuild commands are in
  [`smart-id/demo/README.md`](../authorization-server/idp-server/src/main/resources/smart-id/demo/README.md).
  Note that SK's own demo store bundles the *production* root; it is deliberately excluded here.

### Resolved

- ✅ **`signatureProtocol` is `ACSP_V2`** for notification authentication. The split is by operation,
  not by flow — `ACSP_V2` for authentication, `RAW_DIGEST_SIGNATURE` for signing. The inference this
  document made was correct.
- ✅ **The verification code uses 2 bytes**, not 1. SK's prose beats SK's pseudocode.
- ✅ **The ETSI endpoint takes the bare semantics identifier** — `PNO`/`PAS`/`IDC` + upper-case ISO
  3166-1 alpha-2 country + `-` + the national code, per ETSI EN 319 412-1. The `-MOCK-Q` suffix on
  SK's published demo accounts is part of the *document number* and has no place in this path.
  Settled from the spec's own parameter definition, not inferred.
- ✅ **The interactions digest covers the Base64 string, not the JSON.** Found by running it, after
  the reading-based pass got it backwards — see §4. The lesson generalises: the ACSP_V2 payload has
  exactly one failure message for eleven possible mistakes, so the first live session is worth more
  than the third careful re-read.
- ✅ Four §4 errors corrected in the same pass, each of which would have failed signature
  verification with no useful diagnostic: `schemeName` is `smart-id-demo` and per-environment;
  `initialCallbackUrl` is empty in this flow *and* in QR, populated only by the same-device
  device-link flows; `flowType` is `Notification`, the description string; and the RP chooses
  `signatureAlgorithm` + `hashAlgorithm` in the request.
- ✅ Notification flow first; QR designed-for and deferred to Phase 4.
- ✅ **Smart-ID has no `user_credentials` row.** It is an *inherent* method: the state issued the
  identity, SK holds the key, and `users.national_id` + `users.nationality` are the whole binding.
  `user_credentials` holds *issued* credentials only. Phase 1 §2 carries the reasoning, and
  `UserCredentialType` carries it in the code.
- ✅ Keep the country + national ID login form fields; do not delete them.
- ✅ Trusted CA certificates get their own store, scoped by environment.
- ✅ This phase is read-only. Registration and enrolment are Phase 3.
