package ee.authplayground.idpserver.features.users.client;

import org.springframework.security.core.AuthenticationException;

/**
 * The master could not answer — network failure, bad service token, missing
 * scope, or the master simply being down.
 * <p>
 * An {@link AuthenticationException} so it travels the form-login failure path
 * rather than surfacing as a 500, but a <b>distinct type</b> from
 * {@code UsernameNotFoundException} on purpose. "We could not check" is not
 * "the credentials were wrong", and a system that reports the first as the
 * second sends whoever debugs it to the wrong layer entirely.
 * <p>
 * The user still sees the generic login error — nothing about infrastructure
 * belongs on a login page — but the logs say what actually happened.
 */
public class UserMasterUnavailableException extends AuthenticationException {

    public UserMasterUnavailableException(String message) {
        super(message);
    }

    public UserMasterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
