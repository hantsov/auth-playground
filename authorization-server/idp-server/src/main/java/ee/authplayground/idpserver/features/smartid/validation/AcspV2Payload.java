package ee.authplayground.idpserver.features.smartid.validation;

import ee.authplayground.idpserver.features.smartid.client.SessionStatusResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Rebuilds the exact string the user's phone signed.
 *
 * <h2>Why the RP has to reconstruct this at all</h2>
 * SK does not return the signed object — only its ingredients. That is
 * deliberate. If the server sent the string it claimed to have signed,
 * verifying the signature would prove only that the server is internally
 * consistent. Reassembling it from values <i>we</i> already hold — our
 * challenge, our relying-party name, our interactions — is what binds the
 * signature to our session rather than to some session.
 *
 * <h2>Byte-exactness is the whole game</h2>
 * Eleven fields joined by {@code |} (U+007C), UTF-8. One wrong byte anywhere and
 * the signature does not verify, with no indication of which field was wrong —
 * a failure indistinguishable from forgery. The known traps, each of which has
 * cost someone a day:
 * <ol>
 *   <li><b>{@code schemeName} is per-environment.</b> {@code smart-id-demo} on
 *       DEMO, {@code smart-id} in production. Hardcoding the production value
 *       makes every demo signature fail.</li>
 *   <li><b>Base64 fields stay Base64.</b> {@code serverRandom},
 *       {@code rpChallenge} and {@code userChallenge} go in as the encoded
 *       strings. Decoding them first feels tidier and is wrong.</li>
 *   <li><b>Empty fields keep their separators.</b> {@code brokeredRpName} and
 *       {@code initialCallbackUrl} are empty here, producing {@code ||}.
 *       Dropping the field instead of emptying it shifts every later field one
 *       slot left.</li>
 *   <li><b>The interactions hash covers the Base64 string as sent</b>, not the
 *       JSON inside it. The field name invites the opposite reading, and getting
 *       it wrong costs a live session to discover — see
 *       {@link #interactionsDigest}.</li>
 *   <li><b>{@code flowType} is the description string</b> — {@code Notification},
 *       capitalised — not an enum constant name.</li>
 * </ol>
 *
 * <h2>Nothing here knows which flow it is serving</h2>
 * Every flow-specific value arrives in {@link SmartIdExpectation}. This class
 * builds one string from two inputs and has no opinion about where they came
 * from, which is what lets Phase 4 reuse it without modification.
 */
public final class AcspV2Payload {

    private static final String SEPARATOR = "|";

    /** Slot 2. The protocol name is a literal, unlike slot 1. */
    private static final String PROTOCOL = "ACSP_V2";

    private AcspV2Payload() {
    }

    /**
     * @param expectation        what we sent
     * @param signature          the ACSP_V2 signature block from the session response, carrying the
     *                           server- and device-side values we could not know in advance
     * @param interactionTypeUsed which prompt the app displayed — signed, so the user cannot have
     *                            been shown one thing while we believe another
     */
    public static String build(
            SmartIdExpectation expectation,
            SessionStatusResponse.AcspV2Signature signature,
            String interactionTypeUsed
    ) {
        return String.join(SEPARATOR,
                // 1. Per environment, not a constant. The single most common cause
                //    of "the signature will not verify and I cannot see why".
                expectation.schemeName(),
                // 2.
                PROTOCOL,
                // 3. Server entropy, used in its Base64 form exactly as received.
                nullToEmpty(signature.serverRandom()),
                // 4. OUR challenge, from our session — not echoed from the response,
                //    which would defeat the point of having one.
                expectation.rpChallengeBase64(),
                // 5. Device entropy. Present in the notification flow too.
                nullToEmpty(signature.userChallenge()),
                // 6.
                base64(expectation.relyingPartyName()),
                // 7. Empty for us: we are not brokering on behalf of another RP.
                //    The empty slot remains.
                base64(expectation.brokeredRpName()),
                // 8. Hash of the Base64 string AS SENT — not of the JSON inside it.
                interactionsDigest(expectation.interactionsBase64()),
                // 9.
                nullToEmpty(interactionTypeUsed),
                // 10. Empty in every cross-device flow — notification and QR alike.
                //     Only the same-device flows have a browser to return to.
                nullToEmpty(expectation.initialCallbackUrl()),
                // 11. Description string, not enum name. Checked against the
                //     allowed set before we get here.
                nullToEmpty(signature.flowType())
        );
    }

    /**
     * {@code BASE64(SHA-256(interactions))} — where {@code interactions} is the
     * <b>Base64 string exactly as sent in the request</b>, hashed as UTF-8 bytes
     * without decoding it first.
     *
     * <h2>The wording that misleads here</h2>
     * "SHA-256 of the interactions" reads like the interactions themselves — the
     * JSON. It is not. {@code interactions} is the name of the <i>request
     * field</i>, and that field holds Base64. So the digest covers the encoded
     * string, and decoding it first — which feels like the more principled
     * reading — produces a hash that is wrong in a way nothing reports: the
     * payload still has eleven well-formed slots and the signature simply does
     * not verify.
     * <p>
     * SK's reference client settles it: {@code InteractionUtil.calculateDigest}
     * hashes {@code String.getBytes(UTF_8)} of whatever it is handed, and the
     * validator hands it {@code request.interactions()} — the encoded form.
     *
     * <h2>Which makes the retention rule simpler, not harder</h2>
     * Because the hash covers the transmitted string, holding onto that one
     * string is all that is required. There is no need to preserve the JSON
     * separately, and no way for a re-serialisation to drift: the bytes that
     * were sent are the bytes that are hashed.
     */
    private static String interactionsDigest(String interactionsBase64) {
        return Base64.getEncoder().encodeToString(
                sha256(interactionsBase64.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Base64 of the UTF-8 bytes. A null or empty input yields an empty string —
     * which is also what Base64 of empty would produce, so the slot is empty
     * either way and, crucially, still present.
     */
    private static String base64(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
