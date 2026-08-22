package ee.authplayground.idpserver.features.smartid.validation;

import java.security.cert.X509Certificate;

/**
 * An identity that has survived every check — the output of the validation
 * layer and the only thing downstream code is allowed to act on.
 *
 * <h2>Why this type exists at all</h2>
 * The validator could return the raw certificate and let callers read what they
 * like out of it. Then "did anyone verify this?" would be answerable only by
 * tracing call sites. A distinct type that <i>cannot be constructed without
 * going through validation</i> makes the guarantee visible in the signature of
 * every method that takes one.
 * <p>
 * It is deliberately not a {@code UserDetails} and carries no user record. This
 * is who Smart-ID says the person is; whether we know them is a separate
 * question, asked of the user data master afterwards, and answered differently
 * in this phase (reject) than in Phase 3 (register).
 *
 * @param identifier       the ETSI identifier from the certificate's subject DN. Split into country
 *                         and national ID, this is what the person lookup keys on.
 * @param certificate      kept for logging and for anything later that needs to inspect the
 *                         assertion. Nothing downstream should re-derive identity from it — that
 *                         has been done, here, once.
 * @param certificateLevel the assurance level actually presented, already checked to be at least
 *                         what we required. Retained because it is the fact behind {@code acr}.
 * @param documentNumber   which of the person's devices answered, e.g.
 *                         {@code PNOEE-40404040009-MOCK-Q}. Unused in this phase; Phase 4 targets
 *                         re-authentication at a specific device with it.
 */
public record ValidatedSmartIdIdentity(
        EtsiSemanticsIdentifier identifier,
        X509Certificate certificate,
        String certificateLevel,
        String documentNumber
) {
}
