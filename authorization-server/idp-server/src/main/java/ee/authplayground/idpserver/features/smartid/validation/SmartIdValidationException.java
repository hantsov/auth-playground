package ee.authplayground.idpserver.features.smartid.validation;

/**
 * A Smart-ID response failed verification.
 *
 * <h2>Treat every one of these as an attack until proven otherwise</h2>
 * This is not the exception for "the user cancelled" — that is a perfectly
 * ordinary {@code endResult} and never reaches here. Reaching this class means
 * the response claimed success and then failed a cryptographic check: a
 * signature that does not verify, a certificate that chains nowhere, a challenge
 * that is not the one we issued.
 * <p>
 * In development the cause is almost always our own payload reconstruction
 * being one byte out. In production the cause is someone forging a response.
 * The two are indistinguishable from here, so the message is written for the
 * developer and what the user sees is generic — telling an attacker <i>which</i>
 * check caught them is free tuning information.
 */
public class SmartIdValidationException extends RuntimeException {

    public SmartIdValidationException(String message) {
        super(message);
    }

    public SmartIdValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
