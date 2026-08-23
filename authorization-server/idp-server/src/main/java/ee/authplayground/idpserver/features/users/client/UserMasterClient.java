package ee.authplayground.idpserver.features.users.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * This service's window onto the user data master. It is the <b>only</b> way
 * idp-server reaches user data — there is no local database behind it.
 * <p>
 * That is the user-federation pattern: an identity provider with no user store
 * of its own, reading credentials from an external directory on the fly. It is
 * what Keycloak's {@code UserStorageProvider} SPI does and what every Spring
 * Security + LDAP deployment does. The consequence is worth stating plainly:
 * <b>the master is tier-0.</b> Master down means nobody logs in, anywhere.
 * <p>
 * Every call carries a {@code client_credentials} token minted by Keycloak's
 * {@code playground-services} realm — see {@code UserMasterClientConfig} for
 * how it is obtained and why that is not the circular dependency it appears
 * to be.
 */
@Component
@Slf4j
public class UserMasterClient {

    private static final String CREDENTIAL_TYPE_PASSWORD = "PASSWORD";

    private final RestClient restClient;

    public UserMasterClient(RestClient userMasterRestClient) {
        this.restClient = userMasterRestClient;
    }

    /**
     * The <b>password</b> login-path lookup. One call, returning the credential
     * to verify and the person to describe.
     * <p>
     * Smart-ID does not come through here — it is an inherent method with no
     * credential row, and resolves against the master's national-ID endpoint
     * instead. Phase 2 adds that call alongside this one rather than
     * generalising it.
     * <p>
     * Both halves are needed at different moments — the hash during the form
     * POST, the person attributes during token issuance — and those are
     * different HTTP requests, separated by a browser redirect and Keycloak's
     * back-channel token call. Rather than call the master twice, the caller
     * carries the person attributes forward on the authenticated principal.
     *
     * @param loginName the submitted login name, matched against
     *                  {@code user_credentials.identifier} on the PASSWORD row.
     *                  Note this is <i>not</i> {@code users.username}: the
     *                  handle is display, the credential identifier is the key.
     * @return empty when no such credential exists — which the caller must treat
     *         exactly like a bad password, never like a distinguishable error
     */
    /**
     * The <b>Smart-ID</b> login-path lookup: which person is this national ID,
     * and do we know them?
     *
     * <h2>Why this is not a credential lookup</h2>
     * There is no {@code SMART_ID} row to fetch, and adding one would be a
     * mistake rather than a convenience. Smart-ID is an <i>inherent</i> method:
     * the state issued the identity, SK holds the private key, and
     * {@code users.national_id} + {@code users.nationality} are the entire
     * binding. {@code user_credentials} holds <i>issued</i> credentials only —
     * things we handed out and hold a secret for. A credential row here would
     * duplicate a derivable identifier and imply an enrolment step that does not
     * exist.
     * <p>
     * So the two login paths differ in their lookup and converge afterwards:
     * identifier &rarr; {@code users.id} &rarr; principal &rarr; the same
     * {@code sub}. That convergence is the point — one person, two methods, one
     * subject, at different assurance levels.
     *
     * <h2>Both halves, and why the second is not politeness</h2>
     * National ID numbers are unique within a country, not globally. Smart-ID's
     * own demo set has {@code PNOEE-40404040009} and {@code PNOLT-40404040009}
     * as different people sharing a number, so a lookup on the bare code would
     * cheerfully return the wrong human.
     *
     * @param nationalId  the code, without the country prefix
     * @param nationality ISO 3166-1 alpha-2, as stored on the person record
     * @return empty when nobody holds that national ID. <b>In this phase that is
     *         a rejection</b>, not a registration — Phase 3 replaces it with a
     *         required action. Note it is emphatically not "authentication
     *         failed": Smart-ID already proved who they are, we simply have no
     *         record of them.
     */
    public Optional<UserDataResponse> findByNationalId(String nationalId, String nationality) {
        return restClient.get()
                .uri(uri -> uri.path("/internal/users/by-national-id/{nationalId}")
                        .queryParam("nationality", nationality)
                        .build(nationalId))
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == 404) {
                        log.debug("No user at the master with national id {}-{}", nationality, nationalId);
                        return Optional.<UserDataResponse>empty();
                    }
                    if (!status.is2xxSuccessful()) {
                        // Same reasoning as the credential lookup: a 401/403 here is
                        // a service-token or scope problem, and it must not be
                        // mistaken for "we don't know this person".
                        throw new UserMasterUnavailableException(
                                "User data master returned " + status + " for a national-id lookup");
                    }
                    return Optional.ofNullable(response.bodyTo(UserDataResponse.class));
                });
    }

    public Optional<UserCredentialResponse> findPasswordCredential(String loginName) {
        return restClient.get()
                .uri(uri -> uri.path("/internal/credentials")
                        .queryParam("type", CREDENTIAL_TYPE_PASSWORD)
                        .queryParam("identifier", loginName)
                        .build())
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == 404) {
                        log.debug("No PASSWORD credential at the master for '{}'", loginName);
                        return Optional.<UserCredentialResponse>empty();
                    }
                    if (!status.is2xxSuccessful()) {
                        // Deliberately loud. A 401/403 here means the service token
                        // or its scopes are wrong, which looks identical to "user
                        // typed the wrong password" from the login page — and would
                        // otherwise be debugged for an hour at the wrong layer.
                        throw new UserMasterUnavailableException(
                                "User data master returned " + status + " for a credential lookup");
                    }
                    return Optional.ofNullable(response.bodyTo(UserCredentialResponse.class));
                });
    }
}
