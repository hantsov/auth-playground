package ee.authplayground.idpserver.features.smartid.client;

/**
 * Smart-ID could not be reached, or answered in a way that means the fault is
 * ours or theirs — never the user's.
 * <p>
 * Kept distinct from a failed authentication on purpose. "SK returned 401
 * because our relying-party UUID is not registered for this source IP" and "the
 * user tapped Cancel" are the same event from the login page's point of view and
 * completely different events to whoever has to fix one of them. Collapsing them
 * into one message is how an afternoon disappears at the wrong layer.
 */
public class SmartIdUnavailableException extends RuntimeException {

    public SmartIdUnavailableException(String message) {
        super(message);
    }

    public SmartIdUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
