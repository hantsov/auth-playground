package ee.authplayground.resourceserver.features.user.command;

/**
 * Command to provision a new user row from the IdP-supplied identity.
 * Built at the controller boundary from {@code AuthenticatedUser}; the
 * service layer accepts only validated commands.
 */
public record RegisterUser(
        String subject,
        String username,
        String email,
        String firstName,
        String lastName
) {
    public RegisterUser {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
    }
}
