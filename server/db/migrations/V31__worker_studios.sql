-- Kagua server schema, version 31.
--
-- The studios a Fundi Bora account says it works at, and the two-sided match between a
-- worker's account and a studio's account.
--
-- WHY: there are no separate worker or studio accounts (Nathan, 12 November 2026). Every
-- Fundi Bora account is the same kind. An account's roster (workers, V23 and V30) is its
-- studio's list of workers; an account's list of studios (this table) makes it a worker of
-- those studios. Account W works at studio account S when S's roster has a live entry whose
-- number is W's account number, and W's list has a live entry whose number is S's account
-- number. Both sides, or no link. Matching is by account number only, users.phone (V21):
-- a studio has no separate number in this model.
--
-- THE MATCH IS NEVER STORED. worker_links is a view over workers, worker_studios and users,
-- so it is always current: archiving an entry on either side, or deleting an account
-- (anonymise_user nulls every number), ends the link the moment it happens, with nothing to
-- go stale. The routes read it to mark an entry "Linked", and the data API reads it too.
-- No trigger computes anything.
--
-- A LINK THAT ENDED STAYS ON RECORD, for research: who was linked when. The view cannot say
-- that, since a deleted account's numbers are gone, so worker_link_events is an append-only
-- record of each link's start and end, written by sync_worker_link_events(). The store calls
-- it in the same transaction as every roster and studio write, and anonymise_user calls it
-- after clearing the account's numbers, so an event follows every change that can make or
-- end a link. It is idempotent: it compares the view with the newest event per pair and
-- appends only what changed, so calling it when nothing changed writes nothing. The table's
-- only triggers refuse UPDATE, DELETE and TRUNCATE, as V27's and V28's records do.
--
-- A link is one row per pair of accounts. Two roster entries with one number link once, on
-- the oldest live entry (DISTINCT ON in the view). An account that lists its own number on
-- both sides does not link to itself.
--
-- NOBODY CAN PROBE WHO USES THE APP. An entry is "Linked" only through the two-sided match;
-- an unlinked entry reads the same whether or not its number has an account. The routes
-- never look up a number on its own.
--
-- DELETION. anonymise_user is redefined with every clearing from V30, plus the name and
-- number on the deleting account's studio list; clearing them ends its links, which the
-- call to sync_worker_link_events() records.
--
-- The server tests have no database, so the match, the CHECKs and the deletion are proved by
-- the self-tests below, which tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

CREATE TABLE worker_studios (
    -- Made on the phone, as roster ids are, so the list works offline.
    id          uuid PRIMARY KEY,
    -- The account that works there.
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- The studio's name as the person typed or picked it. Nullable only so anonymise_user
    -- can clear it.
    name        text,
    -- The studio's account number, E.164, as the person picked or typed it. Nullable only
    -- so anonymise_user can clear it; a live entry always has one.
    phone       text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- The server's receipt time of the last write, never the phone's clock (see V18).
    updated_at  timestamptz NOT NULL DEFAULT now(),
    -- Archive, never delete: a past link stays on record.
    archived_at timestamptz,
    CONSTRAINT worker_studios_name_length
        CHECK (name IS NULL OR length(name) BETWEEN 1 AND 60),
    -- The same shape as users_phone_is_e164 (V21), so the match is an equality.
    CONSTRAINT worker_studios_phone_is_e164
        CHECK (phone IS NULL OR (phone ~ '^\+[1-9][0-9]{7,14}$' AND phone !~ '^\+2540'))
);

CREATE INDEX worker_studios_user_idx ON worker_studios (user_id, archived_at);

-- "Which lists name this number": the match from the studio's side.
CREATE INDEX worker_studios_phone_idx
    ON worker_studios (phone) WHERE phone IS NOT NULL AND archived_at IS NULL;

-- The match. Read it, never store it.
CREATE VIEW worker_links AS
SELECT DISTINCT ON (u.id, s.id)
       u.id          AS worker_user_id,
       s.id          AS studio_user_id,
       w.id          AS worker_entry_id,
       ws.id         AS studio_entry_id,
       w.employment  AS employment
  FROM workers w
  -- The studio: the account whose roster this is.
  JOIN users s ON s.id = w.user_id
  -- The worker: the account whose number the roster entry carries.
  JOIN users u ON u.phone = w.phone
  -- The worker's own list naming the studio's number.
  JOIN worker_studios ws ON ws.user_id = u.id AND ws.phone = s.phone
 WHERE w.phone IS NOT NULL AND w.archived_at IS NULL
   AND s.phone IS NOT NULL AND s.deleted_at IS NULL
   AND u.deleted_at IS NULL
   AND u.id <> s.id
   AND ws.archived_at IS NULL
 ORDER BY u.id, s.id, w.created_at, w.id;

