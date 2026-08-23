package ee.authplayground.idpserver.features.smartid.validation;

import ee.authplayground.idpserver.features.smartid.client.SessionStatusResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signed payload, slot by slot.
 *
 * <h2>Why this test exists</h2>
 * Every mistake this guards against produces the same symptom — "signature does
 * not verify" — with nothing to indicate which of eleven fields was wrong, and
 * a failure indistinguishable from a forged response. Each assertion below
 * corresponds to a documented trap that has cost someone a day:
 * <ul>
 *   <li>hardcoding {@code smart-id} when DEMO wants {@code smart-id-demo};</li>
 *   <li>dropping an empty field instead of emptying it, shifting every later
 *       slot one place left;</li>
 *   <li>decoding the Base64 values before concatenating them;</li>
 *   <li>hashing the Base64 interactions string rather than the JSON it encodes.</li>
 * </ul>
 * A live end-to-end test would catch all of these too — and only when SK is
 * reachable, only against a demo account, and only as one undifferentiated
 * failure.
 */
class AcspV2PayloadTest {

    private static final String SCHEME_NAME = "smart-id-demo";
    private static final String RP_CHALLENGE = "cmFuZG9tLWNoYWxsZW5nZS12YWx1ZQ==";
    private static final String RP_NAME = "DEMO";
    private static final String SERVER_RANDOM = "+wVP2U/SMKVkVrggDjNTXFV/";
    private static final String USER_CHALLENGE = "TLSjYRH2oYw8tW2bq0it0IUb7WIFkCLgF8NTc7-4Zq4";
    private static final String INTERACTIONS_JSON =
            "[{\"type\":\"displayTextAndPIN\",\"displayText60\":\"Log in to Auth Playground\"}]";
    private static final String INTERACTION_TYPE_USED = "displayTextAndPIN";

    @Test
    void hasElevenSlotsInTheDocumentedOrder() {
        String[] slots = build().split("\\|", -1);

        assertThat(slots).hasSize(11);
        assertThat(slots[0]).isEqualTo(SCHEME_NAME);
        assertThat(slots[1]).isEqualTo("ACSP_V2");
        assertThat(slots[2]).isEqualTo(SERVER_RANDOM);
        assertThat(slots[3]).isEqualTo(RP_CHALLENGE);
        assertThat(slots[4]).isEqualTo(USER_CHALLENGE);
        assertThat(slots[5]).isEqualTo(base64(RP_NAME));
        assertThat(slots[6]).isEmpty();
        assertThat(slots[7]).isEqualTo(base64(sha256(base64(INTERACTIONS_JSON))));
        assertThat(slots[8]).isEqualTo(INTERACTION_TYPE_USED);
        assertThat(slots[9]).isEmpty();
        assertThat(slots[10]).isEqualTo("Notification");
    }

    /**
     * The scheme name is slot 1 and it is environment-specific. Hardcoding the
     * production value is the single most likely reason a first integration
     * never verifies a single signature.
     */
    @Test
    void usesTheConfiguredSchemeNameRatherThanAConstant() {
        assertThat(build()).startsWith("smart-id-demo|ACSP_V2|");
        assertThat(build()).doesNotStartWith("smart-id|");
    }

    /**
     * The empty brokered-RP name and callback URL still occupy their slots.
     * Splitting on {@code -1} above already proves the count; this states the
     * consequence directly, because {@code ||} is the thing to look for when
     * eyeballing a payload.
     */
    @Test
    void keepsEmptyFieldsAsEmptySlots() {
        String payload = build();

        // Slot 6 (brokeredRpName) is empty, between the encoded RP name and the
        // interactions digest.
        assertThat(payload).contains("|" + base64(RP_NAME) + "||");
        // Slot 9 (initialCallbackUrl) is empty, between the interaction type and
        // the flow type.
        assertThat(payload).contains("|" + INTERACTION_TYPE_USED + "||Notification");
    }

    /**
     * The Base64 values go in as they arrived. Decoding them first is the tidy
     * looking mistake.
     */
    @Test
    void leavesBase64ValuesEncoded() {
        String payload = build();

        assertThat(payload).contains(SERVER_RANDOM).contains(RP_CHALLENGE).contains(USER_CHALLENGE);
    }

    /**
     * The digest covers the Base64 string as transmitted, not the JSON inside it.
     *
     * <h2>This assertion is inverted from the obvious reading, on purpose</h2>
     * "SHA-256 of the interactions" sounds like the interactions themselves.
     * {@code interactions} is the name of the request <i>field</i>, and that
     * field holds Base64 — so the hash covers the encoded string. Decoding first
     * is the more principled-feeling reading and it is wrong; it was caught only
     * by a live session against the demo environment, because a wrong digest
     * still produces eleven well-formed slots and fails as nothing more specific
     * than "signature did not verify".
     */
    @Test
    void hashesTheEncodedInteractionsNotTheDecodedJson() {
        String payload = build();

        assertThat(payload).contains(base64(sha256(base64(INTERACTIONS_JSON))));
        assertThat(payload).doesNotContain(base64(sha256(INTERACTIONS_JSON)));
    }

    /**
     * Two sessions must not produce the same signed bytes, or a signature from
     * one would verify against the other.
     */
    @Test
    void differsWhenTheChallengeDiffers() {
        String other = build(expectation("a-different-challenge"));

        assertThat(other).isNotEqualTo(build());
    }

    private String build() {
        return build(expectation(RP_CHALLENGE));
    }

    private String build(SmartIdExpectation expectation) {
        return AcspV2Payload.build(expectation, signature(), INTERACTION_TYPE_USED);
    }

    private SmartIdExpectation expectation(String rpChallenge) {
        return new SmartIdExpectation(
                SCHEME_NAME,
                rpChallenge,
                RP_NAME,
                "",
                base64(INTERACTIONS_JSON),
                "",
                Set.of(SmartIdExpectation.FLOW_TYPE_NOTIFICATION),
                "QUALIFIED",
                null);
    }

    private SessionStatusResponse.AcspV2Signature signature() {
        return new SessionStatusResponse.AcspV2Signature(
                "ignored-for-payload-construction",
                SERVER_RANDOM,
                USER_CHALLENGE,
                "Notification",
                "rsassa-pss",
                null);
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
