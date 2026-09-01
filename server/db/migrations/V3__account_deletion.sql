-- Kagua server schema, version 3.
--
-- Account deletion, retained 30 days. Distinct from the 7 days in V2, which covers
-- a single assessment the customer deleted; this covers the whole account going.
-- The longer window is deliberate: deleting an account is the action somebody is
-- most likely to regret or to have taken by accident, and the only recovery is a
-- copy we still hold.

BEGIN;

-- Set when the customer closes their account. Everything of theirs stays until the
-- purge, and sync returns nothing for a user with this set.
ALTER TABLE users ADD COLUMN deleted_at timestamptz;

CREATE INDEX users_deleted_idx ON users (deleted_at) WHERE deleted_at IS NOT NULL;

-- Sessions, messages and attachments go with the user by cascade.
--
-- usage_events deliberately do not: their user_id is ON DELETE SET NULL, so the
-- billing record survives the account it belonged to. What we spent is our own
-- history, not the customer's personal data, and losing it would make the invoice
-- unauditable.
CREATE OR REPLACE FUNCTION purge_deleted_accounts(retention interval DEFAULT '30 days')
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    removed integer;
BEGIN
    WITH gone AS (
        DELETE FROM users
        WHERE deleted_at IS NOT NULL
          AND deleted_at < now() - retention
        RETURNING 1
    )
    SELECT count(*) INTO removed FROM gone;
    RETURN removed;
END;
$$;

-- One entry point for the timer, so the retention windows live here in version
-- control rather than in a systemd unit somebody edits on the box.
CREATE OR REPLACE FUNCTION purge_expired()
RETURNS text
LANGUAGE sql
AS $$
    SELECT 'sessions=' || purge_deleted_sessions('7 days'::interval)
        || ' accounts=' || purge_deleted_accounts('30 days'::interval);
$$;

INSERT INTO schema_migrations (version) VALUES ('V3__account_deletion')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
