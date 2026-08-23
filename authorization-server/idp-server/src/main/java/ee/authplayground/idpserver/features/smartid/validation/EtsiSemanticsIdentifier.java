package ee.authplayground.idpserver.features.smartid.validation;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.regex.Pattern;
import javax.security.auth.x500.X500Principal;

/**
 * An ETSI Natural Person Semantics Identifier — {@code PNOEE-40404040009} — and
 * the code that gets one out of a certificate.
 *
 * <h2>The structure, per ETSI EN 319 412-1</h2>
 * A three-character identity type ({@code PNO} for a national personal number,
 * {@code IDC} for an identity card, {@code PAS} for a passport), a two-character
 * upper-case ISO 3166-1 alpha-2 country code, a hyphen, and the identifier
 * itself.
 *
 * <h2>Why the country half is not optional</h2>
 * A national ID is unique <i>within a country</i> and nowhere else. Smart-ID's
 * own demo set makes the point: {@code PNOEE-40404040009} and
 * {@code PNOLT-40404040009} are different people sharing a number. Splitting the
 * identifier and carrying both halves is what lets the user lookup ask a
 * question with exactly one answer.
 *
 * <h2>Not a document number</h2>
 * SK publishes demo accounts as {@code PNOEE-40404040009-MOCK-Q}. That trailing
 * suffix identifies a <i>device</i>, belongs to the document-number endpoints,
 * and is rejected here — it is not part of anyone's identity.
 *
 * @param identityType {@code PNO}, {@code IDC} or {@code PAS}
 * @param country      ISO 3166-1 alpha-2, upper case
 * @param nationalId   the identifier within that country
 */
public record EtsiSemanticsIdentifier(String identityType, String country, String nationalId) {

    /** The identity type this playground issues and expects: a national personal number. */
    public static final String IDENTITY_TYPE_PNO = "PNO";

    /**
     * Anchored on purpose. An unanchored pattern would happily match the
     * {@code PNOEE-40404040009} prefix of a document number and silently accept
     * a device identifier as a person.
     */
    private static final Pattern PATTERN = Pattern.compile("^(PNO|IDC|PAS)([A-Z]{2})-([A-Za-z0-9]+)$");

    /**
     * The subject-DN attribute carrying the identifier. Java's RFC 2253 printer
     * has no keyword for {@code 2.5.4.5}, so without this mapping the attribute
     * renders as a hex-encoded DER blob rather than a readable string.
     */
    private static final Map<String, String> SERIAL_NUMBER_OID = Map.of("2.5.4.5", "SERIALNUMBER");

    private static final String SERIAL_NUMBER_RDN = "SERIALNUMBER";

    public static EtsiSemanticsIdentifier parse(String value) {
        if (value == null) {
            throw new SmartIdValidationException("No semantics identifier present");
        }
        var matcher = PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            throw new SmartIdValidationException(
                    "Not an ETSI natural person semantics identifier: '" + value + "'");
        }
        return new EtsiSemanticsIdentifier(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    /**
     * Builds an identifier from what the user typed on the login form.
     * <p>
     * This is the only place the form's values are trusted, and they are trusted
     * for exactly one thing: addressing the push notification. <b>Identity comes
     * from the certificate, never from here.</b> Someone can type any national ID
     * they like; what they cannot do is produce a signature from the private key
     * held on that person's phone.
     */
    public static EtsiSemanticsIdentifier of(String country, String nationalId) {
        return parse(IDENTITY_TYPE_PNO + country.trim().toUpperCase() + "-" + nationalId.trim());
    }

    /**
     * Pulls the identifier out of a certificate's subject DN, attribute
     * {@code serialNumber} (OID 2.5.4.5).
     *
     * <h2>This is where identity actually comes from</h2>
     * Everything else in the flow — the form field, the session, the push
     * notification — is addressing. The certificate is the assertion: a
     * qualified certificate whose subject the state stands behind, delivered
     * with a signature proving the holder of its private key participated. The
     * national code inside it is the value the user record is resolved against.
     * <p>
     * Read this only <i>after</i> the chain has validated. An unvalidated
     * certificate is an attacker-supplied string that happens to parse.
     */
    public static EtsiSemanticsIdentifier fromCertificate(X509Certificate certificate) {
        X500Principal subject = certificate.getSubjectX500Principal();
        String dn = subject.getName(X500Principal.RFC2253, SERIAL_NUMBER_OID);

        try {
            // LdapName rather than string splitting: DN components may contain
            // escaped commas, and a naive split would slice a name in half.
            for (Rdn rdn : new LdapName(dn).getRdns()) {
                if (SERIAL_NUMBER_RDN.equalsIgnoreCase(rdn.getType())) {
                    return parse(String.valueOf(rdn.getValue()));
                }
            }
        } catch (InvalidNameException e) {
            throw new SmartIdValidationException("Unparseable certificate subject DN: " + dn, e);
        }

        throw new SmartIdValidationException(
                "Certificate subject has no serialNumber (OID 2.5.4.5): " + dn);
    }

    /** The wire form, e.g. {@code PNOEE-40404040009}. */
    public String value() {
        return identityType + country + "-" + nationalId;
    }

    @Override
    public String toString() {
        return value();
    }
}
