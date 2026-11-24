-- Kagua server schema, version 38.
--
-- One account per phone number per app, instead of one per number (release scope row 30;
-- Nathan, 16 November 2026). Merge after V37 (#112, #113), before v0.7.0's PRs 2 to 5.
--
-- WHY: Phase One puts Kagua on a tablet in some shops, and the shop owner registers it with
-- their own number, which is usually the number they already use for Fundi Bora. V5's
-- users_phone_key allowed one account per number across both apps, so that registration was
-- refused. Now one number may hold one Kagua ('buyer') account and one Fundi Bora ('fundi')
-- account. The two stay independent, as V16 says: separate passwords, histories and
-- deletion. A second account of the same app on one number is still refused.
--
-- SIGN-IN. The phone already names its app on every request (X-App-Id, since v0.4.0), and the
-- sign-in route now reads it to choose which of the number's accounts to check the password
-- against: Kagua's buyer account, or the fundi account for Fundi Bora and PesaLog (V33). It
-- never creates an account or chooses a prompt; the invite code still decides the audience
-- (V16).
--
-- EVERY MATCH ON NUMBERS NAMES ITS AUDIENCE. The two-sided match (worker_links, V31) and the
-- manager of a piece (piece_managers, V34) join users on phone. Rosters and studio lists are
-- Fundi Bora's, so both views now join Fundi Bora accounts only: a Kagua account on a
-- worker's or a studio's number never links, never manages a piece, and never reads one.
-- Before this, the joins happened to drop a Kagua account (it has no studio list and no
-- roster), but only by accident of the data; the views now say it.
--
-- DELETION. anonymise_user is not redefined. Nothing here stores anything new, and its comment
-- that the unique index on phone is partial, so cleared numbers do not collide, still holds.
--
-- ROLLBACK. Only while no number holds two accounts: drop users_phone_audience_key, recreate
-- users_phone_key ON users (phone) WHERE phone IS NOT NULL, and restore the two views from
-- V31 and V34. Once a shop owner has registered a second account, the old index cannot be
-- built until one of the two is deleted.
--
-- The server tests have no database, so the index and the views are proved by the self-test
-- below, which tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

-- The new index first, so no moment of this transaction is without one. Partial, as V5's: a
-- deleted account's number is null, and nulls never collide.
CREATE UNIQUE INDEX users_phone_audience_key ON users (phone, audience) WHERE phone IS NOT NULL;
DROP INDEX users_phone_key;

-- As V31, plus the audience of both accounts. Same columns in the same order, so the
-- function and the view that read it (sync_worker_link_events, piece_managers) are unchanged.
CREATE OR REPLACE VIEW worker_links AS
SELECT DISTINCT ON (u.id, s.id)
       u.id          AS worker_user_id,
       s.id          AS studio_user_id,
       w.id          AS worker_entry_id,
       ws.id         AS studio_entry_id,
       w.employment  AS employment
  FROM workers w
  -- The studio: the account whose roster this is. Rosters are Fundi Bora's.
  JOIN users s ON s.id = w.user_id AND s.audience = 'fundi'
  -- The worker: the Fundi Bora account whose number the roster entry carries. The same
  -- number may also hold a Kagua account (V38), which is never a worker.
  JOIN users u ON u.phone = w.phone AND u.audience = 'fundi'
  -- The worker's own list naming the studio's number.
  JOIN worker_studios ws ON ws.user_id = u.id AND ws.phone = s.phone
 WHERE w.phone IS NOT NULL AND w.archived_at IS NULL
   AND s.phone IS NOT NULL AND s.deleted_at IS NULL
   AND u.deleted_at IS NULL
   AND u.id <> s.id
   AND ws.archived_at IS NULL
 -- The oldest live entry on each side, so the row is the same on every read.
 ORDER BY u.id, s.id, w.created_at, w.id, ws.created_at, ws.id;

-- As V34, with the studio's account joined among Fundi Bora accounts only. One row per piece
-- at most: a number holds one Fundi Bora account (users_phone_audience_key), and
-- worker_links has one row per pair.
CREATE OR REPLACE VIEW piece_managers AS
SELECT p.id              AS piece_id,
       p.user_id         AS worker_user_id,
       l.studio_user_id  AS manager_user_id,
       p.studio_entry_id AS studio_entry_id
  FROM pieces p
  -- The label, an entry of the maker's own live list.
  JOIN worker_studios ws
    ON ws.id = p.studio_entry_id AND ws.user_id = p.user_id
   AND ws.archived_at IS NULL AND ws.phone IS NOT NULL
  -- The studio's Fundi Bora account, by the entry's number. A Kagua account on the same
  -- number is never a piece's manager.
  JOIN users s ON s.phone = ws.phone AND s.audience = 'fundi' AND s.deleted_at IS NULL
  -- And the two-sided match between the maker and that account.
  JOIN worker_links l ON l.worker_user_id = p.user_id AND l.studio_user_id = s.id;

-- Self-test of the index and both views. A studio owner and a worker, each with a Fundi Bora
-- account and a Kagua account on the same number; everything is rolled back by the known
-- error at the end. Country code 999 is assigned to nobody (V31).
DO $$
DECLARE
    studio       uuid := '00000000-0000-4000-8000-000000000380';
    studio_kagua uuid := '00000000-0000-4000-8000-000000000381';
    worker       uuid := '00000000-0000-4000-8000-000000000382';
    worker_kagua uuid := '00000000-0000-4000-8000-000000000383';
    roster       uuid := '00000000-0000-4000-8000-000000000384';
    listing      uuid := '00000000-0000-4000-8000-000000000385';
    labelled     uuid := '00000000-0000-4000-8000-000000000386';
    refused      boolean;
BEGIN
    BEGIN
        -- One number, one account of each app. Credentials paired (V5).
        INSERT INTO users (id, audience, phone, password_hash) VALUES (studio,       'fundi', '+999000000081', 'self-test');
        INSERT INTO users (id, audience, phone, password_hash) VALUES (studio_kagua, 'buyer', '+999000000081', 'self-test');
        INSERT INTO users (id, audience, phone, password_hash) VALUES (worker,       'fundi', '+999000000082', 'self-test');
        INSERT INTO users (id, audience, phone, password_hash) VALUES (worker_kagua, 'buyer', '+999000000082', 'self-test');

        -- A second account of either app on one number is refused.
        refused := false;
        BEGIN
            INSERT INTO users (id, audience, phone, password_hash)
            VALUES ('00000000-0000-4000-8000-000000000387', 'fundi', '+999000000081', 'self-test');
        EXCEPTION WHEN unique_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V38 self-test: a number took a second Fundi Bora account';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO users (id, audience, phone, password_hash)
            VALUES ('00000000-0000-4000-8000-000000000388', 'buyer', '+999000000081', 'self-test');
        EXCEPTION WHEN unique_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V38 self-test: a number took a second Kagua account';
        END IF;

        -- The two-sided match between the Fundi Bora accounts, with a Kagua account on each
        -- number: exactly one link, between the two fundi accounts.
        INSERT INTO workers (id, user_id, name, phone) VALUES (roster, studio, 'Kamau', '+999000000082');
        INSERT INTO worker_studios (id, user_id, name, phone) VALUES (listing, worker, 'Mama Njeri''s workshop', '+999000000081');
        -- Counted over this test's four accounts only: the rehearsal runs against the live
        -- database, which already holds links of its own.
        IF (SELECT count(*) FROM worker_links
            WHERE worker_user_id IN (worker, worker_kagua, studio, studio_kagua)
               OR studio_user_id IN (worker, worker_kagua, studio, studio_kagua)) <> 1 THEN
            RAISE EXCEPTION 'V38 self-test: worker_links holds % rows for the test accounts, not 1',
                (SELECT count(*) FROM worker_links
                 WHERE worker_user_id IN (worker, worker_kagua, studio, studio_kagua)
                    OR studio_user_id IN (worker, worker_kagua, studio, studio_kagua));
        END IF;
        IF NOT EXISTS (SELECT 1 FROM worker_links WHERE worker_user_id = worker AND studio_user_id = studio) THEN
            RAISE EXCEPTION 'V38 self-test: the link is not between the two Fundi Bora accounts';
        END IF;

        -- A roster written on the Kagua account (no route allows it; the view must not care)
        -- makes no link either: a Kagua account is never a studio.
        INSERT INTO workers (id, user_id, name, phone)
        VALUES ('00000000-0000-4000-8000-000000000389', studio_kagua, 'Kamau', '+999000000082');
        IF EXISTS (SELECT 1 FROM worker_links WHERE studio_user_id = studio_kagua OR worker_user_id = worker_kagua) THEN
            RAISE EXCEPTION 'V38 self-test: a Kagua account linked';
        END IF;

        -- A piece labelled for the studio has one manager, the studio's Fundi Bora account.
        INSERT INTO pieces (id, user_id, item_type_id, studio_entry_id, studio_chosen_at)
        VALUES (labelled, worker, 'wooden-table', listing, now());
        IF (SELECT count(*) FROM piece_managers WHERE piece_id = labelled) <> 1 THEN
            RAISE EXCEPTION 'V38 self-test: the labelled piece has % managers, not 1',
                (SELECT count(*) FROM piece_managers WHERE piece_id = labelled);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM piece_managers WHERE piece_id = labelled AND manager_user_id = studio) THEN
            RAISE EXCEPTION 'V38 self-test: the piece''s manager is not the studio''s Fundi Bora account';
        END IF;

        -- The link record follows the view: one linked event, for the fundi pair. The sync's
        -- own count is not used, since on the live database it may also record a link that
        -- was waiting there; only this test's accounts are counted.
        PERFORM sync_worker_link_events();
        IF (SELECT count(*) FROM worker_link_events
            WHERE worker_user_id IN (worker, worker_kagua, studio, studio_kagua)
               OR studio_user_id IN (worker, worker_kagua, studio, studio_kagua)) <> 1 THEN
            RAISE EXCEPTION 'V38 self-test: the sync did not record exactly one link for the test accounts';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM worker_link_events
                       WHERE worker_user_id = worker AND studio_user_id = studio AND event = 'linked') THEN
            RAISE EXCEPTION 'V38 self-test: the recorded link is not between the two Fundi Bora accounts';
        END IF;

        RAISE EXCEPTION 'V38 self-test passed' USING ERRCODE = 'P0V38';
    EXCEPTION WHEN SQLSTATE 'P0V38' THEN
        -- Everything inside the block above, the four accounts included, is rolled back.
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V38__phone_per_audience')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
