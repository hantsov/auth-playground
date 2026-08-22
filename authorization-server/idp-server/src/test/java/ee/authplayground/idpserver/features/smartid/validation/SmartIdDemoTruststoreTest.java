package ee.authplayground.idpserver.features.smartid.validation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped DEMO truststore, checked as a build artefact rather than at first
 * boot.
 *
 * <h2>What this actually catches</h2>
 * The truststore is eight certificates downloaded from two hosts and assembled
 * with {@code keytool}. Every failure mode of that process is silent: a
 * truncated download, a file saved as an HTML error page, an alias typo, the
 * wrong environment's CA. None of it shows up until a real Smart-ID login
 * fails — and then it surfaces as "certificate does not chain to a trusted CA",
 * which reads like SK's problem.
 * <p>
 * So the store is verified here as a hierarchy: every issuing CA is checked to
 * actually chain to one of the roots. Five certificates that individually parse
 * prove nothing; five that resolve to the roots we shipped prove the store is
 * coherent.
 */
class SmartIdDemoTruststoreTest {

    private static final String TRUSTSTORE = "/smart-id/demo/smart-id-demo-truststore.p12";

    /** Not a secret. PKCS#12 demands one even when every entry is a public certificate. */
    private static final char[] PASSWORD = "changeit".toCharArray();

    private static Map<String, X509Certificate> certificates;

    @BeforeAll
    static void loadTruststore() throws Exception {
        certificates = new HashMap<>();
        try (InputStream in = SmartIdDemoTruststoreTest.class.getResourceAsStream(TRUSTSTORE)) {
            assertThat(in)
                    .withFailMessage("Truststore missing from the classpath at %s", TRUSTSTORE)
                    .isNotNull();

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, PASSWORD);
            keyStore.aliases().asIterator().forEachRemaining(alias -> {
                try {
                    certificates.put(alias, (X509Certificate) keyStore.getCertificate(alias));
                } catch (Exception e) {
                    throw new IllegalStateException(alias, e);
                }
            });
        }
    }

    @Test
    void containsTheExpectedAnchors() {
        assertThat(certificates).hasSize(8).containsOnlyKeys(
                "test-of-ee-cert-centre-root-ca",
                "test-of-sk-root-g1e",
                "test-of-sk-root-g1r",
                "test-of-eid-sk-2016",
                "test-of-nq-sk-2016",
                "test-of-eid-q-2021e",
                "test-of-eid-q-2024e",
                "test-of-eid-q-2024r");
    }

    /**
     * Each issuing CA must chain to one of the roots we shipped.
     *
     * <h2>Why build a path when the issuers are anchors in production use</h2>
     * At runtime the issuing CAs are themselves trust anchors, so a real login
     * resolves in a single hop and never exercises the link between intermediate
     * and root. That makes a broken or mismatched root invisible — until SK
     * rotates an intermediate and the store suddenly has to bridge a gap it
     * cannot. Anchoring on the roots alone here forces that link to be real
     * today.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "test-of-eid-sk-2016",
            "test-of-nq-sk-2016",
            "test-of-eid-q-2021e",
            "test-of-eid-q-2024e",
            "test-of-eid-q-2024r"
    })
    void everyIssuingCaChainsToAShippedRoot(String alias) throws Exception {
        X509Certificate issuingCa = certificates.get(alias);

        X509CertSelector target = new X509CertSelector();
        target.setCertificate(issuingCa);

        PKIXBuilderParameters parameters = new PKIXBuilderParameters(rootsOnly(), target);
        // Demo certificates are not automatically published to OCSP, and this
        // test must not depend on the network in any case.
        parameters.setRevocationEnabled(false);
        parameters.addCertStore(CertStore.getInstance("Collection",
                new CollectionCertStoreParameters(List.of(issuingCa))));

        assertThat(CertPathBuilder.getInstance("PKIX").build(parameters)).isNotNull();
    }

    /**
     * The production root must not be here.
     *
     * <h2>Not pedantry — SK's own demo app ships it</h2>
     * {@code sid_trust_anchor_certificates.jks} in {@code smart-id-java-demo}
     * bundles the production {@code EE Certification Centre Root CA} next to the
     * test roots. Copying that wholesale would mean production certificates
     * validate against a configuration that is demo in every other respect,
     * quietly erasing the environment boundary the truststore exists to draw.
     */
    @Test
    void excludesProductionRoots() {
        assertThat(certificates.values())
                .extracting(certificate -> certificate.getSubjectX500Principal().getName())
                .allSatisfy(subject -> assertThat(subject).containsIgnoringCase("TEST"));
    }

    /**
     * A certificate that expires unnoticed takes Smart-ID login down with a
     * message pointing at SK. The nearest expiry is 2030, so a year of headroom
     * is a low bar that still fails before anyone is surprised.
     */
    @Test
    void noAnchorIsCloseToExpiry() {
        assertThat(certificates.values())
                .allSatisfy(certificate -> assertThat(certificate.getNotAfter())
                        .as("%s expires", certificate.getSubjectX500Principal())
                        .isAfter(new java.util.Date(System.currentTimeMillis() + 365L * 24 * 3600 * 1000)));
    }

    private static Set<TrustAnchor> rootsOnly() {
        Collection<X509Certificate> roots = new ArrayList<>();
        certificates.forEach((alias, certificate) -> {
            if (alias.contains("root")) {
                roots.add(certificate);
            }
        });
        assertThat(roots).hasSize(3);

        Set<TrustAnchor> anchors = new HashSet<>();
        roots.forEach(root -> anchors.add(new TrustAnchor(root, null)));
        return anchors;
    }
}
