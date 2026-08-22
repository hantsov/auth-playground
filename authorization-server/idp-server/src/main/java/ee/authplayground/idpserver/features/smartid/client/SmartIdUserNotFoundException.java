package ee.authplayground.idpserver.features.smartid.client;

/**
 * Smart-ID has no usable account for this identity — a 404 from session
 * creation.
 *
 * <h2>This is not "unknown user" in our sense</h2>
 * Two different absences are easy to conflate and must not be:
 * <ul>
 *   <li><b>This one:</b> the national ID is not a Smart-ID subscriber. Nothing
 *       was pushed to any phone, because there is no phone to push to.</li>
 *   <li><b>The other:</b> Smart-ID authenticated them fine, but our user data
 *       master has no person with that national ID. That happens <i>after</i> a
 *       successful signature, and in this phase it is a rejection — Phase 3
 *       turns it into a registration.</li>
 * </ul>
 * They deserve different messages, and only the second one is on the path to
 * ever becoming a successful login.
 */
public class SmartIdUserNotFoundException extends RuntimeException {

    public SmartIdUserNotFoundException(String message) {
        super(message);
    }
}
