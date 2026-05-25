-- Track when the resource server last mirrored JWT claims into this row.
-- The SPA bootstrap calls POST /api/user/sync after every successful login,
-- and the Token Inspector page surfaces this timestamp so the sync flow is
-- visible to anyone exploring the playground.
ALTER TABLE user_data
    ADD COLUMN last_synced_at TIMESTAMPTZ;
