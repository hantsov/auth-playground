-- The golden record: identity + person attributes, split from credentials.
--
-- Two tables. `users` answers "who is this person"; `user_credentials` answers
-- "what did we issue them to prove it with".
--
-- The split is not symmetric, and understanding why is the point of this file.
-- Authentication methods come in two shapes:
--
--   INHERENT — usable because of *who you are*. Some external authority holds
--   the authenticator and has already done the identity proofing; we only need
--   the identifier on the person row. Smart-ID is the example: the state issued
--   the identity, SK holds the key, and users.national_id is the entire
--   binding. Nothing to enrol, nothing to store, nothing for us to revoke.
--
--   ISSUED — usable because *we gave you something*. We hold a secret, so there
--   is a row, an enrolment step, and a revocation lever. Passwords today; TOTP
--   or WebAuthn would land here too.
--
-- user_credentials is the table of ISSUED credentials. The test for any method
-- added later: does authenticating require something we store? Then it is a
-- row here. If not, it is an attribute on users.
--
-- So a Smart-ID-only person has ZERO credential rows. That is exactly what a
-- separate table buys and what a users.password_hash column could never
-- express — a nullable column would conflate "no password yet" with
-- "authenticates by other means".

CREATE TABLE users (
    -- Minted here, and this UUID becomes the `sub` claim everywhere downstream:
    -- idp-server asserts it, Keycloak links its shadow user to it, and
    -- resource-backend keys its rows on it. It is the only identifier in the
    -- system that is stable by construction — everything else on this row is
    -- a mutable display or contact attribute.
    id             UUID PRIMARY KEY,

    -- The bare national identity code, e.g. '40404040009'.
    --
    -- This is a person attribute AND the binding for every inherent method.
    -- Smart-ID authentication resolves against it: the certificate's subject DN
    -- carries the ETSI semantics identifier ('PNOEE-40404040009'), which is
    -- split into country + code and matched against (nationality, national_id).
    -- The combined form is derived when needed, never stored — there is no
    -- credential row for it to live on.
    --
    -- Nullable: not every person in the system necessarily has one. A person
    -- with no national_id simply cannot use an inherent method.
    national_id    VARCHAR(50),

    -- ISO 3166-1 alpha-2, e.g. 'EE'. Carries the country half of the ETSI
    -- identifier, and — see the UNIQUE constraint at the bottom — is what makes
    -- national_id uniqueness correct rather than merely plausible.
    --
    -- VARCHAR rather than CHAR: Postgres blank-pads CHAR, and a padded country
    -- code silently stops matching the one you searched for.
    --
    -- Strictly, ETSI's country is the *issuing country of the identity
    -- document*, which is not universally the same thing as nationality. For
    -- this playground they coincide; the distinction is noted rather than
    -- modelled.
    nationality    VARCHAR(2),

    -- A display handle, and nothing more. It used to be the login identifier;
    -- that role now belongs to user_credentials.identifier on the PASSWORD row.
    -- This is free to change without breaking anything, which is exactly what
    -- was NOT true before this refactor.
    username       VARCHAR(100) UNIQUE,

    -- Deliberately NOT UNIQUE, and never a join key. OIDC Core 5.7 makes
    -- `sub` + `iss` the only claims a relying party may rely on as a stable
    -- identifier. Auto-linking accounts on a matching email address is a
    -- documented account-takeover primitive: a Smart-ID user types an email
    -- into a form with no mailbox verification, and joining on it would let
    -- anyone holding any Smart-ID inherit an existing account.
    --
    -- Collect it. Never join on it. The schema should make the wrong thing
    -- hard, not merely discouraged.
    email          VARCHAR(255),

    -- A real column because it is a real fact. idp-server used to emit
    -- `email_verified: true` as a hardcoded literal, which was harmless only
    -- while every address was a seeded fixture. The moment an address arrives
    -- from a form it becomes an assertion nobody performed.
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,

    given_name     VARCHAR(100),
    family_name    VARCHAR(100),

    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Composite, NOT a bare UNIQUE (national_id). National identity numbers are
    -- unique *within a country*, not globally, and Smart-ID's own demo set
    -- proves it: PNOEE-40404040009 and PNOLT-40404040009 are different people
    -- sharing a number. A single-column constraint would collide the first time
    -- anyone seeded a non-Estonian test identity.
    --
    -- This is also what stops two people binding the same state identity. Let
    -- it reject rather than checking first: a read-then-write leaves a race,
    -- and this is the one table where losing it means one human's identity
    -- attached to another's account.
    UNIQUE (nationality, national_id)
);

CREATE TABLE user_credentials (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- PASSWORD is the only value today, and the column still earns its place:
    -- it is the discriminator for future ISSUED methods (TOTP, WebAuthn), and
    -- it is half of the UNIQUE index below.
    --
    -- Deliberately a plain string rather than a Postgres enum: adding an
    -- authentication method should be an INSERT, not a DDL migration
    -- coordinated across services.
    type        VARCHAR(32) NOT NULL,

    -- What the person presents to identify themselves under this method. For
    -- PASSWORD that is the login name.
    --
    -- Note this is deliberately NOT users.username, even though they are equal
    -- for seeded users. The handle on the person row is for display and may
    -- change freely; this is the login key and does not.
    identifier  VARCHAR(255) NOT NULL,

    -- BCrypt for PASSWORD. Nullable so a future issued method that stores
    -- something other than a comparable secret has somewhere to go — but see
    -- the CHECK below: a PASSWORD row without a hash is a broken row, not a
    -- user who "has no password", and the database refuses it.
    secret_hash VARCHAR(255),

    -- Per-credential, deliberately distinct from users.enabled. Revoking one
    -- issued method is not the same act as disabling a person.
    --
    -- There is no equivalent lever for inherent methods, and that is correct
    -- rather than a gap: we did not issue a Smart-ID certificate and we cannot
    -- revoke one. SK does that, and our certificate-chain and OCSP checks catch
    -- it. Locally the levers are users.enabled or clearing the identifier.
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Makes credential lookup a single indexed read for every issued method,
    -- present and future.
    UNIQUE (type, identifier),

    -- The rule that keeps `secret_hash` honest while leaving it nullable.
    -- Enforced here rather than in application code because a constraint holds
    -- for every writer, including the psql session someone opens at 2am.
    CONSTRAINT password_requires_secret
        CHECK (type <> 'PASSWORD' OR secret_hash IS NOT NULL)
);

CREATE INDEX idx_user_credentials_user_id ON user_credentials (user_id);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
