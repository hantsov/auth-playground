# Refactor Phase 4 — Smart-ID device-link (QR) flow

> **Status:** design, nothing implemented yet. Written to be picked up cold.
>
> **Depends on:** [Phase 2](refactor-phase-2_smart-id-notification-authn.md). This phase adds a second
> way to *start* a Smart-ID session; everything that happens after the session completes — signature
> reconstruction, certificate validation, identity extraction, Spring Security wiring — is Phase 2's
> validation layer, reused verbatim. If that layer turned out to be flow-specific, fixing it is the
> first task here.
>
> Independent of [Phase 3](refactor-phase-3_user-registration-and-onboarding.md). QR and registration
> touch different layers and can land in either order. If Phase 3 lands first, QR inherits
> registration for free — an unknown identity arriving by QR is the same unknown identity.
>
> **Scope:** the device-link / QR authentication flow against SK's DEMO environment. No new identity
> semantics, no new claims, no schema change.

---

## 1. Why this was deferred

Not because QR is harder — because it is worse to learn on.

SK publishes explicit test accounts that trigger `USER_REFUSED`, `WRONG_VC`, and `TIMEOUT`, and the
Mock Service returns mapped results immediately off the document number. **Device-link's published
test accounts are success cases and minors only**, and exercising it at all requires simulating the
scan by POSTing session values to the mock's device-link endpoint.

So the notification flow proves the shared validation layer against real failure cases first. By the
time this phase starts, the part that is genuinely security-critical — signature reconstruction,
chain validation, `rpChallenge` matching, certificate level — is already written and already tested
against responses that fail in interesting ways. What remains here is session mechanics and a QR
image.

That ordering was the point of the split, and it is worth not undoing: **do not "quickly" reimplement
validation inside the device-link path.** One validation layer, two entry points.

---

## 2. What it adds

On top of Phase 2's client, session registry, and validation layer:

- **`DeviceLinkBuilder`** — byte-exact parameter assembly for the device link URI. Same class of
  problem as the `ACSP_V2` payload: the bytes are hashed, so re-serialising is not free.
- **`authCode`** — HMAC-SHA256 over the assembled link.
- **`elapsedSeconds` tracking** — the link is time-bound, so the session registry has to know when
  the session started, not merely that it exists.
- **An in-memory session registry** holding per-session secrets and timing. Phase 2 was told to build
  one that can carry this (§3); if it did, this is a field addition rather than a redesign.
- **`GET /login/smart-id/qr`** serving a fresh SVG per poll, with a ~1-second frontend refresh loop.

ZXing for QR encoding is acceptable — that is not "the web request part" that must be handwritten.
The handwritten-client rule exists so the protocol is legible in the code, and a QR encoder is not
protocol.

New package: `ee.authplayground.idpserver.features.smartid.devicelink`, alongside the `client`,
`session`, `validation`, `service` and `controller` packages Phase 2 established.

---

## 3. Constraints Phase 2 was built to preserve

Phase 2 §3 committed to three things specifically so this phase would be cheap. Check them before
starting — if any has drifted, fix it first rather than working around it:

- **`sessionSecret` never reaches the frontend.** The QR flow has per-session secret material. If the
  polling endpoint or the templates got used to receiving the whole session object, that is the thing
  to unpick.
- **Session handling does not assume a single request/response round trip.** Device-link needs
  per-session secrets and timing held across many polls.
- **The validation layer takes no notification-specific parameter.** It should receive a session
  response plus expected values and not know which flow produced them.

---

## 4. Protocol additions

Phase 2 §4 carries the full protocol reference — environments, RP credentials, the `ACSP_V2` payload,
the session endpoint, trusted CAs, and the demo test accounts. Only the device-link-specific parts are
repeated here.

