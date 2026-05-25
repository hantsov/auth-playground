package ee.authplayground.resourceserver.features.user.command;

/**
 * Command to mirror the IdP-supplied identity into an existing user row.
 * <p>
 * {@code username} is nullable here on purpose: per the existing sync
 * semantics, the local username is only overwritten when the IdP carries a
 * non-blank value. Nullable identity fields ({@code email},
 * {@code firstName}, {@code lastName}) follow the IdP verbatim, including
 * nulls.
 */
public record SyncUser(
        String subject,
        String username,
        String email,
        String firstName,
        String lastName
) {
    public SyncUser {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
    }
}
