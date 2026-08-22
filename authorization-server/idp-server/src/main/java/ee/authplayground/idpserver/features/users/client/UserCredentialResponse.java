package ee.authplayground.idpserver.features.users.client;

import java.util.UUID;

/**
 * What the user data master returns for a credential lookup: the credential
 * and the person it belongs to, in one response.
 * <p>
 * Deliberately a copy of the master's DTO rather than a shared module. Two
 * services sharing a compiled contract look tidy and quietly couple their
 * release cycles; the master's response is an HTTP contract, and this is this
 * service's reading of it. If the master adds a field, nothing here breaks —
 * unknown properties are ignored.
 *
 * @param secretHash BCrypt hash for {@code PASSWORD}, {@code null} for
 *                   {@code SMART_ID}. <b>This service performs the comparison</b>;
 *                   the master never sees a plaintext password.
 * @param user       person attributes, carried forward onto the authenticated
 *                   principal so token issuance needs no second call.
 */
public record UserCredentialResponse(
        UUID id,
        String type,
        String identifier,
        String secretHash,
        boolean enabled,
        UserDataResponse user
) {
}
