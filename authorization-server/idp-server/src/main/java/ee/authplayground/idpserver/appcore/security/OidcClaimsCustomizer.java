package ee.authplayground.idpserver.appcore.security;

import ee.authplayground.idpserver.features.users.repository.UserDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Populates OIDC profile/email claims on the ID token at issuance time.
 * <p>
 * Spring Authorization Server's defaults give you a minimal ID token
 * ({@code sub}, {@code iss}, {@code aud}, {@code exp}, {@code iat}) and
 * leave profile claim population to the app. Without this customizer,
 * Keycloak (acting as the relying party) sees an ID token with no
 * {@code email}, {@code given_name}, or {@code family_name} — and the
 * brokered-login flow falls through to "Update Account Information"
 * because those fields are required for a Keycloak user record.
 * <p>
 * The customizer fires only for ID tokens (not access tokens) and only
 * when the corresponding scope was granted: {@code email} unlocks the
 * email claims, {@code profile} unlocks name claims. The
 * {@code sub} claim Spring Auth Server populates is already the
 * user's stable UUID — we don't override it.
 */
@Configuration
@RequiredArgsConstructor
public class OidcClaimsCustomizer {

    private final UserDataRepository userDataRepository;

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> idTokenCustomizer() {
        return context -> {
            if (!OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
                return;
            }

            String username = context.getPrincipal().getName();
            userDataRepository.findByUsername(username).ifPresent(user -> {
                var scopes = context.getAuthorizedScopes();
                var claims = context.getClaims();

                if (scopes.contains("profile")) {
                    claims.claim("preferred_username", user.getUsername());
                    if (user.getGivenName() != null) {
                        claims.claim("given_name", user.getGivenName());
                    }
                    if (user.getFamilyName() != null) {
                        claims.claim("family_name", user.getFamilyName());
                    }
                    if (user.getGivenName() != null || user.getFamilyName() != null) {
                        String fullName = java.util.stream.Stream.of(user.getGivenName(), user.getFamilyName())
                                .filter(java.util.Objects::nonNull)
                                .reduce((a, b) -> a + " " + b)
                                .orElse(null);
                        if (fullName != null) {
                            claims.claim("name", fullName);
                        }
                    }
                }

                if (scopes.contains("email")) {
                    claims.claim("email", user.getEmail());
                    claims.claim("email_verified", true);
                }
            });
        };
    }
}
