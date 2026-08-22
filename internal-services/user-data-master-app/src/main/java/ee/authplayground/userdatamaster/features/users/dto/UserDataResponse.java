package ee.authplayground.userdatamaster.features.users.dto;

import ee.authplayground.userdatamaster.features.users.entity.UserData;

import java.util.UUID;

/**
 * Person attributes as they leave the master.
 * <p>
 * A DTO rather than the entity, for one reason worth stating: the entity is
 * reachable from {@link ee.authplayground.userdatamaster.features.users.entity.UserCredential},
 * and serialising entities across a service boundary is how a password hash
 * eventually ends up in a response that was never supposed to carry one.
 *
 * @param id            the stable subject identifier — this is what becomes {@code sub}
 * @param nationalId    bare code, e.g. {@code 40404040009}
 * @param nationality   ISO 3166-1 alpha-2, e.g. {@code EE}
 * @param emailVerified whether anyone actually verified {@code email}. Not a constant.
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

    public static UserDataResponse from(UserData user) {
        return new UserDataResponse(
                user.getId(),
                user.getNationalId(),
                user.getNationality(),
                user.getUsername(),
                user.getEmail(),
                user.isEmailVerified(),
                user.getGivenName(),
                user.getFamilyName(),
                user.isEnabled()
        );
    }
}
