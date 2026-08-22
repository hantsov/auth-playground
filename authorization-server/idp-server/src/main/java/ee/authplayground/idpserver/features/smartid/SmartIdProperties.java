package ee.authplayground.idpserver.features.smartid;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Everything that differs between Smart-ID environments, in one place.
 *
 * <h2>Why these travel together</h2>
 * {@link #baseUrl}, {@link #schemeName} and {@link #truststore} are not three
 * independent settings — they are three halves of one fact, "which Smart-ID
 * environment is this". Mixing them produces failures that look like anything
 * except a configuration mistake:
 * <ul>
 *   <li>a production truststore against DEMO rejects every certificate chain;</li>
 *   <li>the production {@code schemeName} against DEMO produces a signed payload
 *       whose bytes differ from ours by nine characters, so the signature simply
 *       does not verify — with no field to point at.</li>
 * </ul>
 * That second one is the reason {@code schemeName} is configuration rather than
 * a constant. It is the first slot of the signed ACSP_V2 payload, and on DEMO it
 * is {@code smart-id-demo}, not {@code smart-id}.
 *
 * <h2>What this deliberately does not hold</h2>
 * No client secret, no API key. Smart-ID authenticates relying parties by
 * {@code relyingPartyUUID} plus source IP registered with SK — which is why the
 * demo UUID is a published constant that anyone may use, and why it grants
 * access to demo accounts only.
 *
 * @param baseUrl              server root <b>without</b> the version segment. The API's own paths
 *                             start {@code /v3/}, so a base URL ending in {@code /v3} produces
 *                             {@code /v3/v3/...} and a 404 that reads like the endpoint moved.
 *                             SK's documentation quotes the base as {@code .../smart-id-rp/v3/},
 *                             which is the trap; the OpenAPI {@code servers} entry is
 *                             {@code https://sid.demo.sk.ee/smart-id-rp}.
 * @param relyingPartyUuid     ours is SK's published demo UUID. Not a secret.
 * @param relyingPartyName     shown to the user in the Smart-ID app, in bold, above everything else.
 *                             Must be pre-registered with SK; {@code DEMO} is the registered name for
 *                             the demo environment.
 * @param schemeName           first slot of the signed ACSP_V2 payload. {@code smart-id-demo} on
 *                             DEMO, {@code smart-id} in production. See the class comment.
 * @param certificateLevel     minimum assurance we accept. {@code QUALIFIED} means an eIDAS
 *                             qualified certificate — a state-backed identity rather than a
 *                             self-asserted one, which is the entire reason Smart-ID earns
 *                             {@code acr: strong} where a password gets {@code weak}.
 * @param signatureAlgorithm   we choose this, and SK signs with it. Case-sensitive and lower-case:
 *                             {@code rsassa-pss}. The RSASSA-PKCS1-v1_5 alternatives are deprecated
 *                             in the API and should not be selected here.
 * @param hashAlgorithm        digest over the ACSP_V2 payload. Also our choice, also case-sensitive:
 *                             {@code SHA-512}, hyphenated. <b>Verification must use the same value we
 *                             sent</b>, which is why it is one setting read by both the request
 *                             builder and the validator rather than two constants that can drift.
 * @param pollTimeout          long-poll timeout handed to {@code GET /v3/session/{id}}. SK bounds it
 *                             to 1s–120s. This is not a "how long may the user take" budget — the
 *                             call simply returns {@code RUNNING} and we ask again.
 * @param sessionMaxDuration   how long we keep waiting overall before giving up on the user. This
 *                             <i>is</i> the user-facing budget.
 * @param truststore           SK issuing CA certificates for this environment, validated explicitly
 *                             rather than against the JVM default truststore. A JVM default
 *                             truststore would accept any certificate from any public CA, which for
 *                             an identity assertion is no check at all.
 * @param truststorePassword   PKCS#12 stores require one even though the contents are public
 *                             certificates. Not a secret; do not treat it as one.
 * @param revocationCheckEnabled OCSP/CRL checking. <b>Off by default and that is correct only for
 *                             DEMO</b>, where certificates are not automatically published to OCSP
 *                             and must be uploaded by hand. <b>Production must turn this on</b> — a
 *                             revoked certificate that still validates is exactly the failure mode
 *                             revocation exists to prevent.
 */
@ConfigurationProperties("playground.idp.smart-id")
public record SmartIdProperties(
        String baseUrl,
        String relyingPartyUuid,
        String relyingPartyName,
        String schemeName,
        String certificateLevel,
        String signatureAlgorithm,
        String hashAlgorithm,
        Duration pollTimeout,
        Duration sessionMaxDuration,
        String truststore,
        String truststorePassword,
        boolean revocationCheckEnabled
) {
}
