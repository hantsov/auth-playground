package ee.authplayground.idpserver.features.smartid.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One entry in the {@code interactions} list — what the Smart-ID app shows the
 * user while asking them to confirm.
 *
 * <h2>Why this is a list and not a string</h2>
 * The RP sends several interactions in preference order and the app picks the
 * best one it supports, reporting its choice back as {@code interactionTypeUsed}.
 * Older app versions may not support the richer screens, and an RP that demanded
 * one would simply fail on those devices.
 * <p>
 * That returned choice is <b>signed</b>: {@code interactionTypeUsed} occupies a
 * slot in the ACSP_V2 payload. So the user cannot be shown one prompt while the
 * RP believes another was displayed — the signature would not verify. That is
 * what makes "what did the user actually agree to" a cryptographic fact rather
 * than a hopeful one.
 *
 * <h2>Text length is part of the type</h2>
 * {@code displayTextAndPIN} carries {@code displayText60};
 * {@code confirmationMessage} and
 * {@code confirmationMessageAndVerificationCodeChoice} carry
 * {@code displayText200}. Only the field matching the type may be present, which
 * is why nulls are dropped from the JSON — a null sibling would serialise as an
 * explicit null and be rejected.
 *
 * @param type           one of {@code displayTextAndPIN}, {@code confirmationMessage},
 *                       {@code confirmationMessageAndVerificationCodeChoice}
 * @param displayText60  at most 60 characters, for {@code displayTextAndPIN}
 * @param displayText200 at most 200 characters, for the confirmation types
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Interaction(
        String type,
        String displayText60,
        String displayText200
) {

    /**
     * The simplest interaction, and the one every app version supports: a line
     * of text plus the PIN entry screen.
     */
    public static Interaction displayTextAndPin(String text) {
        return new Interaction("displayTextAndPIN", text, null);
    }

    /**
     * A separate confirmation screen before PIN entry, with room for a fuller
     * description. The app falls back to {@code displayTextAndPIN} when it does
     * not support this, which is why both are sent.
     */
    public static Interaction confirmationMessage(String text) {
        return new Interaction("confirmationMessage", null, text);
    }
}
