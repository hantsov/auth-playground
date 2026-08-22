# Refactor Phase 3 — user registration + onboarding

> **Status:** design, nothing implemented yet. Written to be picked up cold.
>
> **Depends on:** [Phase 2](refactor-phase-2_smart-id-notification-authn.md). Registration is
> triggered by a Smart-ID authentication that resolves to nobody, so the authentication path has to
> work first. Phase 2 rejects that case deliberately; this phase replaces the rejection.
>
> Independent of [Phase 4](refactor-phase-4_smart-id-device-link-qr.md) — QR and registration touch
> different layers and can land in either order.
>
> **Scope:** turning a proven identity into a customer. Three things happen here that Phase 2
> deliberately excluded: **writes to the master**, **credential enrolment**, and **registration
> status reaching the token**.

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
holders. **That conclusion is withdrawn.** It imports an assumption — "the bank knows you before you
show up" — that holds for a US bank or a corporate staff IdP and is false for an Estonian retail bank.

In Estonia a resident with Smart-ID opens an account fully online: identify with Smart-ID, answer a
questionnaire, done. There is no prior offline step creating the record. The state has already done
the identity proofing; the bank inherits it from the certificate and only decides whether to accept
the relationship. **Self-service registration at first login is the accurate model, not a playground
concession.**

The policy seam that conclusion implied is still worth having, but relabel what its arms mean. It is
not consumer-vs-bank — it is **self-service onboarding vs. pre-provisioned population**, and both
exist in banking:

```java
// UserResolutionPolicy
//   OPEN_POPULATION   → register unknown identity   (Estonian retail bank; our default)
//   CLOSED_POPULATION → reject unknown identity     (corporate IdP, branch-based onboarding)
```

Note that `CLOSED_POPULATION` is exactly what Phase 2 ships as its only behaviour. This phase adds
the other arm and makes the choice explicit rather than implied.

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

1. **Identity enrolment** — creating the `users` row, carrying the national ID the certificate
   proves, plus collecting the email it does not carry. Happens in **idp-server**. This is what
   "registration in the IdP" means. Note it creates no `user_credentials` row: Smart-ID has no secret
   to store, so a credential row appears only later, if the person enrols a password.
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

## 2. Credential enrolment

One `CredentialEnrolmentService`, called only from an **authenticated** session:

- "Add Smart-ID to my account" — sign in with password → account page → authenticate with Smart-ID →
  write `national_id` + `nationality` onto the **person record**.
- "Add a password to my Smart-ID account" — create the `PASSWORD` **credential row**.

The existing session is the proof in both directions, and that check is the part worth sharing. The
writes are not symmetric and should not be forced to look it: Smart-ID has no secret, so binding it
updates `users`, while a password updates `user_credentials`. Share the assurance gate; don't share
the write.

`UNIQUE (nationality, national_id)` is what stops two people binding the same state identity. Let the
constraint reject rather than checking first — a read-then-write leaves a race, and this is the one
table where losing it means one human's identity attached to another's account.

> The governing rule: **credential enrolment must happen from a session at or above the assurance
> level the credential will grant.** A password grants AAL1, so any authenticated session can enrol
> one. There is still no anonymous enrolment endpoint.

This lives here rather than in Phase 2 for two reasons, and both are about writes. Binding Smart-ID
needs `customer:write` and a master write endpoint — neither of which exists until §3.1 below. And
"add a password to my Smart-ID account" only becomes meaningful once Smart-ID-only users exist, which
is what registration creates.

---

## 3. Registration for unknown identities

### 3.1 — Master-first creation

Unknown national ID → registration, not rejection (§1). One write, in one place:

```
Smart-ID verified  →  master: create users row
                      (mints the UUID; stores national_id + nationality from the certificate)
```

That single row is the whole identity record. There is no second write for a credential — the
national ID on the person record is what the next Smart-ID login resolves against.

**The master mints the ID.** It is the golden record; `sub` is its customer ID. IdP-first creation
would put identity minting in the wrong tier.

This makes the master's write path real, and two things have to be built for it — neither exists
after Phase 1, and Phase 2 deliberately did not add them:

