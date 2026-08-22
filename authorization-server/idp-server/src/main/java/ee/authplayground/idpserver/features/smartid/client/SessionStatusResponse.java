package ee.authplayground.idpserver.features.smartid.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The whole result of {@code GET /v3/session/{sessionID}}, in one place so the
 * wire shape is readable without opening the specification.
 *
 * <h2>What this object is not</h2>
 * It is <b>not evidence of anything</b> until the validation package has been
 * through it. {@code result.endResult == OK} is the server's opinion, delivered
 * over a channel an attacker who can reach our process could also produce. The
 * only field here that carries proof is {@link AcspV2Signature#value()}, and
 * only once it has been verified against a payload we reconstructed ourselves,
 * with a public key from a certificate we chained to a CA we configured.
 * <p>
 * An integration that reads {@code endResult} and trusts {@code cert} is
 * trivially forgeable. That is the single most important sentence about this
 * class.
 *
 * <h2>Unknown fields are ignored on purpose</h2>
 * SK adds fields as the API evolves and this is our reading of an HTTP contract,
 * not a generated mirror of it. A new field upstream must not stop logins here.
 *
 * @param state               {@code RUNNING} or {@code COMPLETE}. Only {@code COMPLETE} carries a
 *                            result; {@code RUNNING} means the long poll timed out with the user
 *                            still deciding, and is not an error.
 * @param result              present once {@code state} is {@code COMPLETE}
 * @param signatureProtocol   {@code ACSP_V2} for authentication sessions. Worth checking rather than
 *                            assuming: it selects which shape {@code signature} takes.
 * @param signature           the only field carrying cryptographic proof
 * @param cert                the user's certificate, DER+Base64. Identity comes from <i>here</i>,
 *                            after chain validation — not from what the user typed into the form.
 * @param interactionTypeUsed which prompt the app actually displayed. Signed, so it cannot be
 *                            misreported; occupies its own slot in the ACSP_V2 payload.
 * @param deviceIpAddress     only present when {@code requestProperties.shareMdClientIpAddress} was
 *                            requested and the RP is privileged to receive it. We ask for neither.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionStatusResponse(
        String state,
        Result result,
        String signatureProtocol,
        AcspV2Signature signature,
        Certificate cert,
        String interactionTypeUsed,
        String deviceIpAddress
) {

    /** The session is still open; the user has not yet confirmed or refused. */
    public static final String STATE_RUNNING = "RUNNING";

    /** The session has finished — successfully or otherwise. Check {@code result.endResult}. */
    public static final String STATE_COMPLETE = "COMPLETE";

    public boolean isComplete() {
        return STATE_COMPLETE.equals(state);
    }

    /**
     * @param endResult      {@code OK} or one of the failure codes. See {@code SmartIdEndResult},
     *                       which maps them to something a user can read.
     * @param documentNumber identifies the specific device that answered, e.g.
     *                       {@code PNOEE-30001010004-BVFM-Q}. Present when {@code endResult} is
     *                       {@code OK}. Note this is a <i>document number</i>, not the ETSI
     *                       semantics identifier the request was addressed to — the trailing suffix
     *                       is the device, not the person. Phase 4 uses it; this phase does not.
     * @param details        extra context, currently only the refused interaction
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(String endResult, String documentNumber, Details details) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Details(String interaction) {
    }

    /**
     * The ACSP_V2 signature and the server-side inputs needed to rebuild what
     * was signed.
     *
     * <h2>Why the signed object is not in the response</h2>
     * SK returns the <i>ingredients</i> and expects the RP to reassemble the
     * payload itself. That is deliberate: if the server sent the string it
     * claims to have signed, verifying it would prove only that the server is
     * self-consistent. Reconstructing it from values we already hold — our
     * {@code rpChallenge}, our relying-party name, our interactions — is what
     * binds the signature to <i>our</i> session.
     *
     * @param value                        the signature, Base64
     * @param serverRandom                 server-side entropy, used <b>as-is in its Base64 form</b>
     *                                     in the payload. Do not decode it first.
     * @param userChallenge                Base64URL value originating on the user's device. Present
     *                                     in the notification flow too, and it occupies its own slot.
     * @param flowType                     {@code Notification} here. Signed, so the flow that
     *                                     produced this response cannot be misrepresented — a QR
     *                                     session cannot be replayed as a notification one.
     * @param signatureAlgorithm           echoes what we requested, e.g. {@code rsassa-pss}
     * @param signatureAlgorithmParameters the PSS parameters needed to verify
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AcspV2Signature(
            String value,
            String serverRandom,
            String userChallenge,
            String flowType,
            String signatureAlgorithm,
            SignatureAlgorithmParameters signatureAlgorithmParameters
    ) {
    }

    /**
     * RSASSA-PSS parameters as returned.
     *
     * <h2>These are not decoration</h2>
     * PSS is parameterised: the same key and the same message verify or fail
     * depending on digest, salt length and mask generation function. Java's
     * {@code Signature} needs all of them via {@code PSSParameterSpec}, and
     * getting one wrong produces "signature does not verify" — indistinguishable
     * from a forged signature, which is the worst possible way to be told about
     * a configuration mistake.
     *
     * @param hashAlgorithm    digest over the payload, e.g. {@code SHA-512}
     * @param maskGenAlgorithm MGF1 and its own digest
     * @param saltLength       in bytes
     * @param trailerField     {@code "0xbc"} — the only value RFC 8017 defines
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SignatureAlgorithmParameters(
            String hashAlgorithm,
            MaskGenAlgorithm maskGenAlgorithm,
            Integer saltLength,
            String trailerField
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MaskGenAlgorithm(String algorithm, MaskGenParameters parameters) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MaskGenParameters(String hashAlgorithm) {
    }

    /**
     * @param value            the certificate, DER encoded then Base64. The identity we act on comes
     *                         out of this and nowhere else.
     * @param certificateLevel {@code ADVANCED} or {@code QUALIFIED}. Must be checked against what we
     *                         asked for: the API returning a lower level than requested is exactly
     *                         the case a naive integration would accept.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Certificate(String value, String certificateLevel) {
    }
}
