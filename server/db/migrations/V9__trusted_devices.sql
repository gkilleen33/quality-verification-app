-- Kagua server schema, version 9.
--
-- Remembered browsers, so an admin is not asked for a code every sitting.
--
-- The honest trade-off: a remembered browser means somebody holding an unlocked laptop with
-- this cookie needs only the password, for up to thirty days. That is a real reduction in
-- what 2FA buys, accepted because the alternative — a code every thirty minutes, since the
-- idle timeout is that short — is the kind of friction that ends with the secret written on
-- a sticky note. It is bounded rather than removed: the password is still required every
-- time, the cap is absolute, and every row here can be revoked.

BEGIN;

CREATE TABLE admin_trusted_devices (
    id           uuid PRIMARY KEY,
    admin_id     uuid NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    -- SHA-256 of the cookie value, never the value. 256 bits of CSPRNG output has no
    -- dictionary to attack, so the reasoning is the same as refresh_tokens: a slow hash
    -- would buy nothing and cost a hash on every sign-in.
    token_hash   char(64) NOT NULL UNIQUE,
    -- Whatever the browser called itself, to make the list on the page mean something.
    -- Descriptive only: it is supplied by the client and is never part of the check.
    label        text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    expires_at   timestamptz NOT NULL
);

CREATE INDEX admin_trusted_devices_admin_idx ON admin_trusted_devices (admin_id);
CREATE INDEX admin_trusted_devices_expiry_idx ON admin_trusted_devices (expires_at);

-- Expired rows are dead weight and a small liability; the nightly job already exists.
CREATE OR REPLACE FUNCTION purge_expired_trusted_devices()
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    removed integer;
BEGIN
    WITH gone AS (
        DELETE FROM admin_trusted_devices
        WHERE expires_at < now()
        RETURNING 1
    )
    SELECT count(*) INTO removed FROM gone;
    RETURN removed;
END;
$$;

CREATE OR REPLACE FUNCTION purge_expired()
RETURNS text
LANGUAGE sql
AS $$
    SELECT 'sessions=' || purge_deleted_sessions('7 days'::interval)
        || ' accounts=' || purge_deleted_accounts('30 days'::interval)
        || ' tokens='   || purge_expired_tokens('30 days'::interval)
        || ' devices='  || purge_expired_trusted_devices();
$$;

INSERT INTO schema_migrations (version) VALUES ('V9__trusted_devices')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
