package ee.authplayground.idpserver.features.smartid.validation;

import ee.authplayground.idpserver.features.smartid.SmartIdProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a certificate SK handed us is one we are willing to believe.
 *
 * <h2>Why not the JVM default truststore</h2>
 * The default truststore trusts every public CA on the internet. Against it,
 * "this certificate validates" means "somebody, somewhere, was willing to issue
 * it" — which for an identity assertion is no check at all. Anyone able to
 * obtain a certificate from any trusted CA could assert any subject they liked.
 * <p>
 * So the trust anchors are exactly SK's issuing CAs for exactly this
 * environment, loaded from a truststore we ship. <b>The demo and production
 * chains are disjoint</b>: a truststore built for one rejects every certificate
 * from the other, which is why it is scoped by environment alongside the base
 * URL and the scheme name.
 *
 * <h2>Revocation is off, and that is only correct here</h2>
 * SK's demo certificates are not automatically published to OCSP — they have to
 * be uploaded by hand. Leaving revocation on would fail every demo login for a
 * reason unrelated to the code. <b>Production must turn it on.</b> A revoked
 * certificate that still validates is precisely what revocation exists to
 * prevent, and the gap between "works in demo" and "safe in production" is this
 * one flag.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmartIdCertificateValidator {

    /**
     * Assurance levels, weakest first. Only these two are meaningful for
     * authentication — {@code QSCD} is a signing-side distinction.
     */
    private static final List<String> CERTIFICATE_LEVELS = List.of("ADVANCED", "QUALIFIED");

    /** RFC 5280 key usage bit 0: {@code digitalSignature}. */
    private static final int KEY_USAGE_DIGITAL_SIGNATURE = 0;

    /** Standard TLS client authentication EKU, long used by Smart-ID authentication certificates. */
    private static final String EKU_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";

    /** SK's own authentication EKU, present on certificates issued from April 2025. */
    private static final String EKU_SMART_ID_AUTHENTICATION = "1.3.6.1.4.1.62306.5.7.0";

    private final SmartIdProperties properties;
    private final ResourceLoader resourceLoader;

    private Set<TrustAnchor> trustAnchors;

    /**
     * Loads the truststore once at startup rather than per login.
     * <p>
     * Failing here stops the application, which is the intended behaviour: an
     * idp-server that starts with no Smart-ID trust anchors would accept
     * whatever it was sent, and a login page offering a method that cannot
     * possibly be validated is worse than one that admits it is unavailable.
     */
    @PostConstruct
    void loadTrustAnchors() {
        String location = properties.truststore();
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, properties.truststorePassword().toCharArray());

            Set<TrustAnchor> anchors = new HashSet<>();
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (keyStore.getCertificate(alias) instanceof X509Certificate certificate) {
                    anchors.add(new TrustAnchor(certificate, null));
                    log.debug("Smart-ID trust anchor '{}': {}", alias, certificate.getSubjectX500Principal());
                }
            }

            if (anchors.isEmpty()) {
                throw new IllegalStateException("Smart-ID truststore " + location + " contains no certificates");
            }

            this.trustAnchors = Set.copyOf(anchors);
            log.info("Loaded {} Smart-ID trust anchor(s) from {}", anchors.size(), location);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not load the Smart-ID truststore from " + location
                            + ". Smart-ID logins cannot be validated without it.", e);
        }
    }

    /** Decodes the DER+Base64 certificate SK returned. Parsing only — proves nothing. */
    public X509Certificate parse(String base64Der) {
        try {
            byte[] der = Base64.getDecoder().decode(base64Der);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new java.io.ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new SmartIdValidationException("Certificate in session response is unparseable", e);
        }
    }

    /**
     * Runs every certificate check, in the order a failure is cheapest to
     * diagnose.
     *
     * @param certificate    the parsed end-entity certificate
     * @param reportedLevel  {@code cert.certificateLevel} from the response
     */
    public void validate(X509Certificate certificate, String reportedLevel) {
        checkValidityDates(certificate);
        checkChainsToTrustedCa(certificate);
        checkCertificateLevel(reportedLevel);
        checkUsableForAuthentication(certificate);
    }

    /**
     * Checked explicitly as well as by the path validator, because the path
     * validator's message for an expired certificate buries it among generic
     * path-building failures.
     */
    private void checkValidityDates(X509Certificate certificate) {
        try {
            certificate.checkValidity();
        } catch (CertificateExpiredException e) {
            throw new SmartIdValidationException("Smart-ID certificate expired on " + certificate.getNotAfter(), e);
        } catch (CertificateNotYetValidException e) {
            throw new SmartIdValidationException("Smart-ID certificate is not valid until " + certificate.getNotBefore(), e);
        }
    }

    /**
     * Builds and validates a path from the certificate to one of our anchors.
     *
     * <h2>Why a path builder rather than validating a fixed path</h2>
     * SK returns the end-entity certificate <b>alone</b> — no chain. Every
     * intermediate needed to reach a root has to come from somewhere, and the
     * only somewhere available is our own truststore. So the store's contents
     * are handed to the builder twice, in two different roles:
     * <ul>
     *   <li>as <b>trust anchors</b>, the certificates we are willing to stop at;</li>
     *   <li>as an intermediate <b>certificate store</b>, the material the builder
     *       may use to bridge gaps.</li>
     * </ul>
     * With SK's issuing CAs present this usually resolves in a single hop,
     * because the issuer is itself an anchor. Keeping the roots in as well means
     * a longer path also resolves — so adding a new SK intermediate to the store
     * later does not require rethinking any of this.
     *
     * <h2>Trusting intermediates directly is deliberate, and stronger</h2>
     * Anchoring on SK's issuing CAs rather than only on their roots narrows what
     * we accept: a certificate from some other CA under the same root does not
     * validate. That is the opposite of the usual browser posture, and correct
     * here — we are not trying to accept the web, we are trying to accept
     * Smart-ID.
     */
    private void checkChainsToTrustedCa(X509Certificate certificate) {
        try {
            X509CertSelector target = new X509CertSelector();
            target.setCertificate(certificate);

            PKIXBuilderParameters parameters = new PKIXBuilderParameters(trustAnchors, target);
            parameters.setRevocationEnabled(properties.revocationCheckEnabled());
            // The end-entity itself has to be findable by the builder, alongside
            // any intermediates from the truststore.
            parameters.addCertStore(CertStore.getInstance("Collection",
                    new CollectionCertStoreParameters(intermediatesPlus(certificate))));

            CertPathBuilder.getInstance("PKIX").build(parameters);
        } catch (Exception e) {
            throw new SmartIdValidationException(
                    "Smart-ID certificate does not chain to a trusted CA for this environment ("
                            + properties.truststore() + "). Demo and production chains are disjoint.", e);
        }
    }

    private Collection<X509Certificate> intermediatesPlus(X509Certificate endEntity) {
        List<X509Certificate> certificates = new ArrayList<>();
        certificates.add(endEntity);
        trustAnchors.forEach(anchor -> certificates.add(anchor.getTrustedCert()));
        return certificates;
    }

    /**
     * The returned level must be at least what we asked for.
     *
     * <h2>Not an equality check</h2>
     * A certificate stronger than requested is fine; one weaker is not. Comparing
     * for equality would reject the acceptable case, and — worse — an integration
     * that skips this entirely accepts an {@code ADVANCED} certificate where it
     * demanded {@code QUALIFIED}, which is the difference between a self-asserted
     * identity and a state-backed one. That difference is the entire justification
     * for {@code acr: strong}.
     */
    private void checkCertificateLevel(String reportedLevel) {
        int required = CERTIFICATE_LEVELS.indexOf(properties.certificateLevel());
        int actual = reportedLevel == null ? -1 : CERTIFICATE_LEVELS.indexOf(reportedLevel);

        if (actual < 0) {
            throw new SmartIdValidationException("Unrecognised certificate level: " + reportedLevel);
        }
        if (actual < required) {
            throw new SmartIdValidationException(
                    "Certificate level " + reportedLevel + " is below the required " + properties.certificateLevel());
        }
    }

    /**
     * The certificate must be usable for authentication specifically.
     *
     * <h2>Why a certificate is not simply "valid"</h2>
     * A certificate carries what it may be used for. Smart-ID issues separate
     * certificates for authentication and for signing, and accepting either for
     * either purpose would let a signature gathered under "sign this document"
     * be replayed as "log this person in" — the user agreed to one and got the
     * other. The extension checked here is what keeps those apart.
     */
    private void checkUsableForAuthentication(X509Certificate certificate) {
        boolean[] keyUsage = certificate.getKeyUsage();
        if (keyUsage == null || !keyUsage[KEY_USAGE_DIGITAL_SIGNATURE]) {
            throw new SmartIdValidationException("Certificate does not permit digitalSignature key usage");
        }

        try {
            List<String> extendedKeyUsage = certificate.getExtendedKeyUsage();
            if (extendedKeyUsage == null) {
                // Older Smart-ID authentication certificates carry no EKU at all.
                // Key usage above is then the only statement of purpose available,
                // and rejecting these would refuse otherwise valid identities.
                log.debug("Certificate has no extended key usage; relying on key usage alone");
                return;
            }
            boolean usableForAuthentication = extendedKeyUsage.contains(EKU_CLIENT_AUTH)
                    || extendedKeyUsage.contains(EKU_SMART_ID_AUTHENTICATION);
            if (!usableForAuthentication) {
                throw new SmartIdValidationException(
                        "Certificate is not intended for authentication (extended key usage: " + extendedKeyUsage + ")");
            }
        } catch (java.security.cert.CertificateParsingException e) {
            throw new SmartIdValidationException("Could not read the certificate's extended key usage", e);
        }
    }
}
