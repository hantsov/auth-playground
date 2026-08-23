package ee.authplayground.idpserver.features.smartid.service;

import ee.authplayground.idpserver.features.smartid.validation.ValidatedSmartIdIdentity;
import ee.authplayground.idpserver.features.users.service.UserDataDetails;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.io.Serial;
import java.util.Collections;

/**
 * A Smart-ID authentication, before and after resolution — the Smart-ID
 * counterpart to {@code UsernamePasswordAuthenticationToken}.
 *
 * <h2>Why a token and a provider rather than setting the context directly</h2>
 * The short version of this flow is: verify the signature, look the person up,
 * put a principal in the {@code SecurityContext}. Doing that inline would work
 * and would quietly step outside Spring Security's model — no
 * {@code AuthenticationManager}, no provider chain, no events, nothing for a
 * future method to hook. Going through the normal machinery costs two small
 * classes and keeps Smart-ID a first-class authentication method rather than a
 * special case bolted to the side.
 *
 * <h2>The two states</h2>
 * <b>Unauthenticated:</b> carries a {@link ValidatedSmartIdIdentity}. Note what
 * that means — by the time this token is constructed, the cryptography is
 * already done. What remains is not "is this person who they claim" but "do we
 * have a record of them", which is a different question and the provider's job.
 * <p>
 * <b>Authenticated:</b> carries a {@link UserDataDetails} whose name is the
 * master's {@code users.id} — the same principal shape the password path
 * produces, which is what makes both methods arrive at one {@code sub}.
 *
 * <h2>No credentials, ever</h2>
 * {@link #getCredentials()} returns null in both states. There is no secret in
 * this flow: the private key never leaves the user's phone, and the signature
 * that proved possession has already been verified and is not a credential to
 * re-present.
 *
 * <h2>Deliberately not {@code @Transient}</h2>
 * Spring Security's {@code @Transient} marks an {@code Authentication} as one
 * that must <b>never be written to the {@code HttpSession}</b>. It reads like a
 * statement about serializable state, and it is not — it is a storage policy.
 * <p>
 * Annotating this class with it broke the brokered login in a way that looked
 * like anything but a session problem: the signature verified, the person
 * resolved, the log said the login was complete, and then
 * {@code HttpSessionSecurityContextRepository} silently declined to store the
 * context. The next request — the redirect back to {@code /oauth2/authorize} —
 * arrived anonymous, bounced to {@code /login}, and the user simply landed back
 * on the login page having done everything right.
 * <p>
 * The whole point of this token is that it survives to the next request: the
 * authorization endpoint is what turns it into a code for Keycloak. It has to
 * be storable, so it must not carry that annotation.
 */
public class SmartIdAuthenticationToken extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Object principal;

    /**
     * The unauthenticated form: Smart-ID has proven who this is, but we have not
     * yet asked whether we know them.
     */
    public SmartIdAuthenticationToken(ValidatedSmartIdIdentity identity) {
        // Explicitly the no-authorities collection rather than a bare null:
        // Spring Security 7 added a builder-taking constructor, so an untyped
        // null is now ambiguous between the two.
        super(Collections.<GrantedAuthority>emptyList());
        this.principal = identity;
        setAuthenticated(false);
    }

    /** The authenticated form, produced only by {@link SmartIdAuthenticationProvider}. */
    private SmartIdAuthenticationToken(UserDataDetails user) {
        super(user.getAuthorities());
        this.principal = user;
        // The superclass constructor deliberately makes this the only way to set
        // the flag, so no caller can promote an unauthenticated token by hand.
        super.setAuthenticated(true);
    }

    static SmartIdAuthenticationToken authenticated(UserDataDetails user) {
        return new SmartIdAuthenticationToken(user);
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    /**
     * Always null. See the class comment — there is no secret anywhere in this
     * authentication method.
     */
    @Override
    public Object getCredentials() {
        return null;
    }
}
