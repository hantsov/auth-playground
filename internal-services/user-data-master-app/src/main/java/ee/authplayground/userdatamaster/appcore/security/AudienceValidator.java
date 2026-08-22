package ee.authplayground.userdatamaster.appcore.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Rejects tokens that were not minted for us.
 * <p>
 * <b>Spring does not do this for you.</b> {@code JwtDecoder} validates the
 * signature, the expiry and — if you configured an issuer — the {@code iss}
 * claim. It does <i>not</i> check {@code aud} unless you add a validator, and
 * this is the most commonly skipped step in machine-to-machine setups.
 * <p>
 * What it buys: audience validation is what stops a token minted for one
 * service being replayed against another. Without it, any client in the
 * services realm holding a valid token could present it here, and the only
 * thing standing between them and a password hash would be the scope check.
 * Scopes are the second line; this is the first.
 * <p>
 * The token's audience is set by an explicit audience mapper on the client
 * scopes in {@code playground-services-realm.json}. Keycloak's default
 * {@code aud} is <i>not</i> what you want — typically the calling client itself,
 * or {@code account} — which is precisely why the mapper has to be there and
 * this validator has to check it.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedAudience;

    public AudienceValidator(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audiences = token.getAudience();
        if (audiences != null && audiences.contains(expectedAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                OAuth2ErrorCodes.INVALID_TOKEN,
                "Required audience '" + expectedAudience + "' not present in token",
                "https://datatracker.ietf.org/doc/html/rfc9068#section-4"
        ));
    }
}
