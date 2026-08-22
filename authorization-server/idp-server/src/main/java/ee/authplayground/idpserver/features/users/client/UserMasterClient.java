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
     * The login-path lookup. One call, returning the credential to verify and
     * the person to describe.
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
