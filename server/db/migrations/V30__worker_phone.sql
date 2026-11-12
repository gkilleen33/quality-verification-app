-- Kagua server schema, version 30.
--
-- A phone number on each roster entry, and whether the person is an employee or a
-- temporary worker.
--
-- WHY: workers on a roster have been names only (V23). A manager now adds a worker from
-- the phone's contacts, and the number is what will later link the worker's own Fundi Bora
-- account to this workshop (Nathan, 12 November 2026): a link needs the manager to list the
-- worker's number and the worker to list the workshop's, and the match is made here from
-- the two numbers. Nothing matches yet; V31 adds the other side. Employee or temporary is
-- recorded for the same report: a workshop's own staff and the people it hires for a job
-- are read differently.
--
-- The number is optional. A worker added before this, or without a number, is still on the
-- roster and still named on pieces; only an entry with a number can ever be linked. Stored
-- in E.164 and held to the same shape as users.phone (V21), so the match can be an equality.
--
-- Employee or temporary is a closed vocabulary (Employment in Fundi.kt), mirrored by the
-- CHECK below and held to it by FundiVocabularyTest. Every existing entry becomes an
-- employee: that is the default, and the only kind the roster knew.
--
-- DELETION. anonymise_user is redefined, as V29's, plus workers.phone. A worker's number
-- identifies the worker, and it was given by the manager whose account is being deleted,
-- so it goes with the manager's own number. The link to parts stays, nameless and
-- numberless, as V23 says. Every clearing from V29, the M-Pesa deletions included, is kept
-- word for word; MigrationsTest checks that nothing was lost.
--
-- The server tests have no database, so the CHECKs and the deletion are proved by the
-- self-test at the end, which tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

ALTER TABLE workers ADD COLUMN phone text;

-- The same shape as users_phone_is_e164 (V21): a + and 8 to 15 digits, never the
-- prefilled +254 followed by the trunk 0. Equal strings are then the same number.
ALTER TABLE workers
    ADD CONSTRAINT workers_phone_is_e164
        CHECK (phone IS NULL OR (phone ~ '^\+[1-9][0-9]{7,14}$' AND phone !~ '^\+2540'));

ALTER TABLE workers ADD COLUMN employment text NOT NULL DEFAULT 'employee';

ALTER TABLE workers
    ADD CONSTRAINT workers_employment_known
        CHECK (employment IN ('employee', 'temporary'));

-- "Which rosters list this number": the match V31 makes, from the worker's side. Only
-- live entries with a number take part, so the index holds only those.
CREATE INDEX workers_phone_idx
    ON workers (phone) WHERE phone IS NOT NULL AND archived_at IS NULL;

-- As V29, plus workers.phone in the roster UPDATE.
CREATE OR REPLACE FUNCTION anonymise_user(target uuid)
RETURNS boolean
LANGUAGE plpgsql
AS $$
DECLARE
    affected integer;
BEGIN
    -- The code this account registered with. Revoked before the statement below blanks
    -- users.invite_code, which is the only link to it and the only thing keeping it single
    -- use. The unique index on that column means no other account holds the same code.
    UPDATE invite_codes SET
        revoked_at = now()
    WHERE revoked_at IS NULL
      AND code IN (
          SELECT u.invite_code FROM users u
           WHERE u.id = target
      );

    UPDATE users SET
        -- The identifier and the credential. Null rather than a placeholder: the unique
        -- index on phone is partial, so nulls do not collide.
        phone                        = NULL,
        -- The number as it was before V21 rewrote it: still somebody's number.
        phone_as_entered             = NULL,
        password_hash                = NULL,
        password_set_at              = NULL,
        display_name                 = NULL,
        business_name                = NULL,
        -- A point plus its accuracy locates a specific workshop, which is as identifying
        -- as the name above it.
        business_location            = NULL,
        business_location_accuracy_m = NULL,
        business_location_at         = NULL,
        -- The code's label often names the person it was handed to.
        invite_code                  = NULL,
        label                        = NULL,
        deleted_at                   = COALESCE(deleted_at, now()),
        anonymised_at                = now()
    WHERE id = target AND anonymised_at IS NULL;

    GET DIAGNOSTICS affected = ROW_COUNT;

    -- Where this account's assessments happened. Cleared for the same reason as the
    -- premises above: together these points say where somebody spends their time.
    UPDATE sessions SET
        location            = NULL,
        location_accuracy_m = NULL,
        location_at         = NULL
    WHERE user_id = target;

    -- What the maker called each piece. Often the customer's name.
    UPDATE pieces SET
        label = NULL
    WHERE user_id = target;

    -- The roster. A nickname identifies a person, and so does a number (V30); the links
    -- to parts stay, nameless. Employee or temporary says nothing about who, and stays.
    UPDATE workers SET
        name  = NULL,
        phone = NULL
    WHERE user_id = target;

    -- Each photo's camera metadata: GPS, serial numbers, owner and artist names.
    UPDATE attachments SET
        photo_meta = NULL
    WHERE photo_meta IS NOT NULL
      AND message_id IN (
          SELECT m.id FROM messages m JOIN sessions s ON s.id = m.session_id
           WHERE s.user_id = target
      );

    -- The fundi's M-Pesa transactions and unread counts (V29), whole: the comments can
    -- name people, and the rest is the fundi's own finances.
    DELETE FROM mpesa_transactions WHERE user_id = target;
    DELETE FROM mpesa_unread_counts WHERE user_id = target;

    -- Every device signed out. Otherwise a phone holding a live refresh token keeps
    -- working against an account that no longer has an owner.
    DELETE FROM refresh_tokens WHERE user_id = target;

    RETURN affected > 0;
