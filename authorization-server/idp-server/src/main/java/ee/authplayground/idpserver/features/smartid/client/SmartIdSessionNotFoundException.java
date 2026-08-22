package ee.authplayground.idpserver.features.smartid.client;

/**
 * The session ID is unknown to SK — expired, or already consumed.
 * <p>
 * Ordinary rather than alarming: Smart-ID sessions are short-lived, and a
 * browser tab left open past the session lifetime lands here. It is reported as
 * an expiry to the user, not as a failure of Smart-ID.
 */
public class SmartIdSessionNotFoundException extends RuntimeException {

    public SmartIdSessionNotFoundException(String message) {
        super(message);
    }
}
