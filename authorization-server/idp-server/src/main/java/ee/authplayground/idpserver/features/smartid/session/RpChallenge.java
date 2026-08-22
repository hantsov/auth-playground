package ee.authplayground.idpserver.features.smartid.session;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The relying party's challenge, and the four digits derived from it.
 *
 * <h2>What the challenge is for</h2>
 * A fresh random value per authentication, mixed into the data the user's phone
 * signs. Without it, a signature captured from one login could be replayed into
 * another — the response would be perfectly valid, just not about this attempt.
 * Checking that the signed payload contains <i>our</i> challenge is what makes
 * the assertion specific to this session.
 * <p>
 * SK's specification calls for 32–64 bytes from a cryptographic RNG. We use 64:
 * it is the top of the range and costs nothing.
 *
 * <h2>Why the verification code comes from the challenge</h2>
 * The number shown on screen and the number shown on the phone are computed
 * independently, on both ends, from the same challenge — it is never
 * transmitted. So a matching pair tells the user the phone is being asked about
 * <i>this</i> session.
 * <p>
 * That matters against a real attack: an attacker who tricks someone into
 * starting a login can trigger a genuine push to that person's phone. What they
 * cannot do is make the attacker's screen show the same four digits as the
 * victim's phone. A user who compares them sees the mismatch. This is why
 * displaying the code prominently is a security control and not decoration.
 */
public final class RpChallenge {

    /** Top of SK's permitted 32–64 byte range. */
    private static final int CHALLENGE_BYTES = 64;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private RpChallenge() {
    }

    /**
     * A fresh challenge, Base64 encoded — which is also the form that goes into
     * the signed payload, so it is stored and compared exactly as produced.
     */
    public static String generate() {
        byte[] bytes = new byte[CHALLENGE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * The four-digit verification code for a challenge.
     *
     * <h2>The algorithm, and the trap in SK's own documentation</h2>
     * SHA-256 the <b>raw challenge bytes</b> — the Base64 string decoded, not
     * the string itself — take the <b>two rightmost bytes</b>, read them as a
     * big-endian unsigned integer, and keep the last four decimal digits,
     * zero-padded.
     * <p>
     * SK's prose says two bytes. SK's pseudocode alongside it reads
     * {@code integer(SHA-256(rpChallenge)[-2:-1]) mod 10000}, which as a Python
     * slice is <i>one</i> byte. The prose is right, and SK's own Java client
     * confirms it: it reads a big-endian {@code short} at {@code length - 2}.
     * One byte would silently produce codes in the range 0–255 — plausible
     * enough to ship, and wrong on every login.
     */
    public static String verificationCode(String rpChallengeBase64) {
        byte[] digest = sha256(Base64.getDecoder().decode(rpChallengeBase64));

        // Two rightmost bytes, big-endian, unsigned. The mask is what makes it
        // unsigned: a Java short is signed, so digests ending in a high bit
        // would otherwise yield a negative number.
        short twoRightmostBytes = ByteBuffer.wrap(digest).getShort(digest.length - 2);
        int code = twoRightmostBytes & 0xFFFF;

        return "%04d".formatted(code % 10000);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every JVM. Unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
