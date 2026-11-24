-- Kagua server schema, version 39.
--
-- Kagua on a shop's tablet (release scope row 12; Nathan, 6 and 16 November 2026). Merge after
-- V38 (#114), before v0.7.0's PRs 3 to 5.
--
-- WHO: DIL may give a shop a tablet with Kagua on it. The shop owner registers it with their
-- own number (V38 lets that be their Fundi Bora number), and walk-in customers use it to check
-- a finished piece before buying. The tablet keeps no customer's assessment: it deletes its own
-- copy when the customer is done, leaves the app, locks the screen, or stops touching it for
-- five minutes. The server keeps every one, and never sends a shop account's earlier
-- assessments back to any phone.
--
-- WHERE SHOP MODE COMES FROM: THE INVITE CODE, as is_tester (V10) and the audience (V16) do
-- (Nathan, 20 November 2026). Only shops DIL gives a tablet get a shop code; the business
-- question at sign-up (V2's account_type) does not turn shop mode on. Kagua codes only: the
-- CHECKs below refuse a Fundi Bora shop code and a Fundi Bora shop account.
--
-- Fixed at registration, like the audience and unlike is_tester: an account's assessments were
-- made either on a shop's tablet or on somebody's own phone, and flipping the flag would hide a
-- personal account's history from its own phone, or send a shop's customers' assessments to
-- the tablet. The portal does not edit it.
--
-- THE RESEARCH RECORD. sessions.shop_tablet, copied from the account when the assessment
-- starts and never changed (as sessions.audience is, V16), says the assessment was made on a
-- shop's tablet. tablet_ended and tablet_ended_at say how and when the tablet deleted its copy:
-- 'done' (the customer pressed "Done: delete this assessment", or went back to the start
-- screen), 'left_app', 'screen_lock' or 'idle' (five minutes with no touch). Null until the
-- tablet reports it, and for good if it never does (a tablet that is switched off mid-way, or
-- never comes back online). The first report wins.
--
-- THE ONE IN PROGRESS. shop_session_in_progress(account) is the only assessment of a shop
-- account the server will show a phone (GET /v1/sessions/{id} and its photos): the account's
-- newest assessment, while its tablet has not reported deleting it, it has not been deleted,
-- and it has changed in the last 30 minutes. Newest first, then the conditions, so an older
-- assessment is never the one in progress once a newer one exists. Thirty minutes is longer
-- than any one customer's assessment (the tablet deletes its copy after five idle minutes) and
-- short enough that a tablet that never reported its wipe stops being shown it soon after.
--
-- DELETION. anonymise_user is not redefined: nothing here is personal data. The flags and the
-- end of the tablet copy describe the assessment, not the person, and stay with it as
-- sessions.audience does.
--
-- ROLLBACK. Drop the function shop_session_in_progress, the three sessions columns, and the two
-- shop_mode columns with their CHECKs, in any order. Nothing else reads them. The rollback loses
-- which accounts are shops and which assessments were made on a tablet, so take it only before
-- the first shop code is issued, or after exporting those columns.
--
-- The server tests have no database, so the CHECKs and the function are proved by the self-test
-- below, which tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

-- Carried by the code, never typed at registration.
ALTER TABLE invite_codes ADD COLUMN grants_shop_mode boolean NOT NULL DEFAULT false;
ALTER TABLE invite_codes
    ADD CONSTRAINT invite_codes_shop_mode_kagua CHECK (NOT grants_shop_mode OR audience = 'buyer');

-- Copied from the invite at registration, in the same transaction that redeems it.
ALTER TABLE users ADD COLUMN shop_mode boolean NOT NULL DEFAULT false;
ALTER TABLE users
    ADD CONSTRAINT users_shop_mode_kagua CHECK (NOT shop_mode OR audience = 'buyer');

-- Written once, when the assessment starts, from users.shop_mode.
ALTER TABLE sessions ADD COLUMN shop_tablet boolean NOT NULL DEFAULT false;

-- How the tablet's copy ended. A closed vocabulary, mirrored by TabletEnd in :shared and held
-- to this list by its vocabulary test.
ALTER TABLE sessions ADD COLUMN tablet_ended text;
ALTER TABLE sessions ADD COLUMN tablet_ended_at timestamptz;
ALTER TABLE sessions
    ADD CONSTRAINT sessions_tablet_ended_known
        CHECK (tablet_ended IN ('done', 'left_app', 'screen_lock', 'idle'));
-- Both or neither, and only on a tablet's assessment.
ALTER TABLE sessions
    ADD CONSTRAINT sessions_tablet_ended_paired
        CHECK ((tablet_ended IS NULL) = (tablet_ended_at IS NULL));
ALTER TABLE sessions
    ADD CONSTRAINT sessions_tablet_ended_on_tablet
        CHECK (tablet_ended IS NULL OR shop_tablet);

-- Defaulted to false, and correct without a backfill: no code and no account is a shop's
-- before this migration.

-- Few rows, read by research only.
CREATE INDEX sessions_shop_tablet_idx ON sessions (user_id, created_at DESC) WHERE shop_tablet;

-- The one assessment of a shop account a phone may read (see THE ONE IN PROGRESS above). Null
-- when there is none. STABLE: it reads now() and the table, and changes nothing.
CREATE FUNCTION shop_session_in_progress(account uuid) RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT newest.id
      FROM (SELECT s.id, s.tablet_ended, s.client_deleted_at, s.updated_at
              FROM sessions s
             WHERE s.user_id = account
             ORDER BY s.created_at DESC, s.id DESC
             LIMIT 1) newest
     WHERE newest.tablet_ended IS NULL
       AND newest.client_deleted_at IS NULL
       AND newest.updated_at > now() - interval '30 minutes'
$$;

-- Self-test of the CHECKs and the function. A shop account and a personal Kagua account, with
-- their own assessments; everything is rolled back by the known error at the end, and nothing
-- outside this test's own rows is counted. Country code 999 is assigned to nobody (V31).
DO $$
DECLARE
    shop      uuid := '00000000-0000-4000-8000-000000000390';
    personal  uuid := '00000000-0000-4000-8000-000000000391';
    earlier   uuid := '00000000-0000-4000-8000-000000000392';
    current_  uuid := '00000000-0000-4000-8000-000000000393';
    refused   boolean;
BEGIN
    BEGIN
        -- A Fundi Bora shop code and a Fundi Bora shop account are refused.
        refused := false;
        BEGIN
            INSERT INTO invite_codes (code, audience, grants_shop_mode) VALUES ('V39-SELF-TEST', 'fundi', true);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V39 self-test: a Fundi Bora invite took shop mode';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO users (id, audience, shop_mode, phone, password_hash)
            VALUES ('00000000-0000-4000-8000-000000000394', 'fundi', true, '+999000000094', 'self-test');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V39 self-test: a Fundi Bora account took shop mode';
        END IF;

        INSERT INTO users (id, audience, shop_mode, phone, password_hash) VALUES (shop,     'buyer', true,  '+999000000090', 'self-test');
        INSERT INTO users (id, audience, shop_mode, phone, password_hash) VALUES (personal, 'buyer', false, '+999000000091', 'self-test');

        -- No assessment yet: nothing in progress.
        IF shop_session_in_progress(shop) IS NOT NULL THEN
            RAISE EXCEPTION 'V39 self-test: an account with no assessment has one in progress';
        END IF;

        -- An earlier customer's assessment, then the current one.
        INSERT INTO sessions (id, user_id, item_type_id, audience, shop_tablet, created_at, updated_at)
        VALUES (earlier, shop, 'wooden-table', 'buyer', true, now() - interval '10 minutes', now() - interval '9 minutes');
        IF shop_session_in_progress(shop) IS DISTINCT FROM earlier THEN
            RAISE EXCEPTION 'V39 self-test: the only fresh assessment is not the one in progress';
        END IF;
        INSERT INTO sessions (id, user_id, item_type_id, audience, shop_tablet, created_at, updated_at)
        VALUES (current_, shop, 'wooden-chair', 'buyer', true, now() - interval '1 minute', now());
        IF shop_session_in_progress(shop) IS DISTINCT FROM current_ THEN
            RAISE EXCEPTION 'V39 self-test: the newest assessment is not the one in progress';
        END IF;

        -- The earlier one never comes back, even if the newest has ended: the newest is chosen
        -- first, then tested.
        UPDATE sessions SET tablet_ended = 'done', tablet_ended_at = now() WHERE id = current_;
        IF shop_session_in_progress(shop) IS NOT NULL THEN
            RAISE EXCEPTION 'V39 self-test: an ended assessment, or an earlier one, is in progress';
        END IF;

        -- Gone stale without a report: after 30 minutes nothing is in progress.
        UPDATE sessions SET tablet_ended = NULL, tablet_ended_at = NULL,
                            updated_at = now() - interval '31 minutes'
         WHERE id = current_;
        IF shop_session_in_progress(shop) IS NOT NULL THEN
            RAISE EXCEPTION 'V39 self-test: a stale assessment is still in progress';
        END IF;

        -- An end with no time, an unknown end, and an end on a phone's assessment are refused.
        refused := false;
        BEGIN
            UPDATE sessions SET tablet_ended = 'idle' WHERE id = current_;
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V39 self-test: an end was stored without its time';
        END IF;

        refused := false;
        BEGIN
            UPDATE sessions SET tablet_ended = 'deleted', tablet_ended_at = now() WHERE id = current_;
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V39 self-test: an unknown end was stored';
        END IF;

        INSERT INTO sessions (id, user_id, item_type_id, audience)
        VALUES ('00000000-0000-4000-8000-000000000395', personal, 'wooden-table', 'buyer');
        refused := false;
        BEGIN
            UPDATE sessions SET tablet_ended = 'done', tablet_ended_at = now()
             WHERE id = '00000000-0000-4000-8000-000000000395';
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V39 self-test: a phone''s assessment took a tablet end';
        END IF;

        RAISE EXCEPTION 'V39 self-test passed' USING ERRCODE = 'P0V39';
    EXCEPTION WHEN SQLSTATE 'P0V39' THEN
        -- Everything inside the block above, both accounts included, is rolled back.
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V39__shop_tablet')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
