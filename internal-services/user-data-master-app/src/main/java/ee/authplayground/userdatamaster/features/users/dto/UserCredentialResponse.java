package ee.authplayground.userdatamaster.features.users.dto;

import ee.authplayground.userdatamaster.features.users.entity.UserCredential;
import ee.authplayground.userdatamaster.features.users.entity.UserCredentialType;

import java.util.UUID;

/**
 * A credential record and the person it belongs to, in one response.
 * <p>
 * Both halves travel together because idp-server needs both at the same moment
 * and cannot come back for the second: the login POST and the ID-token issuance
 * are different HTTP requests, separated by a browser redirect and a
 * back-channel token call from Keycloak. idp-server carries the person
 * attributes forward on the authenticated principal instead of asking twice.
 * <p>
 * That is also why this response requires both {@code credentials:read} and
 * {@code customer:read} — it is one read returning two kinds of data.
 *
 * @param secretHash BCrypt for {@code PASSWORD}, {@code null} for {@code SMART_ID}.
 *                   The master hands this out rather than verifying it; see
 *                   {@code UserCredentialController} for the reasoning.
 */
public record UserCredentialResponse(
        UUID id,
        UserCredentialType type,
        String identifier,
        String secretHash,
        boolean enabled,
        UserDataResponse user
) {

    public static UserCredentialResponse from(UserCredential credential) {
        return new UserCredentialResponse(
                credential.getId(),
                credential.getType(),
                credential.getIdentifier(),
                credential.getSecretHash(),
                credential.isEnabled(),
                UserDataResponse.from(credential.getUser())
        );
    }
}