-- Each link's start and end, for research. Append-only.
CREATE TABLE worker_link_events (
    id              bigserial PRIMARY KEY,
    worker_user_id  uuid NOT NULL REFERENCES users(id),
    studio_user_id  uuid NOT NULL REFERENCES users(id),
    -- The roster entry the link was made on, and its kind at the time.
    worker_entry_id uuid NOT NULL REFERENCES workers(id),
    employment      text NOT NULL,
    event           text NOT NULL,
    -- The server's clock when the change was recorded: the receipt time of the write
    -- that made or ended the link.
    at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT worker_link_events_event_known
        CHECK (event IN ('linked', 'ended')),
    CONSTRAINT worker_link_events_employment_known
        CHECK (employment IN ('employee', 'temporary'))
);

CREATE INDEX worker_link_events_pair_idx
    ON worker_link_events (worker_user_id, studio_user_id, id);

CREATE TRIGGER worker_link_events_append_only
    BEFORE UPDATE OR DELETE ON worker_link_events
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();

CREATE TRIGGER worker_link_events_no_truncate
    BEFORE TRUNCATE ON worker_link_events
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_row_change();

-- Appends a 'linked' event for each pair in the view whose newest event is not 'linked',
-- and an 'ended' event for each pair whose newest event is 'linked' and which the view no
-- longer holds. Returns how many it wrote. Whole-table, since the pilot's roster is small
-- and a scoped version would have to know which accounts a write can affect.
CREATE FUNCTION sync_worker_link_events()
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    opened integer;
    closed integer;
BEGIN
    WITH latest AS (
        SELECT DISTINCT ON (worker_user_id, studio_user_id)
               worker_user_id, studio_user_id, event
          FROM worker_link_events
         ORDER BY worker_user_id, studio_user_id, id DESC
    )
    INSERT INTO worker_link_events (worker_user_id, studio_user_id, worker_entry_id, employment, event)
    SELECT l.worker_user_id, l.studio_user_id, l.worker_entry_id, l.employment, 'linked'
      FROM worker_links l
      LEFT JOIN latest e
        ON e.worker_user_id = l.worker_user_id AND e.studio_user_id = l.studio_user_id
     WHERE e.event IS NULL OR e.event = 'ended';
    GET DIAGNOSTICS opened = ROW_COUNT;

    WITH latest AS (
        SELECT DISTINCT ON (worker_user_id, studio_user_id)
               worker_user_id, studio_user_id, worker_entry_id, employment, event
          FROM worker_link_events
         ORDER BY worker_user_id, studio_user_id, id DESC
    )
    INSERT INTO worker_link_events (worker_user_id, studio_user_id, worker_entry_id, employment, event)
    SELECT e.worker_user_id, e.studio_user_id, e.worker_entry_id, e.employment, 'ended'
      FROM latest e
     WHERE e.event = 'linked'
       AND NOT EXISTS (
           SELECT 1 FROM worker_links l
            WHERE l.worker_user_id = e.worker_user_id AND l.studio_user_id = e.studio_user_id
       );
    GET DIAGNOSTICS closed = ROW_COUNT;

    RETURN opened + closed;
END;
$$;

