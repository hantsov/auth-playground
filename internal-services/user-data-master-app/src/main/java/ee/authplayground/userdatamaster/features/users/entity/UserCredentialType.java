package ee.authplayground.userdatamaster.features.users.entity;

/**
 * The <b>issued</b> authentication methods — the ones that exist because we
 * gave the person something.
 *
 * <h2>Why Smart-ID is not in this enum</h2>
 * Authentication methods come in two shapes, and only one of them needs a row.
 * <ul>
 *   <li><b>Inherent</b> — usable because of <i>who you are</i>. An external
 *       authority holds the authenticator and has already done the identity
 *       proofing; we only need the identifier on the person record. Smart-ID is
 *       the example: the state issued the identity, SK holds the key, and
 *       {@code users.national_id} is the entire binding. There is nothing to
 *       enrol, nothing to store, and nothing for us to revoke.</li>
 *   <li><b>Issued</b> — usable because <i>we gave you something</i>. We hold a
 *       secret, so there is a row, an enrolment step, and a revocation
 *       lever.</li>
 * </ul>
 * Smart-ID is inherent, so it has no credential row and no constant here. An
 * earlier draft of this schema gave it one, keyed on the ETSI semantics
 * identifier — but that identifier is derivable from
 * {@code users.nationality + users.national_id}, so the row was duplication
 * with a synchronisation problem attached, and it implied an enrolment step
 * that does not exist. If your user data carries a national ID and you hold a
 * Smart-ID account, you can authenticate. Nothing is opted into.
 * <p>
 * <b>The test for anything added later:</b> does authenticating require
 * something we store? Then it belongs here. If not, it is an attribute on
 * {@code users}.
 * <p>
 * Persisted as a string rather than an ordinal — a reordered enum silently
 * rewriting the meaning of every row is a well-known way to lose a database.
 */
public enum UserCredentialType {

    /**
     * Username + password. The {@code identifier} is the login name and
     * {@code secretHash} is a BCrypt hash — enforced by the
     * {@code password_requires_secret} CHECK constraint, so a PASSWORD row can
     * never exist without one.
     * <p>
     * Note what this proves: that the presenter knows a secret. It says nothing
     * about <i>who</i> they are — which is why, from Phase 2 on, a password can
     * only ever be added to an identity that some inherent method already
     * established. It is a legacy-and-convenience method, not a peer of
     * Smart-ID.
     * <p>
     * TOTP and WebAuthn would be the natural next constants: both issued, both
     * with something to store, both revocable per-credential.
     */
    PASSWORD
}
