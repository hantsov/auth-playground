package ee.authplayground.idpserver.features.smartid.client;

/**
 * What comes back from creating a notification session: a session ID, and
 * nothing else.
 *
 * <h2>Note what is absent</h2>
 * No verification code. The four digits the user compares against their phone
 * are derived by <b>us</b>, from the {@code rpChallenge} <b>we</b> generated —
 * never received from the server.
 * <p>
 * That is what makes it a verification code rather than a decoration. Both ends
 * compute it independently from the same challenge, so a matching pair of
 * numbers tells the user the phone is being asked to confirm <i>this</i>
 * session, and not one an attacker started in parallel. A code handed to us by
 * the server would prove only that the server said so.
 *
 * @param sessionID poll {@code GET /v3/session/{sessionID}} with this
 */
public record NotificationAuthenticationResponse(String sessionID) {
}