- **The endpoint.** The master is read-only today: `GET /internal/credentials`, `GET /internal/users/{id}`,
  `GET /internal/users/by-national-id/{nid}`. A `POST /internal/users` guarded by `customer:write`
  is new.
- **The grant.** `customer:write` exists as a scope in `playground-services` but is currently held
  only by `resource-backend`. idp-server needs it added to its `defaultClientScopes`.

It is the one place the IdP cannot complete a flow alone — a write on the authentication path, narrow
and infrequent. Keep the endpoint that narrow: it creates a person, and it must not be able to write
`user_credentials`, or `customer:write` quietly becomes "may write anything in the master" and the
one-client-one-capability story the services realm exists to tell stops being true.

### 3.2 — The required action

Smart-ID authentication succeeds *before* we have an email. The certificate never carries one, and
Keycloak needs it or brokered login falls through to "Update Account Information."

So there is a genuine intermediate state: authenticated, but `/oauth2/authorize` cannot complete. The
flow diverts to a form, then resumes. Keycloak calls these "required actions"; same shape.

Collect the **minimum** — email, and nothing else. Optionally offer "set a password," but route it
through `CredentialEnrolmentService` (§2) rather than making it a branch of registration. The
registration form is a *caller*, not an owner.

Store the collected address with `email_verified = false`. Phase 1 already made the IdP side honest —
`OidcClaimsCustomizer` emits the real `users.email_verified` column rather than the hardcoded `true`
it used to — so nothing needs changing in idp-server for the claim to come out correct.

**But the claim being correct is not the same as Keycloak believing it, and this is a trap.** The
`playground-idp` provider sets `"trustEmail": true`, which makes Keycloak **skip its own verification
step** for addresses arriving from this IdP. An honest `email_verified: false` travelling up does not,
by itself, stop Keycloak marking the shadow user's email verified. Decode the IdP's token and the
claim looks right; look at the shadow user and it isn't.

This is Phase 1 §6 / its open question 10 coming due — Phase 1 deliberately left `trustEmail` alone
because every seeded address was a fixture we controlled, and flagged that the first form-collected
address is when it stops being true. That is now. Decide one of:

- Drop `"trustEmail": true` and let Keycloak run its own verification (changes the flow for *every*
  user, including password users whose addresses are fine).
- Keep it and accept that `email_verified` is advisory downstream — in which case say so out loud,
  because a claim nobody enforces is worse than an absent one.
- Verify the address in the IdP before the required action completes, so `false` is transient.

Whichever way it goes, verify it **end to end** rather than by decoding the IdP's token — the DoD
item below is written to fail if you only check the near side.

### 3.3 — Onboarding in resource-backend

`features.onboarding`. Owns the questionnaire, validation, state transitions, and the (stubbed)
screening decision. Keep it in its own feature package so the seam stays clean — extracting a feature
package later is easy; extracting data tangled into other features is not.

The SPA's existing `/register` flow becomes the KYC step rather than a generic profile step. **The
second form already exists** — this phase adds the *first* one (in the IdP) and re-points the second.

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

> The **`playground`** realm sets no token lifespans at all, so it runs on Keycloak defaults (5 min
> access token — confirmed, the SPA's token comes back `expires_in: 300` — 30 min SSO idle, 10 h SSO
> max). Fine values; set them explicitly anyway. Also enable refresh token rotation
> (`revokeRefreshToken: true`) — bank-shaped and a one-liner.
>
> Note this is realm-specific: `playground-services` **does** set `accessTokenLifespan: 3600`,
> deliberately longer for machine tokens that have no user and no refresh. Don't read one realm's
> settings as the stack's.
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
IdP required action        email → master: users row (UUID + national_id + email)
Keycloak enricher          fetch status → session note → claim
                           shadow user, ROLE_PROSPECT
SPA                        status PENDING → onboarding UI
resource-backend           questionnaire + (stubbed) screening → ACTIVE
                           → Keycloak Admin API: ROLE_PROSPECT → ROLE_CUSTOMER
