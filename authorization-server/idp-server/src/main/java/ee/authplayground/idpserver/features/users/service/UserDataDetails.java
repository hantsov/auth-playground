package ee.authplayground.idpserver.features.users.service;

import ee.authplayground.idpserver.features.users.client.UserDataResponse;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

import java.io.Serial;
import java.util.Collection;

/**
 * The authenticated principal — and the reason the {@code sub} claim is finally
 * correct.
 *
 * <h2>The username is the user's UUID</h2>
 * Spring Authorization Server derives {@code sub} from
 * {@code Authentication#getName()}, which for a {@link User} is whatever was
 * passed as the username. This class passes the <b>master's user ID</b>, not
 * the login name.
 * <p>
 * Before this, {@code sub} was the login name — meaning the stable federated
 * identifier Keycloak links its shadow user to was a mutable display string,
 * and renaming a user would sever the link. The subject identifier is now the
 * golden record's primary key, which is stable by construction.
 *
 * <h2>Why it carries person attributes</h2>
 * They come from the same master response as the credential, and they are
 * needed later — at token issuance, which is a different HTTP request entirely
 * (the browser has been redirected and Keycloak is calling the token endpoint
 * back-channel). Carrying them on the principal is what makes one master call
 * per login enough; {@code OidcClaimsCustomizer} reads them from here rather
 * than asking the master again.
 * <p>
 * They are a <b>snapshot taken at authentication time</b>. That is not a
 * limitation to apologise for — claims describe the authentication event they
 * were issued for, which is exactly what every OIDC provider does.
 *
 * <h2>One caveat for later</h2>
 * The authorization store is in-memory today, so this object is simply held
 * live. If it ever moves to JDBC (see {@code AuthorizationServerConfig}), this
 * class has to be Jackson-serializable — which means a mixin registered with
 * Spring Security's Jackson modules, not just a default constructor.
 */
@Getter
public class UserDataDetails extends User {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The display handle. Emitted as {@code preferred_username} — which is
     * explicitly <i>not</i> an identifier, and OIDC Core says so: relying
     * parties must not use it as a key, because it can change.
     */
    private final String preferredUsername;

    private final String email;
    private final boolean emailVerified;
    private final String givenName;
    private final String familyName;

    private UserDataDetails(
            String subject,
            String passwordHash,
            boolean enabled,
            Collection<? extends GrantedAuthority> authorities,
            UserDataResponse user
    ) {
        super(subject, passwordHash, enabled, true, true, true, authorities);
        this.preferredUsername = user.username();
        this.email = user.email();
        this.emailVerified = user.emailVerified();
        this.givenName = user.givenName();
        this.familyName = user.familyName();
    }

    /**
     * @param passwordHash the BCrypt hash from the master. Spring Security's
     *                     {@code DaoAuthenticationProvider} compares the
     *                     submitted plaintext against it — the master never
     *                     performs the comparison and never sees the plaintext.
     */
    public static UserDataDetails of(UserDataResponse user, String passwordHash, boolean credentialEnabled) {
        return new UserDataDetails(
                // The one line this whole refactor exists for.
                user.id().toString(),
                passwordHash,
                // Either the person or the credential being disabled is enough to
                // refuse the login, and they are genuinely different facts:
                // revoking one authentication method is not disabling a human.
                user.enabled() && credentialEnabled,
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                user
        );
    }

    /**
     * Carries no {@code {id}} prefix on purpose. Spring Security's
     * {@code DelegatingPasswordEncoder} refuses a value it cannot map to an
     * encoder, so if this ever reached a password comparison the result would be
     * an immediate exception rather than a silent match against some literal.
     */
    private static final String NO_STORED_CREDENTIAL = "no-stored-credential-smart-id-is-inherent";

    /**
     * The principal for an <b>inherent</b> authentication method — today,
     * Smart-ID.
     *
     * <h2>There is no hash because there is no credential</h2>
     * The password path fetches a stored secret and compares it. Nothing
     * equivalent exists here: the private key lives on the user's phone, SK
     * holds the certificate, and the proof was a signature verified before this
     * method was ever called. Passing a placeholder is not a workaround for a
     * missing value — it is an accurate statement that this server holds no
     * secret for this person.
     * <p>
     * The placeholder is a string no password encoder can ever match, and
     * {@code eraseCredentials()} clears it immediately afterwards, so nothing
     * downstream can mistake it for something comparable. {@link User} refuses a
     * null password outright, which is the only reason a value is passed at all.
     *
     * <h2>Enabled has one input here, not two</h2>
     * The password factory ands together the person and the credential, because
     * revoking one method is not disabling a human. With no credential row there
     * is only the person's own flag to consult.
     */
    public static UserDataDetails ofInherentMethod(UserDataResponse user) {
        UserDataDetails details = new UserDataDetails(
                user.id().toString(),
                NO_STORED_CREDENTIAL,
                user.enabled(),
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                user
        );
        details.eraseCredentials();
        return details;
    }

    /**
     * Full name, or {@code null} when neither half is known. Assembled here so
     * the claims customizer stays a list of claims rather than a list of claims
     * plus string handling.
     */
    public String getFullName() {
        if (givenName == null && familyName == null) {
            return null;
        }
        if (givenName == null) {
            return familyName;
        }
        if (familyName == null) {
            return givenName;
        }
        return givenName + " " + familyName;
    }
}
