package ee.authplayground.idpserver.features.smartid.session;

import java.time.Duration;
import java.time.Instant;

/**
 * One in-flight Smart-ID authentication, as <b>we</b> see it. Not SK's session —
 * ours, holding what SK will never send back and what verification later
 * depends on.
 *
 * <h2>This is the anti-replay anchor</h2>
 * Almost everything here exists to be compared against the signed response.
 * {@link #rpChallengeBase64()} and {@link #interactionsBase64()} are inputs to
 * the signature; if they were not retained, verification could only re-derive
 * them from the response — which proves nothing, since the response is what is
 * under suspicion. <b>A challenge that is generated but never checked is
 * decoration.</b>
 *
 * <h2>Why the exact Base64 strings, not the values</h2>
 * {@code interactionsBase64} is stored as the literal string that was sent, not
 * as a list of {@code Interaction} objects. Re-serialising the same objects can
 * produce different bytes — field order, whitespace, escaping — and the
 * signature covers a hash of those bytes. Keeping the string is the only way to
 * be certain the hash matches. SK's own specification warns about this
 * explicitly.
 * <p>
 * The same reasoning applies to {@code rpChallengeBase64}: the payload uses the
 * Base64 form as-is rather than the underlying random bytes.
 *
 * <h2>Built for Phase 4, at no cost now</h2>
 * {@link #startedAt()} is not used by the notification flow. It is here because
 * the device-link flow needs {@code elapsedSeconds} in a QR image that must be
 * regenerated every second, and retrofitting time into a session model that
 * never had it means touching every call site. Same for the room to hold a
 * per-session secret: the QR flow has one, and it must never reach the browser.
 *
 * @param sessionId           SK's session ID, for polling
 * @param semanticsIdentifier the ETSI identifier this session was addressed to, e.g.
 *                            {@code PNOEE-40404040009}. Kept so the identity in the returned
 *                            certificate can be checked against the identity we asked for — the
 *                            notification flow names its subject up front, and confirming SK
 *                            answered about the right person is free.
 * @param rpChallengeBase64   exactly as sent
 * @param interactionsBase64  exactly as sent
 * @param verificationCode    the four digits shown to the user, derived from the challenge
 * @param startedAt           when SK accepted the session
 */
public record SmartIdSession(
        String sessionId,
        String semanticsIdentifier,
        String rpChallengeBase64,
        String interactionsBase64,
        String verificationCode,
        Instant startedAt
) {

    /**
     * How long this session has been running. Phase 4's device link is
     * time-bound and needs this per QR refresh; here it bounds how long we keep
     * polling before telling the user their attempt expired.
     */
    public Duration elapsed() {
        return Duration.between(startedAt, Instant.now());
    }

    public boolean hasExpired(Duration maxDuration) {
        return elapsed().compareTo(maxDuration) > 0;
    }
}
