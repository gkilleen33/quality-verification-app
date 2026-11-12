-- Kagua server schema, version 27.
--
-- Settings the server reads while it runs, per app, with a history of every change.
--
-- WHY: the terms gate (sign-up plan v3, step 1) has to be switched on for Fundi Bora at a
-- moment chosen after the release that shows the terms screen is on every phone, and
-- switched off again at once if that goes wrong. An environment variable needs a restart
-- and leaves no record of who changed it or when; a migration per change needs a PR, a
-- review and a deploy, which is far too slow to turn a gate off in an emergency. So the
-- values live in a table that the server reads with a short cache, and every change is
-- recorded beside it.
--
-- Two keys so far, each a closed vocabulary with a CHECK on its values: terms_gate (on or
-- off) and terms_version (the label of the terms text an account must have agreed to).
-- Later settings (sign-up plan v3, section 2.6) arrive with the code that reads them, each
-- by a migration that replaces app_settings_key_known and app_settings_value_valid, as V20
-- replaced the ownership CHECKs. The app versions stay in the environment: AppVersions.kt
-- says why.
--
-- HOW A SETTING CHANGES
--
-- Until the portal's settings page exists (sign-up plan v3, row 4a), Nathan changes one
-- with server/db/set-setting.sh, run as `bash /opt/kagua/db/set-setting.sh ...` over SSM,
-- each command approved. The script sets kagua.changed_by and kagua.reason for its own
-- transaction and runs one statement.
--
-- No path can change a setting without leaving a history row, because the history is
-- written by a trigger, not by the code that makes the change. The trigger takes who made
-- the change from kagua.changed_by, a value the SQL session sets for itself with
-- SET LOCAL, and refuses the change when it is unset. The seeds below go through the same
-- trigger, as 'V27'. A setting is never deleted, only switched by its value, and the
-- history is append-only: triggers refuse UPDATE, DELETE and TRUNCATE on it.
--
-- No personal data in either table: an app, a key, a value, a time, and the name of the
-- person or migration that made the change. So anonymise_user is unchanged.
--
-- The server tests have no database, so the triggers are proved by the self-test at the
-- end of this file, which tools/deploy.sh runs in its rolled-back rehearsal before
-- anything is applied.

BEGIN;

CREATE TABLE app_settings (
    -- Never defaulted, as users.audience is never defaulted on insert: a setting written for
    -- the wrong app would change the other app's phones.
    audience    text NOT NULL,
    key         text NOT NULL,
    value       text NOT NULL,
    -- Both written by app_settings_stamp from the transaction's own kagua.changed_by, so a
    -- caller cannot record somebody else as the author.
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  text NOT NULL,
    -- Also the index the server's one lookup per app uses.
    PRIMARY KEY (audience, key),
    CONSTRAINT app_settings_audience_known
        CHECK (audience IN ('buyer', 'fundi')),
    CONSTRAINT app_settings_key_known
        CHECK (key IN ('terms_gate', 'terms_version')),
    -- One clause per key. A value outside it is refused at the moment it is typed, rather
    -- than read later as "off" by a server that cannot make sense of it.
    CONSTRAINT app_settings_value_valid
        CHECK ((key = 'terms_gate' AND value IN ('on', 'off'))
               OR (key = 'terms_version' AND length(btrim(value)) > 0))
);

CREATE TABLE app_settings_history (
    id          bigserial PRIMARY KEY,
    audience    text NOT NULL,
    key         text NOT NULL,
    -- Null for the row that created the setting: there was nothing before it.
    old_value   text,
    new_value   text NOT NULL,
    changed_at  timestamptz NOT NULL DEFAULT now(),
    changed_by  text NOT NULL,
    -- Why, from kagua.reason. Nullable, because a migration's reason is its header comment.
    reason      text,
    -- The preset that made the change, once the settings page has presets (sign-up plan
    -- v3, row 4a). Null for a change made one setting at a time.
    preset      text,
    CONSTRAINT app_settings_history_audience_known
        CHECK (audience IN ('buyer', 'fundi')),
    CONSTRAINT app_settings_history_key_known
        CHECK (key IN ('terms_gate', 'terms_version'))
);

-- "What was terms_gate for Fundi Bora on the 3rd": one app and key, in time order.
CREATE INDEX app_settings_history_key_idx
    ON app_settings_history (audience, key, changed_at);

-- Refuses the statement that fired it. Shared: app_settings refuses DELETE and TRUNCATE
-- with it, app_settings_history refuses UPDATE, DELETE and TRUNCATE, and V28 reuses it
-- for the consent records. Raised with the default SQLSTATE (raise_exception), which the
-- self-tests below catch by name, so an unrelated error still fails the migration.
CREATE FUNCTION refuse_row_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '%: % is not allowed on this table', TG_TABLE_NAME, TG_OP;
END;
$$;

-- Stamps who and when on every insert and update, from the transaction's kagua.changed_by.
-- Unset (or blank) refuses the change, which is what makes the history impossible to skip.
CREATE FUNCTION app_settings_stamp()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    who text := NULLIF(btrim(current_setting('kagua.changed_by', true)), '');
BEGIN
    IF who IS NULL THEN
        RAISE EXCEPTION 'app_settings: set kagua.changed_by in this transaction before changing a setting'
            USING HINT = 'Use server/db/set-setting.sh, which sets it.';
    END IF;
    -- A row is one app's one setting for life. Moving it to another app or key would make
    -- the history read as if the old setting had never existed.
    IF TG_OP = 'UPDATE' AND (NEW.audience <> OLD.audience OR NEW.key <> OLD.key) THEN
        RAISE EXCEPTION 'app_settings: a setting''s audience and key never change';
    END IF;
    NEW.updated_at := now();
    NEW.updated_by := who;
    RETURN NEW;
