package ee.authplayground.idpserver.features.smartid.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * SK's Relying Party API v3, as a declarative interface — so the endpoint list
 * reads like the documentation rather than like plumbing.
 *
 * <h2>Handwritten, and deliberately so</h2>
 * SK publishes a Java client. It is read here as reference and never taken as a
 * dependency, because a playground whose entire purpose is showing how
 * authentication works learns nothing from delegating the interesting half to a
 * library. The protocol should be legible in this repository.
 * <p>
 * That rule covers the protocol, not everything adjacent to it — Phase 4 may use
 * a QR encoding library, because drawing a QR image is not protocol.
 *
 * <h2>Only two endpoints</h2>
 * Notification authentication is a create-then-poll protocol and nothing more.
 * The device-link endpoints belong to Phase 4 and are absent rather than stubbed
 * out; a half-built method is a worse signpost than none.
 */
public interface SmartIdApi {

    /**
     * Starts a notification session: SK pushes a confirmation prompt to the
     * phone registered against this identity.
     *
     * <h2>The path parameter is an ETSI semantics identifier</h2>
     * {@code PNO} + upper-case ISO 3166-1 alpha-2 country + {@code -} + the
     * national code, e.g. {@code PNOEE-40404040009}. Per ETSI EN 319 412-1.
     * <p>
     * <b>Not a document number.</b> SK's published demo accounts are written
     * with a trailing {@code -MOCK-Q} or {@code -DEMO-Q}, which identifies a
     * <i>device</i>, not a person, and belongs to the device-link and
     * re-authentication endpoints. Sending one here is a 404.
     *
     * @return a session ID and nothing else — notably no verification code
     * @throws SmartIdUserNotFoundException when this identity has no usable Smart-ID account (404)
     * @throws SmartIdUnavailableException  for every other non-2xx response
     */
    @PostExchange("/v3/authentication/notification/etsi/{semanticsIdentifier}")
    NotificationAuthenticationResponse startNotificationAuthentication(
            @PathVariable String semanticsIdentifier,
            @RequestBody NotificationAuthenticationRequest request
    );

    /**
     * Long-polls one session.
     *
     * <h2>This is a long poll, not a busy loop</h2>
     * The call blocks server-side until the session finishes or
     * {@code timeoutMs} elapses, then returns {@code RUNNING} if the user is
     * still deciding. So a {@code RUNNING} response is a normal outcome rather
     * than a failure, and the correct reaction is to ask again — not to sleep
     * and retry on a timer of our own, which would be two waits stacked.
     * <p>
     * SK bounds {@code timeoutMs} to 1s–120s.
     *
     * @param sessionId from the create call
     * @param timeoutMs how long SK may hold the connection open
     * @throws SmartIdSessionNotFoundException when the session expired or never existed (404)
     */
    @GetExchange("/v3/session/{sessionId}")
    SessionStatusResponse getSessionStatus(
            @PathVariable String sessionId,
            @RequestParam Long timeoutMs
    );
}
