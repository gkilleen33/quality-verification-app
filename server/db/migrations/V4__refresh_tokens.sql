-- Kagua server schema, version 4.
--
-- Refresh tokens, so a phone does not have to hold a long-lived credential that
-- cannot be revoked. Access tokens are short and stateless; this table is the part
-- we can actually take away from a lost handset.

BEGIN;

CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- SHA-256 of the token, never the token. Deliberately not Argon2: these are 256
    -- bits of CSPRNG output, so there is no dictionary to slow down, and hashing them
    -- expensively on every refresh would spend CPU we do not have on a 2-vCPU box for
    -- no attacker cost. Argon2 is for things humans chose.
    token_hash  char(64) NOT NULL UNIQUE,
    issued_at   timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    -- Set when this token is spent. Rotation means every refresh mints a new token and
    -- retires the one used, so a token seen twice is either a replay or a theft.
    used_at     timestamptz,
    revoked_at  timestamptz,
    -- What replaced it, so a replayed token identifies the chain to revoke.
    replaced_by uuid REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    -- Free-text note from the client, e.g. the handset model. Diagnostics only.
    user_agent  text
);

CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
-- The purge below scans this.
CREATE INDEX refresh_tokens_expiry_idx ON refresh_tokens (expires_at);

/*
 * Revokes every live token for a user. Called when a retired token is presented
 * again: either somebody replayed a request, or a token was stolen and used behind
 * the legitimate holder's back. We cannot tell which from here, and the safe reading
 * of an ambiguous signal is that the account is compromised — so everything goes and
 * the user signs in again. Annoying once, versus an attacker holding a rotating
 * credential indefinitely.
 */
CREATE OR REPLACE FUNCTION revoke_refresh_chain(target uuid)
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    revoked integer;
BEGIN
    WITH gone AS (
        UPDATE refresh_tokens
        SET revoked_at = now()
        WHERE user_id = target AND revoked_at IS NULL
        RETURNING 1
    )
    SELECT count(*) INTO revoked FROM gone;
    RETURN revoked;
END;
$$;

-- Expired and long-since-spent tokens are dead weight, not history: unlike
-- usage_events they answer no question after the fact.
CREATE OR REPLACE FUNCTION purge_expired_tokens(grace interval DEFAULT '30 days')
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    removed integer;
BEGIN
    WITH gone AS (
        DELETE FROM refresh_tokens
        WHERE expires_at < now() - grace
        RETURNING 1
    )
    SELECT count(*) INTO removed FROM gone;
    RETURN removed;
END;
$$;

-- Fold it into the nightly job.
CREATE OR REPLACE FUNCTION purge_expired()
RETURNS text
LANGUAGE sql
AS $$
    SELECT 'sessions=' || purge_deleted_sessions('7 days'::interval)
        || ' accounts=' || purge_deleted_accounts('30 days'::interval)
        || ' tokens='   || purge_expired_tokens('30 days'::interval);
$$;

INSERT INTO schema_migrations (version) VALUES ('V4__refresh_tokens')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
