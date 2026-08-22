-- The golden record: identity + person attributes, split from credentials.
--
-- Two tables, and the split between them is the whole point. `users` answers
-- "who is this person"; `user_credentials` answers "what did they present to
-- prove it". One human, one `users` row, N ways to authenticate.

CREATE TABLE users (
    -- Minted here, and this UUID becomes the `sub` claim everywhere downstream:
    -- idp-server asserts it, Keycloak links its shadow user to it, and
    -- resource-backend keys its rows on it. It is the only identifier in the
    -- system that is stable by construction — everything else on this row is
    -- a mutable display or contact attribute.
    id             UUID PRIMARY KEY,

    -- The bare national identity code, e.g. '40404040009'. NOT the ETSI
    -- semantics identifier — that lives on the SMART_ID credential row and is
    -- derived as 'PNO' || nationality || '-' || national_id.
    --
    -- Nullable: not every person in the system necessarily has one.
    national_id    VARCHAR(50),

    -- ISO 3166-1 alpha-2, e.g. 'EE'. Carries the country half of the ETSI
    -- identifier, and — see the UNIQUE constraint at the bottom — is what makes
    -- national_id uniqueness correct rather than merely plausible.
    --
    -- Strictly, ETSI's country is the *issuing country of the identity
    -- document*, which is not universally the same thing as nationality. For
    -- this playground they coincide; the distinction is noted rather than
    -- modelled.
    -- VARCHAR rather than CHAR: Postgres blank-pads CHAR, and a padded country
    -- code silently stops matching the one you searched for.
    nationality    VARCHAR(2),

    -- A display handle, and nothing more. It used to be the login identifier;
    -- that role now belongs to user_credentials.identifier on the PASSWORD row.
    -- This is free to change without breaking anything, which is exactly what
    -- was NOT true before this refactor.
    username       VARCHAR(100) UNIQUE,

    -- Deliberately NOT UNIQUE, and never a join key. OIDC Core 5.7 makes
    -- `sub` + `iss` the only claims a relying party may rely on as a stable
    -- identifier. Auto-linking accounts on a matching email address is a
    -- documented account-takeover primitive: in Phase 2 a Smart-ID user types
    -- an email into a form with no mailbox verification, and joining on it
    -- would let anyone holding any Smart-ID inherit an existing account.
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
    UNIQUE (nationality, national_id)
);

CREATE TABLE user_credentials (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- PASSWORD | SMART_ID. Deliberately a plain string rather than a Postgres
    -- enum: adding an authentication method should be an INSERT, not a DDL
    -- migration coordinated across services.
    type        VARCHAR(32) NOT NULL,

    -- What the human presents to identify themselves under this method:
    --   PASSWORD  -> the login name, e.g. 'conan'
    --   SMART_ID  -> the ETSI semantics identifier, e.g. 'PNOEE-40404040009'
    --
    -- For SMART_ID this is the same national ID that appears on users.national_id,
    -- in a different role: there it is a person attribute compliance reads, here
    -- it is the index answering "who just authenticated". Stored in derived form
    -- so the lookup is one indexed read rather than a reassembly per request.
    identifier  VARCHAR(255) NOT NULL,

    -- BCrypt for PASSWORD; NULL for SMART_ID, which has no secret to store —
    -- the proof is a signature over a challenge, verified against a certificate
    -- chain, and nothing about it is retained here.
    secret_hash VARCHAR(255),

    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- This is what makes credential lookup a single indexed read for every
    -- authentication method, present and future. Both login paths become the
    -- same query with a different `type`.
    UNIQUE (type, identifier)
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
