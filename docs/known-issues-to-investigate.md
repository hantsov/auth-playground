# Known issues to investigate

> **What this is:** a register of security or correctness issues we have identified, understood, and
> **deliberately not fixed yet**. Each entry is written to be picked up cold — enough to act on
> without rediscovering the problem.
>
> **What this is not:** a backlog of ideas, or a list of things that merely look suspicious. An issue
> earns a place here only once someone has established that it is real. "Might be a problem" belongs
> in the phase docs' open questions; this file is for things we know are problems and have chosen to
> carry.
>
> **Deferring is a decision, not an omission.** Every entry records *why* it was deferred, so the
> next person can re-evaluate the reasoning rather than just inherit the conclusion.

## Entry format

Each issue gets a numbered section with a metadata table, what it is, how it is exploited or
triggered, why it was deferred, and what fixing it would take. Keep entries in discovery order and
do not renumber — other documents and commit messages will reference them by number.

---

## 1. Smart-ID login CSRF: the Smart-ID session is not bound to the browser that started it

| | |
|---|---|
| **Status** | Open — deferred deliberately |
| **Severity** | Medium |
| **Category** | `login-csrf` / `session-management` |
| **Found** | 2026-08-23, security review of [Phase 2](refactor-phase-2_smart-id-notification-authn.md) |
| **Introduced by** | Phase 2, the Smart-ID notification flow. Does not affect the password path. |
| **Affects** | idp-server only. No data is at risk in the user data master. |

### What it is

`SmartIdSessionRegistry` is a process-wide map keyed only on SK's session ID. Nothing records **which
browser** started a given Smart-ID session — `SmartIdSession` has no owner field, and
`SmartIdAuthenticationService.start(...)` never touches the `HttpServletRequest`.

`GET /login/smart-id/status` looks that ID up globally with no ownership check, and when SK reports
completion, `establishSession(...)` writes the authenticated `SecurityContext` into **the calling
request's** HTTP session. The context repository has `allowSessionCreation=true`, so it will mint a
session and set `JSESSIONID` even for a caller that arrived with no cookie at all.

So the actual invariant is:

> **Whoever presents a Smart-ID session ID at the moment SK reports completion receives the login,
> in their own browser.**

Two details make it reachable rather than theoretical:

- **The trigger is a GET with a side effect.** Spring Security's `CsrfFilter` never guards safe
  methods, so the CSRF exemption on `/login/smart-id/**` is *not* the root cause — re-enabling CSRF
  there would not fix this.
- **No `SameSite` is configured anywhere.** `JSESSIONID` ships bare, browsers apply Lax-by-default,
  and Lax permits top-level cross-site GET navigation — exactly the vector needed.

### ⚠️ Three code comments currently assert the opposite

This is the part most likely to mislead someone reading the code before reading this file:

| Location | Claims |
|---|---|
| `SmartIdLoginController` class comment | "knowing it lets you ask how a login is going, not complete one" |
| `SmartIdSessionRegistry` class comment | "The key is not a secret" |
| `DefaultSecurityConfig`, CSRF exemption comment | "`/status` only reports on a session ID the caller already holds" |

All three would be true if `/status` were a read. It is the request that mints the login. **Treat
those comments as known-wrong until this entry is closed.**

### Exploit path

The attacker needs no victim credentials and no access to the victim's phone. They use their *own*
Smart-ID identity throughout.

1. `POST /login/smart-id/start` with the attacker's own country + national ID. No cookie, no session,
   no CSRF token, no authentication required. Returns `sessionId = S`.
2. Attacker approves the push on their own phone with their own PIN. The verification code shown on
   screen is no obstacle — it defends the *inverse* attack (pushing to a victim's phone) and is
   irrelevant when the attacker is the Smart-ID subject.
3. Nothing polls `S`, so the completed session sits in the registry. Window is
   `session-max-duration`, currently `2m`.
4. Attacker induces one top-level navigation to `/login/smart-id/status?sessionId=S` — a link,
   `window.open`, or a 302.
5. The victim's browser is now holding an idp-server session authenticated **as the attacker**. If
   the victim was already logged in, their context is silently overwritten.
6. The victim later reaches `/oauth2/authorize` through the SPA. The session is already
   authenticated and `requireAuthorizationConsent(false)` is set for `kc-broker-client`, so a code is
   issued **silently**, for the attacker's `sub`, carrying `acr: strong` / `amr: ["smartid"]`.

The impact is identity **substitution**, not account takeover: the victim transacts in the
attacker's account, and whatever they enter is readable by the attacker afterwards. The high
assurance level makes it worse rather than better — a relying party is *more* likely to waive further
checks on `acr: strong`.

### Why it was deferred

The playground is not deployed anywhere, has no real users and no real data, and runs against SK's
DEMO environment. The exploit needs live attacker coordination inside a two-minute window plus a
victim click, against a service running on someone's laptop.

The counter-argument is worth writing down, because it is what should eventually force this closed:
**this project's product is correct authentication.** A login flow that can be driven by a
third party is exactly the kind of thing a reader might copy, and three comments currently teach the
wrong lesson about it. That makes this more embarrassing here than the severity rating suggests.

### What fixing it takes

1. **Bind the session to its initiator.** Add `String initiatingSessionId` to `SmartIdSession`,
   populated in `SmartIdAuthenticationService.start(...)` from `request.getSession(true).getId()`,
   with the request passed through from the controller. Keeping the binding in the record rather than
   on `HttpSession` preserves the registry design that Phase 4 needs.
2. **Enforce ownership in `/status`**, immediately after `sessionRegistry.find(...)` and **before**
   `sessionStrategy.onAuthentication(...)` rotates the session ID:
   ```java
   HttpSession http = request.getSession(false);
   if (http == null || !http.getId().equals(session.initiatingSessionId())) {
       throw new SmartIdSessionNotFoundException("Session does not belong to this browser");
   }
   ```
3. **Move completion off GET.** Make it `POST /login/smart-id/status` (or a separate `/complete`) and
   narrow the CSRF exemption to `/login/smart-id/start` only. Once `/start` has created a session,
   the login page can carry a token — which retires the "cannot carry a CSRF token before there is a
   session" premise the current exemption rests on.
4. **Defence in depth:** `server.servlet.session.cookie.same-site: strict` in `application.yml`. Does
   not fix the flaw alone; closes the cheapest delivery vector.
5. **Correct the three comments** listed above.

Steps 1 and 2 are the fix. Steps 3–5 are what stop it recurring.

### Related, and genuinely out of scope

The same missing binding means an attacker who enters a **victim's** national ID at `/start` and
persuades them to approve the push can poll `/status` from their own browser and be authenticated as
the victim.

That one is **inherent to Smart-ID's notification flow**, not a defect in this code — it is why SK
publishes the verification code at all, and why the login page displays it at 3rem with an explicit
instruction to compare. Binding the session does not remove it. It is recorded here so nobody
mistakes it for part of the fix above.

### Checked and clean during the same review

Noted so a future reviewer does not re-tread it: the ACSP_V2 signature verification and PKIX chain
validation, identity being taken from the certificate rather than the form fields, the `rpChallenge`
anti-replay construction, redirect handling, the ETSI identifier pattern as an injection surface,
and `smart-id.js` for XSS. All clean.

One non-security discrepancy was found and also left: `SmartIdResponseValidator`'s Javadoc claims the
RSASSA-PSS parameters are cross-checked against what we requested, and no such check exists. It is
not exploitable — no choice of PSS parameters lets an attacker produce a signature they could not
otherwise produce, and every degenerate parameter fails closed — but the comment and the code should
be made to agree.
