package ee.authplayground.idpserver.appcore.security;

import ee.authplayground.idpserver.features.users.client.UserMasterUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The service-token interceptor, and specifically its recovery from a rejected
 * token.
 *
 * <h2>Why this is worth a test rather than a comment</h2>
 * The failure it guards against is invisible until it is catastrophic. Keycloak
 * writes its realm signing keys into {@code keycloak-postgres}, so the reset
 * documented in AGENTS.md — {@code docker compose down -v} — brings Keycloak
 * back with new keys. Every token minted beforehand is then signed by a key that
 * no longer exists.
 * <p>
 * The token cache cannot notice this on its own: it re-authorizes on expiry, and
 * expiry is the only signal it has. So without the retry, idp-server presents a
 * dead token for up to an hour and <b>nobody can log in by any method</b> —
 * surfacing, when it happened for real, as "there is no account for it here".
 */
class UserMasterClientConfigTest {

    private static final String REGISTRATION_ID = "user-data-master";

    private OAuth2AuthorizedClientManager manager;
    private OAuth2AuthorizedClientService clientService;
    private UserMasterClientConfig config;

    /** Tokens handed out in order, so the retry can be seen using a different one. */
    private final List<String> issuedTokens = new ArrayList<>();

    @BeforeEach
    void setUp() {
        manager = mock(OAuth2AuthorizedClientManager.class);
        clientService = mock(OAuth2AuthorizedClientService.class);
        config = new UserMasterClientConfig();

        when(manager.authorize(any())).thenAnswer(invocation ->
                authorizedClient(issuedTokens.isEmpty() ? "stale" : issuedTokens.removeFirst()));
    }

    /**
     * The whole point: a 401 discards the cached token and the request goes out
     * again with a fresh one.
     */
    @Test
    void discardsTheTokenAndRetriesOnceAfterA401() throws IOException {
        issuedTokens.addAll(List.of("stale", "fresh"));
        var attempts = new AtomicInteger();
        var tokensPresented = new ArrayList<String>();

        ClientHttpResponse response = intercept((request, body) -> {
            tokensPresented.add(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            return attempts.incrementAndGet() == 1
                    ? new MockClientHttpResponse(new byte[0], HttpStatus.UNAUTHORIZED)
                    : new MockClientHttpResponse("{}".getBytes(), HttpStatus.OK);
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attempts).hasValue(2);
        assertThat(tokensPresented).containsExactly("Bearer stale", "Bearer fresh");

        // Without this eviction the manager would hand back the same cached
        // token and the retry would be a verbatim repeat of the first attempt.
        verify(clientService).removeAuthorizedClient(REGISTRATION_ID, REGISTRATION_ID);
    }

    /**
     * A second 401, with a token minted seconds earlier, is not staleness — it is
     * a wrong scope, a wrong audience, or a misconfigured master. Retrying those
     * is only a slower way to fail, so the failure is allowed through.
     */
    @Test
    void doesNotRetryTwice() throws IOException {
        issuedTokens.addAll(List.of("stale", "fresh"));
        var attempts = new AtomicInteger();

        ClientHttpResponse response = intercept((request, body) -> {
            attempts.incrementAndGet();
            return new MockClientHttpResponse(new byte[0], HttpStatus.UNAUTHORIZED);
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(attempts).hasValue(2);
    }

    /**
     * Anything that is not a 401 is passed straight back. A 403 in particular
     * means the token was accepted and the <i>scopes</i> were refused — minting a
     * new token with the same scopes would change nothing.
     */
    @Test
    void doesNotRetryOnOtherErrors() throws IOException {
        issuedTokens.add("stale");
        var attempts = new AtomicInteger();

        ClientHttpResponse response = intercept((request, body) -> {
            attempts.incrementAndGet();
            return new MockClientHttpResponse(new byte[0], HttpStatus.FORBIDDEN);
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(attempts).hasValue(1);
        verify(clientService, never()).removeAuthorizedClient(any(), any());
    }

    @Test
    void passesSuccessfulResponsesThroughUntouched() throws IOException {
        issuedTokens.add("good");
        var attempts = new AtomicInteger();

        ClientHttpResponse response = intercept((request, body) -> {
            attempts.incrementAndGet();
            return new MockClientHttpResponse("{}".getBytes(), HttpStatus.OK);
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attempts).hasValue(1);
        verify(manager, times(1)).authorize(any());
    }

    /**
     * No token at all is a different failure from a rejected one: Keycloak is
     * unreachable or refusing us, and there is nothing to retry with.
     */
    @Test
    void failsLoudlyWhenNoTokenCanBeObtained() {
        when(manager.authorize(any())).thenReturn(null);

        assertThatThrownBy(() -> intercept((request, body) ->
                new MockClientHttpResponse(new byte[0], HttpStatus.OK)))
                .isInstanceOf(UserMasterUnavailableException.class)
                .hasMessageContaining("Could not obtain a service token");
    }

    private ClientHttpResponse intercept(ClientHttpRequestExecution execution) throws IOException {
        ClientHttpRequestInterceptor interceptor = config.serviceTokenInterceptor(manager, clientService);
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://master/internal/users"));
        return interceptor.intercept(request, new byte[0], execution);
    }

    private OAuth2AuthorizedClient authorizedClient(String tokenValue) {
        ClientRegistration registration = ClientRegistration
                .withRegistrationId(REGISTRATION_ID)
                .clientId("idp-server")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://keycloak/token")
                .build();

        OAuth2AccessToken token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                tokenValue,
                Instant.now(),
                Instant.now().plusSeconds(3600));

        return new OAuth2AuthorizedClient(registration, REGISTRATION_ID, token);
    }
}
