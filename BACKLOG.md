# Backlog

Things noted but deliberately deferred. Each item explains why it's not done so a future pass can decide whether the deferral still makes sense.

## Infrastructure

- [ ] **Dockerize `idp-server` and `user-data-master-app`.** Today both run locally via `./gradlew bootRun`; the rest of the stack is containerized. Adding Dockerfiles is mechanical — the design wrinkle is the OIDC issuer URL: it has to be reachable from both the user's browser and from inside Keycloak's container under the *same* hostname (the `iss` claim is checked exactly), which means picking a shared resolvable name or per-environment issuer overrides. The same hostname duality applies to the master's expected-issuer and JWKS URL, and to idp-server's `base-url` for reaching the master. Once dockerized, also bind-mount idp-server's `keys/` directory so the RSA signing key survives container rebuilds.

## Authentication features

- [ ] **Wire Smart-ID for real.** The login page already exposes the placeholder form (country selector + national ID input) with a disabled "Coming soon" submit. Implementing it means integrating with Smart-ID's relying-party REST API on the idp-server side — registering the relying-party UUID + name with the Smart-ID gateway, handling the async signature-request lifecycle, mapping the returned identity assertion to a local user record. Docs: <https://www.smart-id.com/e-service-providers/integration-process/>.

- [ ] **Propagate sign-out to the upstream IdP session.** Today the SPA's "Sign out" tears down the Keycloak session, but Spring IdP's HTTP session lingers — a subsequent sign-in may skip the credential prompt because the IdP "remembers" the user. OIDC back-channel logout (or front-channel) would fix it; needs an endpoint on the IdP side and a `backchannelLogoutUrl` registered on Keycloak's identity-provider config.

- [ ] **Reconsider `syncMode` on the brokered IdP.** Currently `IMPORT` — Keycloak syncs claims from upstream only on first login. If a user's email or name changes on the IdP side, Keycloak's shadow user keeps the old values forever. `FORCE` would re-sync on every login. This is a behavior choice, not a defect — flip it once you care about post-first-login drift.

## Testing

- [ ] **Add tests.** All three modules (`idp-server`, `resource-backend`, `client-frontend`) ship with zero tests today. Highest-value targets when this pass happens: the resource-backend's `UserService` (`findCurrent` / `register` / `syncFromJwt` cover the JIT-provisioning flow that's easy to break), the idp-server's `OidcClaimsCustomizer` (since broken ID-token claims silently fall back to Keycloak's "Update Account Information" page), and a frontend integration test that walks the bootstrap state machine through `loading → needs-registration → ready`.
