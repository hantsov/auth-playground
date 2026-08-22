package ee.authplayground.userdatamaster.appcore.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The master is a pure OAuth2 resource server: it accepts machine tokens minted
 * by Keycloak's {@code playground-services} realm and nothing else.
 * <p>
 * No CORS configuration, no form login, no session. Nothing in a browser talks
 * to this service — every caller is another backend holding a
 * {@code client_credentials} token. If a request arrives from a browser origin,
 * something is wrong with the topology rather than with the CORS policy.
 * <p>
 * <b>Three things are validated, and the third is the one people skip:</b>
 * signature (against the realm's JWKS), issuer (this realm, not the customer
 * realm), and <b>audience</b> (see {@link AudienceValidator}). Authorization on
 * top of that is per-scope, declared at each controller method.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Value("${playground.master.expected-issuer}")
    private String expectedIssuer;

    @Value("${playground.master.expected-audience}")
    private String expectedAudience;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // No browser client, therefore no cookies, therefore no CSRF surface.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Everything under /internal requires a valid token. *Which*
                        // token is decided per-endpoint by @PreAuthorize on scopes —
                        // authentication here, authorization there.
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder())))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        return http.build();
    }

    /**
     * Built by hand rather than via {@code issuer-uri}, for two reasons.
     * <p>
     * First, startup coupling: {@code issuer-uri} performs OIDC discovery when
     * this bean is created, so the master would refuse to start whenever
     * Keycloak is down. We need exactly one endpoint — name it directly.
     * <p>
     * Second, and the substantive one: the audience validator has to be added
     * explicitly. Spring's defaults give you signature and expiry; issuer only
     * if you ask; audience never.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                // Timestamps (exp/nbf) plus the issuer claim, checked as a literal
                // string. `playground-services` and `playground` are different
                // authorization servers in every way that matters — separate signing
                // keys, separate issuer, separate client registry — so a customer
                // token is not merely under-privileged here, it is from the wrong
                // authority entirely.
                JwtValidators.createDefaultWithIssuer(expectedIssuer),
                new AudienceValidator(expectedAudience)
        );
        decoder.setJwtValidator(validators);

        return decoder;
    }

    /**
     * Used only by the seed runner, to hash seed passwords at the encoder's
     * current settings rather than freezing a cost factor into a migration.
     * <p>
     * Note what this service does <b>not</b> do with it: verify anything.
     * The master stores credential material and hands it to the one caller
     * entitled to see it; idp-server performs the comparison. Keeping the
     * verification out of here is what keeps authentication policy — lockout,
     * attempt counting, {@code acr} determination — in one place instead of
     * smeared across two services.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
