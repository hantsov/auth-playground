package ee.authplayground.idpserver.appcore.security;

import ee.authplayground.idpserver.features.users.client.UserMasterUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
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
    public RestClient userMasterRestClient(OAuth2AuthorizedClientManager authorizedClientManager) {
        return RestClient.builder()
                .baseUrl(userMasterBaseUrl)
                .requestInterceptor(serviceTokenInterceptor(authorizedClientManager))
                .build();
    }

    private ClientHttpRequestInterceptor serviceTokenInterceptor(OAuth2AuthorizedClientManager manager) {
        return (request, body, execution) -> {
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

            request.getHeaders().setBearerAuth(authorizedClient.getAccessToken().getTokenValue());
            return execution.execute(request, body);
        };
    }
}
