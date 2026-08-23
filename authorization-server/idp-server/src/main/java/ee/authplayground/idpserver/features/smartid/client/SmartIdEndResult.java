package ee.authplayground.idpserver.features.smartid.client;

import java.util.Arrays;

/**
 * The {@code result.endResult} codes, and what each one should say to a person.
 *
 * <h2>Why every code gets its own message</h2>
 * "Authentication failed" is the honest answer to a wrong password, because
 * telling the user <i>which</i> half was wrong helps an attacker enumerate
 * accounts. None of that applies here. The user has already been reached on
 * their own phone; the session is already bound to an identity SK resolved.
 * These codes describe what happened at that phone — refused, timed out, wrong
 * code chosen — and a person who cancelled by accident deserves to be told they
 * cancelled rather than left guessing.
 * <p>
 * This is also the phase's test surface. SK publishes demo accounts that trigger
 * {@code USER_REFUSED}, {@code WRONG_VC} and {@code TIMEOUT} on demand, which is
 * why the notification flow is built before the QR one: the failure paths can be
 * exercised deliberately rather than hoped about.
 *
 * <h2>Note what is not modelled</h2>
 * There is no "unknown user" code. A national ID with no Smart-ID account fails
 * earlier, as a 404 on session creation — see {@code SmartIdClient}. And a
 * national ID that Smart-ID knows but our master does not is not a Smart-ID
 * outcome at all: it is a resolution failure on our side, handled after
 * validation succeeds.
 */
public enum SmartIdEndResult {

    /** The user confirmed. Nothing is proven yet — the signature still has to verify. */
    OK("Authentication completed."),

    USER_REFUSED("You cancelled the request in the Smart-ID app."),

    /** The user simply never answered. Their session, their timeout — not our error. */
    TIMEOUT("The request timed out. Please try again and confirm in the Smart-ID app."),

    DOCUMENT_UNUSABLE("This Smart-ID account cannot be used. Please check the Smart-ID app."),

    /**
     * The user was shown several codes and picked the wrong one. Worth its own
     * message: this is the failure mode that means something may be genuinely
     * wrong, because the code they should have picked is the one displayed here.
     */
    WRONG_VC("The verification code you selected did not match the one shown here."),

    REQUIRED_INTERACTION_NOT_SUPPORTED_BY_APP("Your Smart-ID app is out of date. Please update it and try again."),

    USER_REFUSED_CERT_CHOICE("You cancelled the device selection in the Smart-ID app."),

    USER_REFUSED_INTERACTION("You cancelled the confirmation in the Smart-ID app."),

    PROTOCOL_FAILURE("Smart-ID reported a protocol error. Please try again."),

    EXPECTED_LINKED_SESSION("The Smart-ID app received a different request. Please try again."),

    SERVER_ERROR("Smart-ID is having technical difficulties. Please try again later."),

    /**
     * Documented in the specification's prose but absent from its enum — so it
     * can arrive despite not being a declared value. Modelled deliberately.
     */
    ACCOUNT_UNUSABLE("This Smart-ID account is currently unusable."),

    /**
     * Not an SK code. Covers anything SK adds after this was written, so a new
     * upstream value surfaces as a clean failure rather than an exception on an
     * enum lookup.
     */
    UNKNOWN("Smart-ID authentication failed.");

    private final String userMessage;

    SmartIdEndResult(String userMessage) {
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }

    public boolean isSuccess() {
        return this == OK;
    }

    /**
     * Never throws. An unrecognised code becomes {@link #UNKNOWN}, because the
     * alternative — a 500 from an enum that fell behind the upstream API — turns
     * someone else's new status code into our outage.
     */
    public static SmartIdEndResult from(String code) {
        return Arrays.stream(values())
                .filter(value -> value.name().equals(code))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
