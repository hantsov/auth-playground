package ee.authplayground.userdatamaster.features.users.entity;

/**
 * The authentication methods a person can hold a credential for.
 * <p>
 * Persisted as a string rather than an ordinal — an enum's ordering is an
 * implementation detail, and a reordered enum silently rewriting every row's
 * meaning is a classic way to lose a database.
 */
public enum UserCredentialType {

    /**
     * Username + password. The {@code identifier} is the login name and
     * {@code secretHash} is a BCrypt hash.
     * <p>
     * Note what this proves: that the presenter knows a secret. It says nothing
     * about <i>who</i> they are — which is why, from Phase 2 on, a password can
     * only ever be added to an identity that some stronger method already
     * established.
     */
    PASSWORD,

    /**
     * Smart-ID. The {@code identifier} is the ETSI semantics identifier
     * ({@code PNOEE-40404040009}) and {@code secretHash} is NULL — there is no
     * secret on our side to store. The proof is a signature verified against a
     * certificate chain, and the certificate carries a state-issued identity.
     * <p>
     * Wired for real in Phase 2. The type exists now so the schema, the lookup
     * path and the seed data do not need a second migration.
     */
    SMART_ID
}