SPA                        reauthorize() → full access
```

---

## 5. Definition of done

- [ ] Unknown national ID → registration → master mints the row → login completes.
- [ ] `email_verified: false` on a collected address — checked on **Keycloak's shadow user**, not
      merely in the IdP's token. `trustEmail: true` will otherwise mark it verified behind your back
      (§3.2). No "Update Account Information" page either way.
- [ ] New user lands with `ROLE_PROSPECT`, reaches onboarding endpoints, is denied everything else.
- [ ] Completing onboarding + `reauthorize()` yields `ROLE_CUSTOMER` **without a logout**.
- [ ] `prompt=none` re-authorization completes silently with a live SSO session.
- [ ] Enricher failure fails the login, cleanly and legibly.
- [ ] Add-password and add-Smart-ID both work from an authenticated session, share the assurance
      gate, and neither has an anonymous entry point.
- [ ] Binding a national ID already held by another person is rejected by the database constraint,
      not by a prior read.
- [ ] `customer:write` reaches idp-server and **nothing else new** — confirm the grant did not widen
      to `credentials:write` or a blanket write scope along the way.

---

## 6. Open questions

### Blocking

| # | Question | Needed by |
|---|---|---|
| 1 | Does Keycloak create a usable shadow user when email arrives from the **enricher** rather than the ID token? The realm has `updateProfileFirstLoginMode: "off"` and `trustEmail: true`, so it will not prompt — but whether it tolerates a null email and lets the enricher fill it is exactly the kind of thing that behaves unexpectedly. **Test this path early.** Fallback: idp-server fetches email from the master purely to emit it. | 3.2 |

### Design

| # | Question | Notes |
|---|---|---|
| 2 | Does resource-backend call the Keycloak Admin API directly for the role flip, or is there a narrower seam? | Admin API is a broad privileged surface. Scope the service account as tightly as Keycloak allows — ideally "may add/remove exactly these two roles." |
| 3 | Does the **provider-level** `syncMode` follow the mappers to `FORCE`? | Half-done already: Phase 1 set `syncMode: FORCE` on the `import-acr` / `import-amr` mappers, because `acr` and `amr` describe *this* authentication rather than the user record. The provider itself is still `IMPORT`, so name/email remain a first-login snapshot. Under the banking lens that stops being a preference: `IMPORT` means the shadow user is a permanently stale copy, so a suspension never propagates. It is what makes revocation-on-next-login real. Strong lean: yes, finish it here. |
| 4 | Does back-channel logout get pulled in? | Note: Spring Security 7's `OidcBackChannelLogoutHandler` is **client-side** — it lets a Spring app *receive* logout tokens. Spring Authorization Server as an OP does **not** send them. So the BACKLOG item is solved by Keycloak calling idp-server's `end_session_endpoint` (RP-initiated), not by back-channel logout. |
| 5 | What goes in `username` for a Smart-ID-only user? | Much lower stakes now that it is a display handle. Derive from the certificate name, or leave null and display the person's name. |
| 6 | Does the required action need its own `acr` gate? | Registration completes at `acr: strong` by construction — you cannot reach it without Smart-ID. But the required-action form is a plain browser POST, so confirm the session backing it is the Smart-ID one and not something a password login could resume into. |
| 7 | Is the representation/delegation feature in scope after this? | It is the stress test that justified the session-note design. Note that **Keycloak's token-exchange delegation is experimental** (needs `token-exchange-delegation` + `parameterized-scopes`), so the RFC 8693 `act`/`may_act` path is not available on 26.4.x. Session notes + re-authorize is the mature route. |

### Resolved

- ✅ Open population — register unknown Smart-ID holders. Withdraws an earlier closed-population
  conclusion; see §1.
- ✅ Registration requires Smart-ID; password is enrolment-only, from an authenticated session.
- ✅ Status reaches the token via a Keycloak enricher authenticator, refreshed by `prompt=none`
  re-authorization rather than logout.
- ✅ Enrichment failure fails the login.
- ✅ Registration creates a `users` row only — no credential row, because Smart-ID is an inherent
  method. See Phase 1 §2.
