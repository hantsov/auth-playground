package ee.authplayground.resourceserver.appcore.security.authprincipal;

/**
 * The authenticated caller, framework-agnostic.
 * <p>
 * Resolved at the controller boundary from whatever auth mechanism the app
 * happens to use today (a Keycloak-issued JWT, currently). Downstream code —
 * controllers using {@link CurrentUser}, services taking commands built from
 * this — never imports a JWT or session type. Swap the resolver and the rest
 * of the app is untouched.
 * <p>
 * {@code subject} is the stable user id (the JWT {@code sub}, or equivalent
 * in a session-based world) and is always present. The identity fields are
 * nullable because not every auth source carries them.
 */
public record AuthenticatedUser(
        String subject,
        String username,
        String email,
        String firstName,
        String lastName
) {
    public AuthenticatedUser {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("AuthenticatedUser.subject is required");
        }
    }
}
