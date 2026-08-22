package ee.authplayground.idpserver.features.smartid.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification code arithmetic, checked against <b>SK's own test vectors</b>.
 *
 * <h2>Why the vectors matter more than the assertions</h2>
 * SK's documentation contradicts itself here: the prose says take the two
 * rightmost bytes of the digest, while the pseudocode beside it reads
 * {@code [-2:-1]}, which as a Python slice is one byte. Both readings produce a
 * plausible-looking four-digit number, and a test written from our own
 * implementation would pass under either. So the expected values below are taken
 * from {@code VerificationCodeCalculatorTest} in SK's reference client rather
 * than computed here — that is the only thing that makes this test evidence
 * rather than a restatement.
 * <p>
 * The one-byte reading would cap every code at 255. Note how many vectors below
 * exceed that.
 */
class RpChallengeTest {

    /**
     * SK's direct vector: the two bytes {@code 0x1B 0xBB}, which Base64-encode
     * to {@code G7s=}. Their client hashes the input before slicing, and so do
     * we — the challenge is hashed, not read directly.
     */
    @Test
    void matchesReferenceClientDirectVector() {
        assertThat(RpChallenge.verificationCode("G7s=")).isEqualTo("4555");
    }

    /**
     * SK's parameterised vectors. Their test hashes each phrase and passes the
     * digest in as the challenge, so we do the same and Base64 it, which is the
     * form we hold a challenge in.
     */
    @ParameterizedTest
    @CsvSource({
            "Hello World!, 7712",
            "Go ahead| make my day., 7782",
            "You're gonna need a bigger boat., 1464",
            "Say 'hello' to my little friend!, 4240"
    })
    void matchesReferenceClientVectors(String phrase, String expectedCode) {
        // The pipe stands in for a comma the CSV parser would otherwise split on.
        String challenge = base64(sha256(phrase.replace('|', ',')));

        assertThat(RpChallenge.verificationCode(challenge)).isEqualTo(expectedCode);
    }

    /**
     * Always four characters, zero-padded. A code rendered as "42" would send
     * the user looking for a two-digit number on their phone and finding
     * "0042" — at which point the comparison the code exists for silently stops
     * happening.
     */
    @Test
    void isAlwaysFourDigits() {
        for (int i = 0; i < 500; i++) {
            assertThat(RpChallenge.verificationCode(RpChallenge.generate()))
                    .hasSize(4)
                    .containsOnlyDigits();
        }
    }

    /**
     * Two challenges are never the same. Trivially true for a correct
     * implementation and catastrophic if it ever stops being: a predictable
     * challenge makes the signature replayable, which is the single thing the
     * challenge exists to prevent.
     */
    @Test
    void generatesADistinctChallengeEachTime() {
        assertThat(RpChallenge.generate()).isNotEqualTo(RpChallenge.generate());
    }

    /** 64 bytes, the top of SK's permitted 32–64 range, so 88 Base64 characters. */
    @Test
    void generatesAChallengeOfTheDocumentedSize() {
        assertThat(Base64.getDecoder().decode(RpChallenge.generate())).hasSize(64);
        assertThat(RpChallenge.generate().length()).isBetween(44, 88);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
