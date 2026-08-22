package ee.authplayground.idpserver.features.smartid.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of {@code POST /v3/authentication/notification/etsi/{semantics-identifier}}.
 *
 * <h2>The literals are case-sensitive and none of them are guessable</h2>
 * Every string constant here came from SK's OpenAPI specification rather than
 * from the prose documentation, because the prose paraphrases and paraphrases
 * drift. Specifically: the field is {@code vcType}, not
 * {@code verificationCodeType}; its only legal value is the lower-case
 * {@code numeric4}; the signature algorithm is the lower-case hyphenated
 * {@code rsassa-pss}; and {@code hashAlgorithm} sits one level down inside
 * {@code signatureAlgorithmParameters} rather than beside
 * {@code signatureAlgorithm}.
 *
 * <h2>What the RP chooses here, it must honour later</h2>
 * {@code signatureAlgorithm} and {@code hashAlgorithm} are <b>our</b> decision,
 * not SK's — and the signature that comes back is only verifiable with the same
 * pair. Choosing one and verifying with another fails silently and completely,
 * so both are read from {@code SmartIdProperties} by the request builder and
 * again by the validator, rather than restated as constants that can drift.
 *
 * <h2>No {@code initialCallbackUrl}</h2>
 * It is not merely omitted — it is absent from the notification request schema
 * altogether. A callback URL is meaningful only in the same-device flows, where
 * the app has to hand the user back to a browser on the same handset. Its slot
 * in the signed payload is still present and still empty; see the validation
 * package.
 *
 * @param relyingPartyUUID            who is asking. Not a secret, and not authentication in itself —
 *                                    SK pairs it with a source IP registered to the relying party.
 * @param relyingPartyName            shown to the user; must match what SK registered.
 * @param certificateLevel            {@code QUALIFIED} — see {@code SmartIdProperties}.
 * @param signatureProtocol           always {@code ACSP_V2} for authentication. The split is by
 *                                    operation rather than by flow: signing uses
 *                                    {@code RAW_DIGEST_SIGNATURE} and is out of scope here.
 * @param signatureProtocolParameters the challenge and the algorithm choices
 * @param interactions                <b>Base64 of the interactions JSON</b>, not the JSON itself.
 *                                    The exact string sent must be retained, because the signed
 *                                    payload hashes the decoded bytes and re-serialising the same
 *                                    objects can produce different bytes.
 * @param vcType                      {@code numeric4}. Required, even though the verification code
 *                                    is something we compute rather than something SK returns.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationAuthenticationRequest(
        String relyingPartyUUID,
        String relyingPartyName,
        String certificateLevel,
        String signatureProtocol,
        AcspV2RequestParameters signatureProtocolParameters,
        String interactions,
        String vcType
) {

    /** The only signature protocol the authentication endpoints accept. */
    public static final String SIGNATURE_PROTOCOL_ACSP_V2 = "ACSP_V2";

    /** The only verification-code type the API defines. Lower-case, and it matters. */
    public static final String VC_TYPE_NUMERIC4 = "numeric4";

    /**
     * The {@code signatureProtocolParameters} object for an ACSP_V2 request.
     *
     * @param rpChallenge                  Base64 of 32–64 freshly generated random bytes. This is the
     *                                     anti-replay half of the protocol: it is held in our session
     *                                     for the lifetime of the request and compared against the
     *                                     signed payload afterwards. A challenge that is generated
     *                                     but never checked is decoration.
     * @param signatureAlgorithm           {@code rsassa-pss}
     * @param signatureAlgorithmParameters mandatory whenever the algorithm is {@code rsassa-pss}
     */
    public record AcspV2RequestParameters(
            String rpChallenge,
            String signatureAlgorithm,
            HashAlgorithmParameters signatureAlgorithmParameters
    ) {
    }

    /**
     * @param hashAlgorithm one of {@code SHA-256}, {@code SHA-384}, {@code SHA-512},
     *                      {@code SHA3-256}, {@code SHA3-384}, {@code SHA3-512} — hyphenated exactly
     *                      as written
     */
    public record HashAlgorithmParameters(String hashAlgorithm) {
    }
}