**Phase 2 §4 was verified against SK's published RP API v3 documentation and the
[`SK-EID/smart-id-java-client`](https://github.com/SK-EID/smart-id-java-client) reference
implementation on 2026-08-22, and corrected in four places.** Those corrections apply here too —
notably the per-environment `schemeName` and the RP-chosen `signatureAlgorithm` / `hashAlgorithm`.
Read that section before this one; the same pass rewrote the two bullets below.

### Endpoint

```
POST  {BASE}/v3/authentication/device-link/anonymous                     ← this phase
POST  {BASE}/v3/authentication/device-link/etsi/{semantics-identifier}
POST  {BASE}/v3/authentication/device-link/document/{document-number}
GET   {BASE}/v3/session/{sessionID}                                      ← shared with Phase 2
```

The **anonymous** variant is the one that makes QR interesting: nobody types a national ID, so the
identity arrives entirely from the certificate. That is also why it composes so well with Phase 3 —
an anonymous scan by an unknown person is exactly the registration trigger.

### The `ACSP_V2` slot that changes

The payload is identical to Phase 2's, and **exactly one slot differs** — which is less than an
earlier draft of this document claimed:

```
{schemeName}|ACSP_V2|serverRandom|rpChallenge|userChallenge|BASE64(relyingPartyName)|BASE64(brokeredRpName)|BASE64(SHA-256(interactions))|interactionTypeUsed||{flowType}
```

- **`flowType` is `QR`** — the description string, capitalised as shown. That is the only slot whose
  value differs from Phase 2's, which sends `Notification`. The four values are `QR`, `Web2App`,
  `App2App`, `Notification`, and they name **how the user engaged**, not which endpoint the RP
  called: a device link opened same-device signs `Web2App` or `App2App` off the same session-create
  call. If this phase ever grows a same-device variant, this slot and the next bullet both change.
- `initialCallbackUrl` is **empty in this flow** — and the empty field **still occupies its slot**.
  **Phase 2 sends it empty too**, contrary to what this document previously said. The split is
  cross-device versus same-device, not QR versus notification: only `Web2App` and `App2App` populate
  it, because only they have a browser on the same handset to return the user to. Dropping the field
  rather than emptying it produces a signature that will not verify, with no useful error.
- `schemeName` is `smart-id-demo` on DEMO, not the literal `smart-id` — per-environment, and Phase 2
  §4 has the reasoning.
- `brokeredRpName` remains empty for us, as in Phase 2, and likewise keeps its slot.

### No verification code

The 4-digit verification code is a notification-flow concept. Device-link confirms by the scan itself,
so Phase 2's verification-code computation is not reused here — do not display a stale one.

### Testing without a phone

SK's Mock Service simulates the handset. For notification requests it returns mapped results
immediately off the document number; **device-link requires the RP to POST session values to the
mock's device-link endpoint to simulate the scan.** That extra step is the mechanical reason this
phase is more work to exercise than Phase 2.

A real demo handset is also available (Android testing track / iOS TestFlight, register at
`sid.demo.sk.ee/portal`) and is worth having for a QR demo that convinces anyone.

### Official API docs

[Phase 2 §4](refactor-phase-2_smart-id-notification-authn.md) carries the full index — environments,
signature protocols, response verification, test accounts, and the reference client. These are the
pages **this** phase needs on top of it. Checked 2026-08-22 against RP API documentation **v3.2.3**;
the site is versioned, so check its banner before trusting a fact against a newer release.

| Page | What it settles |
|---|---|
| [Device link flows](https://sk-eid.github.io/smart-id-documentation/rp-api/device_link_flows.html) | The core page for this phase: device-link URI construction, `authCode` (HMAC-SHA256 keyed by `sessionSecret`), `elapsedSeconds`, and the same-device vs. cross-device parameter split. Also QR rendering guidance — error correction **LOW** or **MEDIUM**, 6–10px blocks for desktop. |
| [Device link test endpoint](https://sk-eid.github.io/smart-id-documentation/rp-api/device_link_test_endpoint.html) | The endpoint "Testing without a phone" above describes POSTing session values to. |
| [Mock Service](https://sk-eid.github.io/smart-id-documentation/rp-api/mock_service.html) | Simulating the handset generally. |
| [Callback URLs](https://sk-eid.github.io/smart-id-documentation/rp-api/callback_urls.html) | Which flows populate `initialCallbackUrl`; settles the slot-10 rule above. |
| [OpenAPI specification](https://sk-eid.github.io/smart-id-documentation/rp-api/api_specification.html) | The three device-link session-create endpoints listed in §4. |

From the reference client
([`SK-EID/smart-id-java-client`](https://github.com/SK-EID/smart-id-java-client), read as reference
and not depended on): `DeviceLinkAuthenticationResponseValidator` settled the slot-10 rule, and
`FlowType` settled `QR`.

---

## 5. Definition of done

- [ ] A QR code renders on the login page, refreshes on its timer, and a scan completes the login.
- [ ] The **same validation layer** handles both flows — verify by reading the call graph, not by
      the fact that both happen to work.
- [ ] `initialCallbackUrl` is empty **and still present** in the signed payload; a signature that
      verifies is the proof.
- [ ] `sessionSecret` appears nowhere in any HTTP response to the browser — check the network tab,
      not the intent.
- [ ] An expired device link fails cleanly, with a message distinguishable from `USER_REFUSED`.
- [ ] Same person, all three methods (password, Smart-ID notification, Smart-ID QR), **same `sub`** —
      with QR and notification at the same `acr: strong`, since assurance is a property of the method
      and not of how the session was started.

---

## 6. Open questions

### Blocking

| # | Question | Needed by |
|---|---|---|
| 2 | Does the anonymous device-link variant return enough to identify the person without a prior national ID, in the shape Phase 2's validation layer expects? | 2 |

### Design

| # | Question | Notes |
|---|---|---|
| 3 | Does the QR panel share the notification panel's polling endpoint, or get its own? | Shared keeps one status contract for the frontend; separate keeps the QR-specific timing fields out of the notification response. Lean: shared endpoint, nullable QR fields. |
| 4 | Where does the session registry live once it holds secrets and timing? | In-memory is fine for a playground and stated as such. Worth a comment noting that a real deployment needs it shared across instances, which is a different design. |
| 5 | Does the 1-second refresh loop need backoff? | A tab left open on the login page is a self-inflicted load test. Cheap to cap. |

### Resolved

- ✅ **`flowType` for this flow is `QR`** — settled against the `SK-EID/smart-id-java-client`
  reference implementation (2026-08-22), not inferred from the notification flow. §4 has the full
  set and the rule behind it.
- ✅ **`initialCallbackUrl` is empty here *and* in Phase 2.** This document previously said Phase 2
  populated it; it does not. Only the same-device flows do. §4.
- ✅ Notification flow first, QR deferred — see §1. The reason was failure-case coverage, not
  difficulty.
- ✅ ZXing is acceptable for QR encoding; the handwritten-client rule covers protocol, not image
  formats.
- ✅ Assurance does not vary by flow: QR and notification both emit `acr: strong` / `amr: ["smartid"]`.