-- Self-test of the match, before anonymise_user is redefined below: MigrationsTest reads
-- every UPDATE after that definition as part of the function, and this test archives
-- entries with UPDATEs. Two throwaway accounts list each other; everything is rolled back
-- by the known error at the end.
DO $$
DECLARE
    studio  uuid := '00000000-0000-4000-8000-0000000000e0';
    worker  uuid := '00000000-0000-4000-8000-0000000000e1';
    other   uuid := '00000000-0000-4000-8000-0000000000e2';
    entry   uuid := '00000000-0000-4000-8000-0000000000e3';
    entry2  uuid := '00000000-0000-4000-8000-0000000000e4';
    listing uuid := '00000000-0000-4000-8000-0000000000e5';
    refused boolean;
    links   integer;
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone) VALUES (studio, 'fundi', '+254700000001');
        INSERT INTO users (id, audience, phone) VALUES (worker, 'fundi', '+254700000002');
        -- An account nobody lists, with a number a roster will name: it must not show.
        INSERT INTO users (id, audience, phone) VALUES (other, 'fundi', '+254700000003');

        -- One side: the studio lists the worker's number. No link.
        INSERT INTO workers (id, user_id, name, phone, employment)
        VALUES (entry, studio, 'Kamau', '+254700000002', 'temporary');
        IF EXISTS (SELECT 1 FROM worker_links) THEN
            RAISE EXCEPTION 'V31 self-test: a one-sided roster entry made a link';
        END IF;

        -- The other side alone: the worker lists the studio's number. Still no link, once
        -- the roster entry is archived.
        INSERT INTO worker_studios (id, user_id, name, phone)
        VALUES (listing, worker, 'Mama Njeri''s workshop', '+254700000001');
        UPDATE workers SET archived_at = now() WHERE id = entry;
        IF EXISTS (SELECT 1 FROM worker_links) THEN
            RAISE EXCEPTION 'V31 self-test: a one-sided studio listing made a link';
        END IF;
        UPDATE workers SET archived_at = NULL WHERE id = entry;

        -- Both sides: one link, on the roster entry, with its kind.
        IF (SELECT count(*) FROM worker_links) <> 1 THEN
            RAISE EXCEPTION 'V31 self-test: both sides listed did not make exactly one link';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM worker_links
             WHERE worker_user_id = worker AND studio_user_id = studio
               AND worker_entry_id = entry AND studio_entry_id = listing
               AND employment = 'temporary'
        ) THEN
            RAISE EXCEPTION 'V31 self-test: the link does not name the two accounts and the entry';
        END IF;

        -- Recorded once, and recording again writes nothing.
        IF sync_worker_link_events() <> 1 THEN
            RAISE EXCEPTION 'V31 self-test: the first sync did not record one linked event';
        END IF;
        IF sync_worker_link_events() <> 0 THEN
            RAISE EXCEPTION 'V31 self-test: a second sync with no change wrote events';
        END IF;

        -- A second roster entry with the same number: still one link.
        INSERT INTO workers (id, user_id, name, phone)
        VALUES (entry2, studio, 'Kamau again', '+254700000002');
        IF (SELECT count(*) FROM worker_links) <> 1 THEN
            RAISE EXCEPTION 'V31 self-test: two roster entries for one number linked twice';
        END IF;
        IF sync_worker_link_events() <> 0 THEN
            RAISE EXCEPTION 'V31 self-test: a second entry for one number wrote an event';
        END IF;

        -- Archiving the studio listing ends the link, and the end is recorded.
        UPDATE worker_studios SET archived_at = now() WHERE id = listing;
        IF EXISTS (SELECT 1 FROM worker_links) THEN
            RAISE EXCEPTION 'V31 self-test: an archived studio listing kept its link';
        END IF;
        IF sync_worker_link_events() <> 1 THEN
            RAISE EXCEPTION 'V31 self-test: the ended link was not recorded';
        END IF;
        IF (SELECT event FROM worker_link_events ORDER BY id DESC LIMIT 1) <> 'ended' THEN
            RAISE EXCEPTION 'V31 self-test: the newest event is not ended';
        END IF;

        -- Listed again: a new link, as a new event.
        UPDATE worker_studios SET archived_at = NULL WHERE id = listing;
        IF sync_worker_link_events() <> 1 THEN
            RAISE EXCEPTION 'V31 self-test: relisting did not record a new linked event';
        END IF;

        -- Archiving every roster entry for the number ends it too.
        UPDATE workers SET archived_at = now() WHERE user_id = studio;
        IF EXISTS (SELECT 1 FROM worker_links) THEN
            RAISE EXCEPTION 'V31 self-test: archived roster entries kept their link';
        END IF;
        UPDATE workers SET archived_at = NULL WHERE user_id = studio;

        -- An account listing its own number on both sides does not link to itself.
        INSERT INTO workers (id, user_id, name, phone)
        VALUES ('00000000-0000-4000-8000-0000000000e6', studio, 'Me', '+254700000001');
        INSERT INTO worker_studios (id, user_id, name, phone)
        VALUES ('00000000-0000-4000-8000-0000000000e7', studio, 'My own', '+254700000001');
        IF EXISTS (SELECT 1 FROM worker_links WHERE worker_user_id = studio_user_id) THEN
            RAISE EXCEPTION 'V31 self-test: an account linked to itself';
        END IF;

        -- The CHECKs.
        refused := false;
        BEGIN
            INSERT INTO worker_studios (id, user_id, name, phone)
            VALUES ('00000000-0000-4000-8000-0000000000e8', worker, 'Wrong', '0700000001');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V31 self-test: worker_studios accepted 0700000001';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO worker_studios (id, user_id, name, phone)
            VALUES ('00000000-0000-4000-8000-0000000000e8', worker, '', '+254700000001');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V31 self-test: worker_studios accepted an empty name';
        END IF;

        -- The record cannot be changed or emptied.
        refused := false;
        BEGIN
            UPDATE worker_link_events SET event = 'ended' WHERE id = (SELECT min(id) FROM worker_link_events);
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V31 self-test: worker_link_events accepted an UPDATE';
        END IF;

        refused := false;
        BEGIN
            DELETE FROM worker_link_events;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V31 self-test: worker_link_events accepted a DELETE';
        END IF;

        refused := false;
        BEGIN
            TRUNCATE worker_link_events;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V31 self-test: worker_link_events accepted a TRUNCATE';
        END IF;

        RAISE EXCEPTION 'V31 match self-test passed' USING ERRCODE = 'P0V31';
    EXCEPTION WHEN SQLSTATE 'P0V31' THEN
        -- Everything inside the block above, both accounts included, is rolled back.
        NULL;
    END;
