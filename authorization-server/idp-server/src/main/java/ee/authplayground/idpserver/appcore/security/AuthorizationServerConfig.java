package ee.authplayground.idpserver.appcore.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.UUID;

/**
 * Spring Authorization Server configuration.
 * <p>
 * Two security filter chains live in this app, and order matters:
 * <ol>
 *   <li>The <b>auth server chain</b> ({@link #authorizationServerSecurityFilterChain})
 *       owns OAuth2/OIDC endpoints: {@code /oauth2/authorize},
 *       {@code /oauth2/token}, {@code /oauth2/jwks}, the userinfo endpoint
 *       and OpenID Discovery. It runs first.</li>
 *   <li>The <b>default chain</b> ({@link DefaultSecurityConfig#defaultSecurityFilterChain})
 *       owns everything else — most importantly the Thymeleaf login page
 *       that the authorize endpoint redirects unauthenticated users to.</li>
 * </ol>
 * <p>
 * For Phase 1 the registered-client repository is in-memory and holds a
 * single client representing Keycloak. The client secret is hard-coded
 * here and also referenced from the realm JSON; this is deliberate
 * dev-only convenience — see the playground's weak-creds-on-purpose
 * convention. Phase 4 replaces the in-memory store with a JDBC-backed one.
 */
@Configuration
public class AuthorizationServerConfig {

    @Value("${playground.idp.issuer-url}")
    private String issuerUrl;

    @Value("${playground.idp.keycloak-broker.client-id}")
    private String keycloakClientId;

    @Value("${playground.idp.keycloak-broker.client-secret}")
    private String keycloakClientSecret;

    @Value("${playground.idp.keycloak-broker.redirect-uri}")
    private String keycloakRedirectUri;

    /**
     * Spring Auth Server's protocol endpoints — {@code /oauth2/authorize},
     * {@code /oauth2/token}, {@code /oauth2/jwks}, the OIDC discovery
     * document, the userinfo endpoint.
     * <p>
     * Spring Security 7 exposes this as a top-level DSL ({@code
     * .oauth2AuthorizationServer(...)}) rather than the older
     * {@code .with(configurer, ...)} dance. Inside the lambda we narrow
     * this filter chain's security matcher to the auth-server endpoints
     * (so the other chain handles everything else) and enable OIDC.
     * The chain runs first by {@code @Order(1)} so it wins for any URL
     * the auth-server matches.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .oauth2AuthorizationServer(authorizationServer -> {
                    http.securityMatcher(authorizationServer.getEndpointsMatcher());
                    authorizationServer.oidc(Customizer.withDefaults());
                })
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                // When an unauthenticated browser lands on /oauth2/authorize,
                // bounce it to our login page (Phase 1 uses Spring's auto-
                // generated form; Phase 3 swaps in the Thymeleaf method picker).
                // API callers (non-HTML Accept) get a 401 instead.
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                ));

        return http.build();
    }

    /**
     * The single registered client: Keycloak acting as a broker.
     * Uses {@code client_secret_basic} authentication, Authorization Code
     * grant, mandatory PKCE off (Keycloak is a confidential client and
     * doesn't need PKCE — though enabling it would be fine too).
     * <p>
     * The stored secret is prefixed {@code {noop}} — the Spring marker for
     * "compare as plaintext" — handled by the {@code DelegatingPasswordEncoder}
     * bean below. This is deliberately honest about what we ship: the secret
     * is dev-only plaintext, hardcoded in source. BCrypt-encoding it would
     * make it look more secure than it is. Real client secrets would arrive
     * via a secret store and be {@code {bcrypt}}-prefixed in persistence.
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository() {
        RegisteredClient keycloakBroker = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(keycloakClientId)
                .clientSecret("{noop}" + keycloakClientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(keycloakRedirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false) // Keycloak is a trusted backend client
                        // Spring Authorization Server 7 requires PKCE by default. Keycloak,
                        // as a confidential server-to-server client authenticating with
                        // client_secret_basic, doesn't send code_challenge — disable the
                        // requirement explicitly. (PKCE would be "defense in depth" here;
                        // it's mandatory for public clients like the SPA→Keycloak hop,
                        // optional for confidential ones like this Keycloak→IdP hop.)
                        .requireProofKey(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofHours(8))
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(keycloakBroker);
    }

    /**
     * Tells Spring Auth Server what to put in the {@code iss} claim of issued
     * tokens AND what to advertise in the discovery document. This MUST be
     * the URL browsers use to reach this server — Keycloak (whether in a
     * container or local) validates the issuer claim against this exact
     * string, so any host-name drift between issuer and discovery URL is
     * a guaranteed federation failure.
     */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer(issuerUrl)
                .build();
    }

    /**
     * JWT decoder used by the userinfo endpoint (and any other place that
     * needs to verify tokens we issued). Reuses the same JWK source the
     * token-issuing pipeline uses.
     */
    @Bean
    public JwtDecoder jwtDecoder(com.nimbusds.jose.jwk.source.JWKSource<com.nimbusds.jose.proc.SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * The {@link PasswordEncoder} bean used app-wide:
     * <ul>
     *   <li>by {@code DaoAuthenticationProvider} (under the form-login filter)
     *       to match user passwords against stored hashes</li>
     *   <li>by Spring Authorization Server at the token endpoint to match
     *       incoming client secrets against {@code RegisteredClient#getClientSecret()}</li>
     * </ul>
     * <p>
     * {@link PasswordEncoderFactories#createDelegatingPasswordEncoder()}
     * returns an encoder that reads a {@code {prefix}} marker on stored
     * values to pick the right algorithm: {@code {bcrypt}} for user
     * passwords (the default for new encodings via {@code encode(...)}),
     * {@code {noop}} for our dev-only client secret. New password storage
     * carries the algorithm prefix forever, so future algorithm upgrades
     * (Argon2, BCrypt cost bump) become a re-encode-on-next-login
     * migration rather than a global rehash.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