END;
$$;

-- Self-test, against a throwaway account created and removed inside the test, as V29's.
-- INSERTs only, never an UPDATE: MigrationsTest reads every UPDATE after this file's
-- anonymise_user as part of the function, and a later definition would then have to
-- repeat it. The block ends by raising a known error that its outer handler catches, so
-- the account and its rows never outlive the test.
DO $$
DECLARE
    probe   uuid := '00000000-0000-4000-8000-0000000000d0';
    kamau   uuid := '00000000-0000-4000-8000-0000000000d1';
    refused boolean;
BEGIN
    BEGIN
        INSERT INTO users (id, audience) VALUES (probe, 'fundi');

        -- A number in the stored form, and each kind of entry.
        INSERT INTO workers (id, user_id, name, phone, employment)
        VALUES (kamau, probe, 'Kamau', '+254712345678', 'temporary');
        INSERT INTO workers (id, user_id, name)
        VALUES ('00000000-0000-4000-8000-0000000000d2', probe, 'Otieno');
        IF (SELECT employment FROM workers WHERE id = '00000000-0000-4000-8000-0000000000d2') <> 'employee' THEN
            RAISE EXCEPTION 'V30 self-test: a worker added without a kind is not an employee';
        END IF;

        -- The prefilled +254 followed by the trunk 0: the shape V21 rewrote away.
        refused := false;
        BEGIN
            INSERT INTO workers (id, user_id, name, phone)
            VALUES ('00000000-0000-4000-8000-0000000000d3', probe, 'Wrong', '+2540712345678');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V30 self-test: workers accepted +2540712345678';
        END IF;

        -- As people say it, without the country code.
        refused := false;
        BEGIN
            INSERT INTO workers (id, user_id, name, phone)
            VALUES ('00000000-0000-4000-8000-0000000000d3', probe, 'Wrong', '0712345678');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V30 self-test: workers accepted 0712345678';
        END IF;

        -- With the spaces a contact card prints.
        refused := false;
        BEGIN
            INSERT INTO workers (id, user_id, name, phone)
            VALUES ('00000000-0000-4000-8000-0000000000d3', probe, 'Wrong', '+254 712 345 678');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V30 self-test: workers accepted a number with spaces';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO workers (id, user_id, name, employment)
            VALUES ('00000000-0000-4000-8000-0000000000d3', probe, 'Wrong', 'contractor');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V30 self-test: workers accepted the kind contractor';
        END IF;

        -- Two entries may share a number: the app does not stop a manager listing the same
        -- person twice, as it does not stop two entries sharing a name (V23).
        INSERT INTO workers (id, user_id, name, phone)
        VALUES ('00000000-0000-4000-8000-0000000000d4', probe, 'Kamau again', '+254712345678');

        -- Deleting the account clears every number on its roster and keeps the kinds.
        IF NOT anonymise_user(probe) THEN
            RAISE EXCEPTION 'V30 self-test: anonymise_user did not anonymise the probe account';
        END IF;
        IF EXISTS (SELECT 1 FROM workers WHERE user_id = probe AND (phone IS NOT NULL OR name IS NOT NULL)) THEN
            RAISE EXCEPTION 'V30 self-test: anonymise_user left a worker''s name or number behind';
        END IF;
        IF (SELECT employment FROM workers WHERE id = kamau) <> 'temporary' THEN
            RAISE EXCEPTION 'V30 self-test: anonymise_user changed a worker''s kind';
        END IF;

        RAISE EXCEPTION 'V30 self-test passed' USING ERRCODE = 'P0V30';
    EXCEPTION WHEN SQLSTATE 'P0V30' THEN
        -- Everything inside the block above, the probe account included, is rolled back.
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V30__worker_phone')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
