package ee.authplayground.idpserver.appcore.security;

import ee.authplayground.idpserver.features.smartid.service.SmartIdAuthenticationToken;
import ee.authplayground.idpserver.features.users.service.UserDataDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.List;

/**
 * Populates the ID token at issuance time.
 * <p>
 * Spring Authorization Server's defaults give you a minimal ID token
 * ({@code sub}, {@code iss}, {@code aud}, {@code exp}, {@code iat}) and leave
 * claim population to the app. Without this, Keycloak — acting as the relying
 * party — sees an ID token with no {@code email}, {@code given_name} or
 * {@code family_name}, and the brokered login falls through to Keycloak's
 * "Update Account Information" page because those fields are required for a
 * Keycloak user record.
 *
 * <h2>Where the values come from</h2>
 * The authenticated principal, <b>not</b> a fresh call to the user data master.
 * The attributes were fetched alongside the credential during the form-login
 * POST and carried here on {@link UserDataDetails}. That is what keeps the
 * whole login to a single master call: this runs during Keycloak's back-channel
 * token request, a different HTTP request from the one the user submitted, so
 * "just fetch it again" would mean a second round trip on the hot path.
 *
 * <h2>{@code sub} is not set here</h2>
 * Spring Authorization Server derives it from {@code Authentication#getName()},
 * and {@link UserDataDetails} makes that the master's user ID. That is the
 * fix for the original bug, and it lives in the principal rather than in a
 * claim override on purpose — a {@code sub} you have to remember to overwrite
 * is a {@code sub} that will eventually be wrong somewhere.
 */
@Configuration
@Slf4j
public class OidcClaimsCustomizer {

    /**
     * Authentication Context Class Reference — how much this authentication is
     * worth. Phase 1 has exactly one method, so exactly one value.
     * <p>
     * Named rather than a Level-of-Assurance integer, because in Phase 2 the
     * whole point is that the same {@code sub} arrives at two different
     * assurance levels: password login stays {@code weak}, and Smart-ID — which
     * carries a state-issued identity in a certificate — becomes {@code strong}.
     * A decoded token should say that out loud.
     */
    private static final String ACR_WEAK = "weak";

    /**
     * What a Smart-ID authentication is worth.
     *
     * <h2>Why these two are not the same</h2>
     * A password proves someone knows a secret. A Smart-ID authentication
     * produces a signature from a private key held on a specific phone, over a
     * challenge we generated seconds earlier, with a qualified certificate the
     * state stands behind. Those are different claims about the world, and a
     * relying party that cannot tell them apart cannot make a decision that
     * depends on the difference — which is the entire reason for emitting
     * {@code acr} at all.
     * <p>
     * <b>The same person reaches both levels at the same {@code sub}.</b> That
     * is the payoff of keeping identity on the person record and out of the
     * credential: {@code acr} describes this login, not this human.
     */
    private static final String ACR_STRONG = "strong";

    /**
     * Authentication Methods References — <i>how</i> they authenticated, as
     * opposed to how much it counts for.
     * <p>
     * An array because OIDC Core defines it as one: a single authentication can
     * legitimately involve several methods.
     */
    private static final List<String> AMR_PASSWORD = List.of("pwd");

    /**
     * Not an RFC 8176 registered value — that document has no Smart-ID entry.
     * Chosen for legibility over a strained fit with something registered:
     * {@code sc} (smart card) and {@code swk} (software key) each describe part
     * of what Smart-ID is and would mislead about the rest.
     */
    private static final List<String> AMR_SMART_ID = List.of("smartid");

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> idTokenCustomizer() {
        return context -> {
            if (!OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
                return;
            }

            Authentication principal = context.getPrincipal();
            if (!(principal.getPrincipal() instanceof UserDataDetails user)) {
                // Every authentication in this server goes through
                // UserDataDetailsService, so this should be unreachable. Log it
                // rather than emitting a half-populated token that would fail
                // downstream in a much less obvious place.
                log.warn("Principal is not UserDataDetails ({}) — emitting no profile claims",
                        principal.getPrincipal().getClass().getName());
                return;
            }

            var scopes = context.getAuthorizedScopes();
            var claims = context.getClaims();

            // acr/amr describe THIS authentication event, so they are not gated
            // on a scope — they are facts about the token itself rather than
            // profile data the relying party asked for.
            //
            // Derived from how the user actually authenticated, not hardcoded.
            // Note there is exactly ONE writer of `acr` in the whole chain: this
            // line. Keycloak imports the value with syncMode FORCE and re-emits
            // it, and the `acr` client scope was deliberately removed from
            // react-client so Keycloak's built-in oidc-acr-mapper does not also
            // write the claim. Two mappers writing one claim is not something to
            // leave to chance.
            boolean smartId = principal instanceof SmartIdAuthenticationToken;
            claims.claim("acr", smartId ? ACR_STRONG : ACR_WEAK);
            claims.claim("amr", smartId ? AMR_SMART_ID : AMR_PASSWORD);

            if (scopes.contains("profile")) {
                claims.claim("preferred_username", user.getPreferredUsername());
                if (user.getGivenName() != null) {
                    claims.claim("given_name", user.getGivenName());
                }
                if (user.getFamilyName() != null) {
                    claims.claim("family_name", user.getFamilyName());
                }
                if (user.getFullName() != null) {
                    claims.claim("name", user.getFullName());
                }
            }

            if (scopes.contains("email")) {
                claims.claim("email", user.getEmail());
                // The real value, from the master — not the hardcoded `true` this
                // used to emit. That literal was harmless only while every address
                // was a seeded fixture we controlled. From Phase 3 an address
                // arrives from a form with no mailbox verification behind it, and
                // asserting `true` would be claiming to have performed a check
                // nobody performed.
                //
                // Note this is only half the fix: the realm sets `trustEmail: true`
                // on the playground-idp provider, which makes Keycloak skip its own
                // verification for addresses from this IdP regardless of what we
                // say here. Closing that loop belongs with Phase 3's first
                // form-collected address.
                claims.claim("email_verified", user.isEmailVerified());
            }
        };
    }
}
