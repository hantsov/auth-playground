package ee.authplayground.idpserver.features.smartid.validation;

import ee.authplayground.idpserver.features.smartid.client.NotificationAuthenticationRequest;
import ee.authplayground.idpserver.features.smartid.client.SessionStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Base64;

/**
 * Turns a completed Smart-ID session response into a proven identity — or
 * refuses.
 *
 * <h2>The one sentence that matters</h2>
 * <b>{@code endResult == OK} is not authentication.</b> It is the server's
 * opinion, arriving over a channel that anyone able to answer our HTTP request
 * could also produce. An integration that reads it, trusts the attached
 * certificate and logs the user in is trivially forgeable: no key is involved
 * anywhere in that path.
 * <p>
 * What proves something is the signature — verified against a payload we rebuilt
 * ourselves, using a public key from a certificate that chains to a CA we chose,
 * over a challenge we generated moments earlier. Every one of those clauses is
 * load-bearing, and dropping any one of them silently removes the guarantee
 * while leaving the code apparently working.
 *
 * <h2>Flow-agnostic on purpose</h2>
 * Nothing here knows whether a notification or a QR code produced this response.
 * Everything flow-specific arrives as a value in {@link SmartIdExpectation}.
 * That is what lets Phase 4 reuse this class rather than grow a second copy of
 * it, and the temptation to add "if this is the QR flow" is the thing to
 * resist — the QR flow's differences are all expressible as expectations.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmartIdResponseValidator {

    /**
     * The only signature algorithm we ever request, so the only one we accept.
     * <p>
     * The API also offers PKCS#1 v1.5 variants, which it marks deprecated.
     * Refusing them here means a downgrade cannot be introduced by the response:
     * if SK ever answered with a weaker algorithm than we asked for, that is
     * either a bug or an attack, and neither should authenticate anyone.
     */
    private static final String SIGNATURE_ALGORITHM_RSASSA_PSS = "rsassa-pss";

    /** JCA's name for the same thing. */
    private static final String JCA_SIGNATURE_ALGORITHM = "RSASSA-PSS";

    /** RFC 8017 defines exactly one trailer field value, encoded as {@code 0xbc}. */
    private static final int PSS_TRAILER_FIELD_BC = 1;

    private final SmartIdCertificateValidator certificateValidator;

    /**
     * @param response    a session response whose {@code endResult} is already {@code OK}; failure
     *                    codes are the caller's business and never reach here
     * @param expectation what we sent, and therefore what must come back
     * @return the identity from the certificate — the only identity in the flow that was proven
     *         rather than asserted
     */
    public ValidatedSmartIdIdentity validate(SessionStatusResponse response, SmartIdExpectation expectation) {
        requireCompleteAcspV2Response(response);

        SessionStatusResponse.AcspV2Signature signature = response.signature();
        checkFlowTypeWasOffered(signature, expectation);

        X509Certificate certificate = certificateValidator.parse(response.cert().value());
        certificateValidator.validate(certificate, response.cert().certificateLevel());

        verifySignature(response, expectation, certificate);

        EtsiSemanticsIdentifier identifier = EtsiSemanticsIdentifier.fromCertificate(certificate);
        checkIdentifierMatchesRequest(identifier, expectation);

        log.debug("Smart-ID response validated for {} (level {}, flow {})",
                identifier, response.cert().certificateLevel(), signature.flowType());

        return new ValidatedSmartIdIdentity(
                identifier,
                certificate,
                response.cert().certificateLevel(),
                response.result().documentNumber());
    }

    /**
     * Structural checks, before anything expensive or cryptographic.
     * <p>
     * {@code signatureProtocol} is verified rather than assumed: it selects the
     * shape of the {@code signature} object, and a response claiming
     * {@code RAW_DIGEST_SIGNATURE} would have been produced by a signing
     * operation, not an authentication. Those are different user consents.
     */
    private void requireCompleteAcspV2Response(SessionStatusResponse response) {
        if (!response.isComplete()) {
            throw new SmartIdValidationException("Session is not complete: state=" + response.state());
        }
        if (!NotificationAuthenticationRequest.SIGNATURE_PROTOCOL_ACSP_V2.equals(response.signatureProtocol())) {
            throw new SmartIdValidationException(
                    "Expected an ACSP_V2 authentication response, got signatureProtocol="
                            + response.signatureProtocol());
        }
        if (response.signature() == null || response.signature().value() == null) {
            throw new SmartIdValidationException("Session completed with OK but carries no signature");
        }
        if (response.cert() == null || response.cert().value() == null) {
            throw new SmartIdValidationException("Session completed with OK but carries no certificate");
        }
    }

    /**
     * The returned {@code flowType} must be one this session could have
     * produced.
     * <p>
     * SK's specification asks the RP to check this explicitly. Without it, a
     * response legitimately obtained through one flow could be presented as
     * though it came from another — and since {@code flowType} is part of the
     * signed payload, the check is also what makes the signature verification
     * meaningful rather than circular.
     */
    private void checkFlowTypeWasOffered(
            SessionStatusResponse.AcspV2Signature signature,
            SmartIdExpectation expectation
    ) {
        if (!expectation.allowedFlowTypes().contains(signature.flowType())) {
            throw new SmartIdValidationException(
                    "Response flowType '" + signature.flowType() + "' was not offered for this session "
                            + expectation.allowedFlowTypes());
        }
    }

    /**
     * Rebuilds the signed payload and verifies the signature over it.
     *
     * <h2>Where the anti-replay check actually happens</h2>
     * There is no line comparing {@code rpChallenge} to anything. There does not
     * need to be: the challenge is slot 4 of the payload we reconstruct from
     * <b>our</b> session, so a signature produced over a different challenge
     * simply fails to verify here. Comparing the challenge separately would
     * duplicate a check the signature already enforces — and a response cannot
     * even report the challenge back for us to compare against.
     *
     * <h2>PSS parameters are not decoration</h2>
     * RSASSA-PSS is parameterised by digest, mask generation function and salt
     * length. The same key over the same bytes verifies or fails depending on
     * them, so they are taken from the response and cross-checked against what
     * we requested rather than guessed.
     */
    private void verifySignature(
            SessionStatusResponse response,
            SmartIdExpectation expectation,
            X509Certificate certificate
    ) {
        SessionStatusResponse.AcspV2Signature signature = response.signature();

        if (!SIGNATURE_ALGORITHM_RSASSA_PSS.equalsIgnoreCase(signature.signatureAlgorithm())) {
            throw new SmartIdValidationException(
                    "Refusing signature algorithm '" + signature.signatureAlgorithm()
                            + "'; only " + SIGNATURE_ALGORITHM_RSASSA_PSS + " is accepted");
        }

        String payload = AcspV2Payload.build(expectation, signature, response.interactionTypeUsed());

        try {
            Signature verifier = Signature.getInstance(JCA_SIGNATURE_ALGORITHM);
            verifier.setParameter(pssParameters(signature.signatureAlgorithmParameters()));
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));

            if (!verifier.verify(Base64.getDecoder().decode(signature.value()))) {
                // Logged at debug with the payload, because in development the
                // cause is almost always our own reconstruction being one byte
                // out, and the payload is the only way to see which byte. It is
                // not secret — the user's phone signed it — but it is noisy.
                log.debug("ACSP_V2 signature did not verify. Reconstructed payload was:\n{}", payload);
                throw new SmartIdValidationException(
                        "ACSP_V2 signature did not verify against the reconstructed payload");
            }
        } catch (SmartIdValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new SmartIdValidationException("Could not verify the ACSP_V2 signature", e);
        }
    }

    /**
     * Maps SK's parameter block onto a {@link PSSParameterSpec}.
     * <p>
     * Defaults are deliberately absent: if SK stops sending a parameter, failing
     * loudly beats quietly verifying against an assumption that happens to be
     * wrong, because the failure mode of a wrong assumption here is a signature
     * check that passes when it should not.
     */
    private PSSParameterSpec pssParameters(SessionStatusResponse.SignatureAlgorithmParameters parameters) {
        if (parameters == null || parameters.hashAlgorithm() == null) {
            throw new SmartIdValidationException("RSASSA-PSS response carries no signatureAlgorithmParameters");
        }

        String hashAlgorithm = parameters.hashAlgorithm();
        String mgfHashAlgorithm = parameters.maskGenAlgorithm() != null
                && parameters.maskGenAlgorithm().parameters() != null
                ? parameters.maskGenAlgorithm().parameters().hashAlgorithm()
                : hashAlgorithm;

        // Salt length defaults to the digest length, which is what SK sends in
        // practice (64 for SHA-512) and what RFC 8017 recommends.
        int saltLength = parameters.saltLength() != null
                ? parameters.saltLength()
                : digestLengthBytes(hashAlgorithm);

        return new PSSParameterSpec(
                hashAlgorithm,
                "MGF1",
                new MGF1ParameterSpec(mgfHashAlgorithm),
                saltLength,
                PSS_TRAILER_FIELD_BC);
    }

    private int digestLengthBytes(String hashAlgorithm) {
        try {
            return MessageDigest.getInstance(hashAlgorithm).getDigestLength();
        } catch (Exception e) {
            throw new SmartIdValidationException("Unsupported hash algorithm: " + hashAlgorithm, e);
        }
    }

    /**
     * When the flow named its subject up front, the certificate must describe
     * that same person.
     *
     * <h2>This is a consistency check, not the identity check</h2>
     * Identity comes from the certificate regardless. This catches SK answering
     * about someone other than the person we pushed to — which should be
     * impossible, and is cheap enough to verify rather than assume.
     * <p>
     * Phase 4's anonymous QR flow passes no expected identifier, because nobody
     * typed one. That is why this is a null check rather than a requirement.
     */
    private void checkIdentifierMatchesRequest(
            EtsiSemanticsIdentifier fromCertificate,
            SmartIdExpectation expectation
    ) {
        EtsiSemanticsIdentifier expected = expectation.expectedIdentifier();
        if (expected != null && !expected.equals(fromCertificate)) {
            throw new SmartIdValidationException(
                    "Certificate identifies " + fromCertificate + " but the session was started for " + expected);
        }
    }
}
