package ee.authplayground.idpserver.features.users.client;

import java.util.UUID;

/**
 * Person attributes as the user data master hands them out.
 * <p>
 * {@code id} is the master's user ID, and this service asserts it verbatim as
 * the {@code sub} claim. Nothing else on this record is an identifier — every
 * other field is a display or contact attribute that may change without
 * breaking a single downstream link.
 *
 * @param emailVerified whether anyone actually verified {@code email}. Emitted
 *                      as the claim of the same name, which used to be a
 *                      hardcoded {@code true}.
 */
public record UserDataResponse(
        UUID id,
        String nationalId,
        String nationality,
        String username,
        String email,
        boolean emailVerified,
        String givenName,
        String familyName,
        boolean enabled
) {
}