END
$$;

-- As V30, plus the studio list's name and number, and the link record after them.
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

    -- The studios this account said it worked at (V31): each names a workshop and its
    -- owner's number. The rows stay, so the link record below keeps its ids.
    UPDATE worker_studios SET
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

    -- The numbers above being gone ends every link this account was on, on either side.
    -- Recorded now, so the record says when.
    PERFORM sync_worker_link_events();

    RETURN affected > 0;
END;
$$;

-- Self-test of the deletion, after the definition: INSERTs only, never an UPDATE, for the
-- reason the first block gives. Two accounts linked, one deleted: the link ends, its end is
-- recorded, and the deleted account's studio list is blank.
DO $$
DECLARE
    studio uuid := '00000000-0000-4000-8000-0000000000f0';
    worker uuid := '00000000-0000-4000-8000-0000000000f1';
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone) VALUES (studio, 'fundi', '+254700000011');
        INSERT INTO users (id, audience, phone) VALUES (worker, 'fundi', '+254700000012');
        INSERT INTO workers (id, user_id, name, phone)
        VALUES ('00000000-0000-4000-8000-0000000000f2', studio, 'Kamau', '+254700000012');
        INSERT INTO worker_studios (id, user_id, name, phone)
        VALUES ('00000000-0000-4000-8000-0000000000f3', worker, 'The workshop', '+254700000011');
        IF sync_worker_link_events() <> 1 THEN
            RAISE EXCEPTION 'V31 self-test: the two accounts did not link';
        END IF;

        IF NOT anonymise_user(worker) THEN
            RAISE EXCEPTION 'V31 self-test: anonymise_user did not anonymise the worker';
        END IF;
        IF EXISTS (SELECT 1 FROM worker_studios WHERE user_id = worker AND (name IS NOT NULL OR phone IS NOT NULL)) THEN
            RAISE EXCEPTION 'V31 self-test: anonymise_user left the studio list''s name or number behind';
        END IF;
        IF EXISTS (SELECT 1 FROM worker_links WHERE worker_user_id = worker) THEN
            RAISE EXCEPTION 'V31 self-test: a deleted account kept its link';
        END IF;
        IF (SELECT event FROM worker_link_events WHERE worker_user_id = worker ORDER BY id DESC LIMIT 1) <> 'ended' THEN
            RAISE EXCEPTION 'V31 self-test: the deletion did not record the link''s end';
        END IF;

        RAISE EXCEPTION 'V31 deletion self-test passed' USING ERRCODE = 'P0V31';
    EXCEPTION WHEN SQLSTATE 'P0V31' THEN
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V31__worker_studios')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