END;
$$;

-- One history row per inserted or updated setting. AFTER, so it records only a row the
-- CHECKs accepted; a refused change rolls back with its statement and leaves no row.
CREATE FUNCTION app_settings_log()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    prior text;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        prior := OLD.value;
    END IF;
    INSERT INTO app_settings_history (
        audience, key, old_value, new_value, changed_by, reason, preset
    ) VALUES (
        NEW.audience, NEW.key, prior, NEW.value, NEW.updated_by,
        NULLIF(btrim(current_setting('kagua.reason', true)), ''),
        NULLIF(btrim(current_setting('kagua.preset', true)), '')
    );
    RETURN NULL;
END;
$$;

CREATE TRIGGER app_settings_stamp
    BEFORE INSERT OR UPDATE ON app_settings
    FOR EACH ROW EXECUTE FUNCTION app_settings_stamp();

CREATE TRIGGER app_settings_log
    AFTER INSERT OR UPDATE ON app_settings
    FOR EACH ROW EXECUTE FUNCTION app_settings_log();

-- A setting is switched off by its value, not removed: a missing row reads as the safe
-- default, and a deletion would leave no history row saying so.
CREATE TRIGGER app_settings_no_delete
    BEFORE DELETE ON app_settings
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();

-- Row triggers do not fire on TRUNCATE, so each table needs a statement trigger as well.
CREATE TRIGGER app_settings_no_truncate
    BEFORE TRUNCATE ON app_settings
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_row_change();

CREATE TRIGGER app_settings_history_append_only
    BEFORE UPDATE OR DELETE ON app_settings_history
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();

CREATE TRIGGER app_settings_history_no_truncate
    BEFORE TRUNCATE ON app_settings_history
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_row_change();

-- The seeds. The gate is off for both apps, so merging and deploying this changes nothing
-- any phone sees. Fundi Bora's terms text is 2026-11 (fundi_terms_of_use_v4.md); Kagua has
-- no terms_version row, which the server reads as "this app has no terms".
SET LOCAL kagua.changed_by = 'V27';

INSERT INTO app_settings (audience, key, value) VALUES
    ('fundi', 'terms_gate',    'off'),
    ('buyer', 'terms_gate',    'off'),
    ('fundi', 'terms_version', '2026-11');

-- Self-test. Each block is a subtransaction: a refusal rolls back only that block, and a
-- statement that should have been refused and was not fails the whole migration.
DO $$
DECLARE
    refused boolean;
BEGIN
    IF (SELECT count(*) FROM app_settings_history WHERE changed_by = 'V27') <> 3 THEN
        RAISE EXCEPTION 'V27 self-test: the seeds did not each write a history row';
    END IF;

    refused := false;
    BEGIN
        UPDATE app_settings_history SET reason = 'self-test';
    EXCEPTION WHEN raise_exception THEN
        refused := true;
    END;
    IF NOT refused THEN
        RAISE EXCEPTION 'V27 self-test: app_settings_history accepted an UPDATE';
    END IF;

    refused := false;
    BEGIN
        DELETE FROM app_settings_history;
    EXCEPTION WHEN raise_exception THEN
        refused := true;
    END;
    IF NOT refused THEN
        RAISE EXCEPTION 'V27 self-test: app_settings_history accepted a DELETE';
    END IF;

    refused := false;
    BEGIN
        TRUNCATE app_settings_history;
    EXCEPTION WHEN raise_exception THEN
        refused := true;
    END;
    IF NOT refused THEN
        RAISE EXCEPTION 'V27 self-test: app_settings_history accepted a TRUNCATE';
    END IF;

    refused := false;
    BEGIN
        DELETE FROM app_settings;
    EXCEPTION WHEN raise_exception THEN
        refused := true;
    END;
    IF NOT refused THEN
        RAISE EXCEPTION 'V27 self-test: app_settings accepted a DELETE';
    END IF;

    refused := false;
    BEGIN
        TRUNCATE app_settings;
    EXCEPTION WHEN raise_exception THEN
        refused := true;
    END;
    IF NOT refused THEN
        RAISE EXCEPTION 'V27 self-test: app_settings accepted a TRUNCATE';
    END IF;

    -- A change with kagua.changed_by blanked is refused. set_config inside the block is
    -- undone with it, so the seeds' value is back afterwards.
    refused := false;
    BEGIN
        PERFORM set_config('kagua.changed_by', '', true);
        UPDATE app_settings SET value = 'off'
         WHERE audience = 'fundi' AND key = 'terms_gate';
    EXCEPTION WHEN raise_exception THEN
        refused := true;
    END;
    IF NOT refused THEN
        RAISE EXCEPTION 'V27 self-test: a setting changed without kagua.changed_by';
    END IF;

    -- A value outside the key's CHECK is refused.
    refused := false;
    BEGIN
        UPDATE app_settings SET value = 'yes'
         WHERE audience = 'fundi' AND key = 'terms_gate';
    EXCEPTION WHEN check_violation THEN
        refused := true;
    END;
    IF NOT refused THEN
        RAISE EXCEPTION 'V27 self-test: terms_gate accepted a value other than on or off';
    END IF;

    IF (SELECT count(*) FROM app_settings_history) <> 3 THEN
        RAISE EXCEPTION 'V27 self-test: a refused change left a history row';
    END IF;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V27__app_settings')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
