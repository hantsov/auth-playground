package ee.authplayground.idpserver.appcore.security;

import ee.authplayground.idpserver.features.users.client.UserMasterUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

/**
 * How this service authenticates itself to the user data master.
 *
 * <h2>The circularity that isn't</h2>
 * idp-server gets its service token from Keycloak; Keycloak brokers user logins
 * to idp-server. Read quickly, that is a deadlock. It is not one:
 * {@code client_credentials} involves no user and no brokering, so it resolves
 * entirely inside Keycloak's own client registry, in a different realm, with no
 * reference to this server at all. The two interactions share a hostname and
 * nothing else.
 *
 * <h2>Why a separate realm</h2>
 * The token comes from {@code playground-services}, not {@code playground}.
 * Keycloak service accounts are backed by real user records
 * ({@code service-account-idp-server}), and putting those inside a realm whose
 * entire documented premise is "no local users, everyone is brokered" would
 * quietly falsify that premise. A Keycloak realm is a separate authorization
 * server in every way that matters — own signing keys, own issuer, own client
 * registry — so this is not a stand-in for an internal AS. It is one.
 *
 * <h2>Client authentication, and where we sit on the ladder</h2>
 * We use {@code client_secret_basic}, consistent with this playground's
 * weak-credentials-on-purpose convention. The ladder above it is worth knowing,
 * because for a bank it is normative rather than aspirational — <b>FAPI 2.0
 * permits only {@code private_key_jwt}, {@code tls_client_auth}, or
 * {@code self_signed_tls_client_auth}; a client secret in any form is not
 * allowed.</b>
 * <ol>
 *   <li>{@code client_secret_basic} — what we build</li>
 *   <li>{@code private_key_jwt} — no shared secret to leak</li>
 *   <li>mTLS client certificates</li>
 *   <li>Workload identity (SPIFFE/SPIRE, cloud IAM) — where the industry is going</li>
 * </ol>
 * Step 2 is genuinely reachable here: Keycloak supports it, and this server
 * already generates and persists an RSA key for its own signing.
 */
@Configuration
@Slf4j
public class UserMasterClientConfig {

    /**
     * Matches the registration id under
     * {@code spring.security.oauth2.client.registration.*} in application.yml.
     */
    private static final String REGISTRATION_ID = "user-data-master";

    @Value("${playground.idp.user-master.base-url}")
    private String userMasterBaseUrl;

    /**
     * {@code AuthorizedClientServiceOAuth2AuthorizedClientManager}, not the
     * {@code DefaultOAuth2AuthorizedClientManager}, because there is no end user
     * here — the principal is this service itself, and calls happen outside any
     * request the user owns.
     * <p>
     * <b>This is also the token cache.</b> The manager stores the authorized
     * client in the {@link OAuth2AuthorizedClientService} (in-memory by default)
     * and re-authorizes only when the token has expired, so a login does not
     * cost a round trip to Keycloak on top of the round trip to the master.
     * Worth confirming by watching Keycloak's token endpoint across two
     * consecutive logins rather than by trusting this comment.
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService
    ) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(provider);

        return manager;
    }

    /**
     * The {@link RestClient} every master call goes through, with the bearer
     * token attached by an interceptor.
     * <p>
     * The token is fetched explicitly rather than by an opaque filter so the
     * {@code client_credentials} exchange is visible to anyone reading this —
     * which is rather the point of the playground.
     */
    @Bean
    public RestClient userMasterRestClient(
            OAuth2AuthorizedClientManager authorizedClientManager,
            OAuth2AuthorizedClientService authorizedClientService
    ) {
        return RestClient.builder()
                .baseUrl(userMasterBaseUrl)
                .requestInterceptor(serviceTokenInterceptor(authorizedClientManager, authorizedClientService))
                .build();
    }

    /**
     * Attaches the service token, and recovers when the master rejects it.
     *
     * <h2>Why a retry is needed at all</h2>
     * The manager caches the token and re-authorizes on <b>expiry</b> — that is
     * the only signal it has. It never learns that the resource server rejected
     * the token, because it is not in the response path.
     * <p>
     * That gap has teeth here. Keycloak generates its realm signing keys into
     * {@code keycloak-postgres}, so the documented reset in AGENTS.md —
     * {@code docker compose down -v} — brings Keycloak back with <b>new keys</b>.
     * Every token minted before that moment is now signed by a key that no
     * longer exists, and the master correctly rejects it with 401. Without the
     * retry below, idp-server keeps presenting that dead token until it expires:
     * <b>up to an hour in which nobody can log in by any method</b>, reported as
     * whatever the calling code makes of a failed lookup. It looks like a data
     * problem, and it is a key-rotation problem.
     *
     * <h2>Once, and only once</h2>
     * A second 401 after a freshly minted token is not a stale-token problem —
     * it is a wrong scope, a wrong audience, or a misconfigured master, and
     * retrying those is just a slower way to fail. So the second failure is
     * allowed to propagate.
     */
    // Package-private so the 401-retry can be driven directly in a test.
    ClientHttpRequestInterceptor serviceTokenInterceptor(
            OAuth2AuthorizedClientManager manager,
            OAuth2AuthorizedClientService clientService
    ) {
        return (request, body, execution) -> {
            request.getHeaders().setBearerAuth(serviceToken(manager));
            ClientHttpResponse response = execution.execute(request, body);

            if (response.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
                return response;
            }

            log.warn("User data master rejected our service token (401). Discarding it and retrying once — "
                    + "this is what a Keycloak key rotation looks like from here.");

            // The rejected response is abandoned, so its body must be released
            // before a second request goes out on the same client.
            response.close();

            // Evicting is the whole point: without it, `authorize` returns the
            // same cached token and the retry is identical to the first attempt.
            // The principal name is the cache key the manager stored against.
            clientService.removeAuthorizedClient(REGISTRATION_ID, REGISTRATION_ID);

            request.getHeaders().setBearerAuth(serviceToken(manager));
            // Safe to re-execute: this chain holds a single interceptor, and each
            // call builds a fresh underlying request from the current headers.
            return execution.execute(request, body);
        };
    }

    private String serviceToken(OAuth2AuthorizedClientManager manager) {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(REGISTRATION_ID)
                // No user is involved, so the "principal" is just this service's
                // own name. It is the cache key the manager stores against.
                .principal(REGISTRATION_ID)
                .build();

        OAuth2AuthorizedClient authorizedClient = manager.authorize(authorizeRequest);
        if (authorizedClient == null) {
            throw new UserMasterUnavailableException(
                    "Could not obtain a service token for '" + REGISTRATION_ID + "' from Keycloak");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
