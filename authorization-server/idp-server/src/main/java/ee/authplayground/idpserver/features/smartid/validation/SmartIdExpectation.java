package ee.authplayground.idpserver.features.smartid.validation;

import java.util.Set;

/**
 * What we sent, and therefore what the signed response must contain.
 *
 * <h2>This record is what keeps the validator flow-agnostic</h2>
 * The validator never asks "was this a notification or a QR login". It is handed
 * a response and this set of expectations, and it checks one against the other.
 * Every difference between the flows is expressed here as a <i>value</i> rather
 * than in the validator as a <i>branch</i>:
 * <ul>
 *   <li>notification passes {@code initialCallbackUrl = ""} and
 *       {@code allowedFlowTypes = [Notification]};</li>
 *   <li>Phase 4's QR passes {@code ""} and {@code [QR]};</li>
 *   <li>a same-device flow would pass its real callback URL.</li>
 * </ul>
 * That is the whole reason Phase 4 is cheap, and it is worth defending: the
 * moment the validator takes a "flowType" parameter and switches on it, the
 * shared layer stops being shared.
 *
 * @param schemeName          first slot of the payload — {@code smart-id-demo} on DEMO. Per
 *                            environment, not per flow, but it lives here because the payload
 *                            builder needs it and the payload builder should read one object.
 * @param rpChallengeBase64   the challenge <b>we</b> generated, exactly as sent. Compared into the
 *                            payload; this is the anti-replay check.
 * @param relyingPartyName    as sent, before Base64 encoding
 * @param brokeredRpName      empty for us. The slot exists regardless — see {@link AcspV2Payload}.
 * @param interactionsBase64  the exact Base64 string sent, not a re-serialisation of the objects
 * @param initialCallbackUrl  empty in both cross-device flows. <b>Empty, not absent</b> — the field
 *                            still occupies its slot in the signed payload.
 * @param allowedFlowTypes    which {@code flowType} values this session could legitimately have
 *                            produced. SK's specification requires the RP to check that the returned
 *                            flow type was one it offered — otherwise a response obtained through
 *                            one flow could be presented as though it came from another.
 * @param requiredCertificateLevel minimum assurance, e.g. {@code QUALIFIED}
 * @param expectedIdentifier  the identity this session was addressed to, or {@code null} when the
 *                            flow did not name one up front. Phase 4's anonymous QR passes
 *                            {@code null} — nobody types an identifier, so there is nothing to
 *                            compare and the certificate is the sole source. Notification passes the
 *                            identifier it pushed to, because confirming SK answered about the right
 *                            person costs nothing.
 */
public record SmartIdExpectation(
        String schemeName,
        String rpChallengeBase64,
        String relyingPartyName,
        String brokeredRpName,
        String interactionsBase64,
        String initialCallbackUrl,
        Set<String> allowedFlowTypes,
        String requiredCertificateLevel,
        EtsiSemanticsIdentifier expectedIdentifier
) {

    /** {@code flowType} for the notification flow. Capitalised exactly so; it is signed. */
    public static final String FLOW_TYPE_NOTIFICATION = "Notification";

    /** {@code flowType} for Phase 4's cross-device QR flow. */
    public static final String FLOW_TYPE_QR = "QR";
}
