-- Kagua server schema, version 34.
--
-- Fundi Bora v0.6.0's pieces and studios (release scope rows 1 to 4, 9, 11 and 27; Nathan,
-- 4, 14 and 16 November 2026): which studio each piece is for, its one correction, the
-- studio a fundi is at each day, the final assessment before a customer report, and who
-- started each assessment of a piece.
--
-- THE LABEL. A piece carries the studio it is for: one entry of its maker's own list of
-- studios they work for (worker_studios, V31), or none for "My own job". The label lives on
-- the piece and not on each assessment, so later assessments inherit it. studio_chosen_at
-- says the maker answered at all: a piece from before v0.6.0, or from an older app, was
-- never asked, which is not the same as "My own job", and the research must tell the two
-- apart. Only an entry of the piece's own maker can be the label; the store checks that on
-- every write, since a CHECK cannot read another table.
--
-- ONE CORRECTION. A wrong label can be changed once (Nathan, 4 November 2026). Each change
-- after creation is a row in piece_studio_changes, append-only, with the old and new
-- values and the server's time. A piece that was never asked may be labelled once
-- ('first_choice'); after that, one 'correction'. The partial unique indexes make a second
-- of either kind a constraint violation, so the rule holds even against a buggy route.
--
-- WHO MAY SEE A WORKER'S PIECE. piece_managers is a view, like worker_links: the account
-- that runs the studio a piece is labelled for, while the two-sided match holds between it
-- and the piece's maker. That account may read the piece and add its own assessments to it
-- (row 11). The piece keeps one maker, pieces.user_id. When either side ends the link, or
-- the maker archives the studio entry, the view drops the row and the access with it. A
-- "My own job" piece, or one labelled for a studio that is not linked, has no row.
--
-- WHO STARTED EACH ASSESSMENT. sessions.user_id already says which account; assessed_as
-- says on what footing, written once when the session is created: 'maker' for the piece's
-- own maker, 'manager' for the account the view above named at that moment. A wrong pick
-- (a manager assessing a different piece from the one the worker logged) has no undo
-- (Nathan, 16 November 2026), so DIL finds them by query on this column. Existing sessions
-- on their own maker's piece are back-filled as 'maker'.
--
-- THE FINAL ASSESSMENT. A customer report needs one assessment of the finished piece by the
-- studio's owner or manager first (row 27). is_final marks it, written once at creation.
--
-- WHICH STUDIO TODAY. fundi_studio_days holds one answer per account and Nairobi day to
-- "Which studio are you at today?" (row 9): an entry of the account's own list, or none for
-- its own workshop. It changes no piece's label.
--
-- DELETION. anonymise_user is not redefined. Nothing here names or locates a person: the
-- new columns and rows hold ids, times and flags, and the studio entries they point at have
-- their names and numbers cleared by the existing definition (V31), which also ends every
-- link the view reads.
--
-- The server tests have no database, so the view, the CHECKs and the indexes are proved by
-- the self-test below, which tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

-- ------------------------------------------------------------------- the label

ALTER TABLE pieces
    ADD COLUMN IF NOT EXISTS studio_entry_id uuid REFERENCES worker_studios(id),
    ADD COLUMN IF NOT EXISTS studio_chosen_at timestamptz;

-- A label is a choice, so it has a time. "My own job" is a choice with no entry.
ALTER TABLE pieces
    ADD CONSTRAINT pieces_studio_is_chosen
        CHECK (studio_entry_id IS NULL OR studio_chosen_at IS NOT NULL);

CREATE INDEX pieces_studio_entry_idx ON pieces (studio_entry_id) WHERE studio_entry_id IS NOT NULL;

CREATE TABLE piece_studio_changes (
    id                  bigserial PRIMARY KEY,
    piece_id            uuid NOT NULL REFERENCES pieces(id),
    -- The account that made the change: always the piece's maker.
    user_id             uuid NOT NULL REFERENCES users(id),
    kind                text NOT NULL,
    -- Null on a first choice, which had no label before it, and for "My own job".
    old_studio_entry_id uuid REFERENCES worker_studios(id),
    new_studio_entry_id uuid REFERENCES worker_studios(id),
    -- The server's clock, never the phone's (see V18).
    at                  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT piece_studio_changes_kind_known
        CHECK (kind IN ('first_choice', 'correction'))
);

CREATE UNIQUE INDEX piece_studio_changes_one_first_choice
    ON piece_studio_changes (piece_id) WHERE kind = 'first_choice';
CREATE UNIQUE INDEX piece_studio_changes_one_correction
    ON piece_studio_changes (piece_id) WHERE kind = 'correction';

CREATE TRIGGER piece_studio_changes_append_only
    BEFORE UPDATE OR DELETE ON piece_studio_changes
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();

CREATE TRIGGER piece_studio_changes_no_truncate
    BEFORE TRUNCATE ON piece_studio_changes
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_row_change();

-- The account that runs the studio each piece is labelled for, while the match holds. Read
-- it, never store it, as worker_links. One row per piece at most: users.phone is unique,
-- and worker_links has one row per pair.
CREATE VIEW piece_managers AS
SELECT p.id              AS piece_id,
       p.user_id         AS worker_user_id,
       l.studio_user_id  AS manager_user_id,
       p.studio_entry_id AS studio_entry_id
  FROM pieces p
  -- The label, an entry of the maker's own live list.
  JOIN worker_studios ws
    ON ws.id = p.studio_entry_id AND ws.user_id = p.user_id
   AND ws.archived_at IS NULL AND ws.phone IS NOT NULL
  -- The studio's account, by the entry's number.
  JOIN users s ON s.phone = ws.phone AND s.deleted_at IS NULL
  -- And the two-sided match between the maker and that account.
  JOIN worker_links l ON l.worker_user_id = p.user_id AND l.studio_user_id = s.id;

-- ------------------------------------------------------------- the assessments

ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS assessed_as text,
    ADD COLUMN IF NOT EXISTS is_final boolean NOT NULL DEFAULT false;

ALTER TABLE sessions
    ADD CONSTRAINT sessions_assessed_as_known
        CHECK (assessed_as IS NULL OR assessed_as IN ('maker', 'manager'));

-- Only Fundi Bora has pieces, and so only it has a final assessment.
ALTER TABLE sessions
    ADD CONSTRAINT sessions_final_is_fundi
        CHECK (NOT is_final OR audience = 'fundi');

-- Before this, only a piece's maker could assess it (V14's ensureSession resolved anybody
-- else's piece id to null), so every linked session was the maker's.
UPDATE sessions s
   SET assessed_as = 'maker'
  FROM pieces p
 WHERE p.id = s.piece_id AND p.user_id = s.user_id AND s.assessed_as IS NULL;

CREATE INDEX sessions_final_idx ON sessions (piece_id) WHERE is_final;

-- ------------------------------------------------------------ which studio today

CREATE TABLE fundi_studio_days (
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- The Nairobi calendar day the answer was received on.
    day             date NOT NULL,
    -- An entry of this account's own list; null for its own workshop.
    studio_entry_id uuid REFERENCES worker_studios(id),
    answered_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, day)
);

-- Self-test of the view and the constraints. Three throwaway accounts: a studio, a worker
-- who lists it, and a third the worker lists but who does not list the worker. Everything is
-- rolled back by the known error at the end. Country code 999 is assigned to nobody (V31).
DO $$
DECLARE
    studio   uuid := '00000000-0000-4000-8000-000000000340';
    worker   uuid := '00000000-0000-4000-8000-000000000341';
    other    uuid := '00000000-0000-4000-8000-000000000342';
    roster   uuid := '00000000-0000-4000-8000-000000000343';
    listing  uuid := '00000000-0000-4000-8000-000000000344';
    elsewhere uuid := '00000000-0000-4000-8000-000000000345';
    labelled uuid := '00000000-0000-4000-8000-000000000346';
    own_job  uuid := '00000000-0000-4000-8000-000000000347';
    unlinked uuid := '00000000-0000-4000-8000-000000000348';
    never    uuid := '00000000-0000-4000-8000-000000000349';
    refused  boolean;
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (studio, 'fundi', '+999000000041', 'self-test');
        INSERT INTO users (id, audience, phone, password_hash) VALUES (worker, 'fundi', '+999000000042', 'self-test');
        INSERT INTO users (id, audience, phone, password_hash) VALUES (other,  'fundi', '+999000000043', 'self-test');

        -- The two-sided match between the worker and the studio; the worker also lists the
        -- third account, which does not list the worker back.
        INSERT INTO workers (id, user_id, name, phone) VALUES (roster, studio, 'Kamau', '+999000000042');
        INSERT INTO worker_studios (id, user_id, name, phone) VALUES (listing, worker, 'Mama Njeri''s workshop', '+999000000041');
        INSERT INTO worker_studios (id, user_id, name, phone) VALUES (elsewhere, worker, 'Otieno''s', '+999000000043');

        INSERT INTO pieces (id, user_id, item_type_id, studio_entry_id, studio_chosen_at)
        VALUES (labelled, worker, 'wooden-table', listing, now()),
               (own_job,  worker, 'wooden-table', NULL, now()),
               (unlinked, worker, 'wooden-table', elsewhere, now()),
               (never,    worker, 'wooden-table', NULL, NULL);

        -- Only the piece labelled for the linked studio has a manager, and it is the studio.
        IF (SELECT count(*) FROM piece_managers) <> 1 THEN
            RAISE EXCEPTION 'V34 self-test: piece_managers holds % rows, not 1', (SELECT count(*) FROM piece_managers);
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM piece_managers
             WHERE piece_id = labelled AND manager_user_id = studio AND worker_user_id = worker
        ) THEN
            RAISE EXCEPTION 'V34 self-test: the labelled piece does not name its studio as manager';
        END IF;

        -- The studio ends the link from its side: the access ends with it.
        UPDATE workers SET archived_at = now() WHERE id = roster;
        IF EXISTS (SELECT 1 FROM piece_managers) THEN
            RAISE EXCEPTION 'V34 self-test: an ended link kept a manager on the piece';
        END IF;
        UPDATE workers SET archived_at = NULL WHERE id = roster;

        -- So does the worker archiving the entry the piece is labelled with.
        UPDATE worker_studios SET archived_at = now() WHERE id = listing;
        IF EXISTS (SELECT 1 FROM piece_managers) THEN
            RAISE EXCEPTION 'V34 self-test: an archived label kept a manager on the piece';
        END IF;
        UPDATE worker_studios SET archived_at = NULL WHERE id = listing;

        -- A label with no choice behind it.
        refused := false;
        BEGIN
            INSERT INTO pieces (id, user_id, item_type_id, studio_entry_id)
            VALUES ('00000000-0000-4000-8000-00000000034a', worker, 'wooden-table', listing);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V34 self-test: pieces accepted a label with no studio_chosen_at';
        END IF;

        -- One first choice, then one correction, and no second of either.
        INSERT INTO piece_studio_changes (piece_id, user_id, kind, old_studio_entry_id, new_studio_entry_id)
        VALUES (never, worker, 'first_choice', NULL, listing);
        INSERT INTO piece_studio_changes (piece_id, user_id, kind, old_studio_entry_id, new_studio_entry_id)
        VALUES (never, worker, 'correction', listing, NULL);
        refused := false;
        BEGIN
            INSERT INTO piece_studio_changes (piece_id, user_id, kind, old_studio_entry_id, new_studio_entry_id)
            VALUES (never, worker, 'correction', NULL, listing);
        EXCEPTION WHEN unique_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V34 self-test: piece_studio_changes accepted a second correction';
        END IF;
        refused := false;
        BEGIN
            INSERT INTO piece_studio_changes (piece_id, user_id, kind, old_studio_entry_id, new_studio_entry_id)
            VALUES (never, worker, 'first_choice', NULL, elsewhere);
        EXCEPTION WHEN unique_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V34 self-test: piece_studio_changes accepted a second first choice';
        END IF;
        refused := false;
        BEGIN
            INSERT INTO piece_studio_changes (piece_id, user_id, kind) VALUES (own_job, worker, 'relabel');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V34 self-test: piece_studio_changes accepted an unknown kind';
        END IF;

        -- The record cannot be changed or emptied.
        refused := false;
        BEGIN
            UPDATE piece_studio_changes SET kind = 'correction' WHERE piece_id = never AND kind = 'first_choice';
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V34 self-test: piece_studio_changes accepted an UPDATE';
        END IF;
        refused := false;
        BEGIN
            DELETE FROM piece_studio_changes WHERE piece_id = never;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V34 self-test: piece_studio_changes accepted a DELETE';
        END IF;
        refused := false;
        BEGIN
            TRUNCATE piece_studio_changes;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V34 self-test: piece_studio_changes accepted a TRUNCATE';
        END IF;

        -- The manager's assessment of the worker's piece, and the final one.
        INSERT INTO sessions (id, user_id, item_type_id, audience, piece_id, assessed_as, is_final)
        VALUES ('00000000-0000-4000-8000-00000000034b', studio, 'wooden-table', 'fundi', labelled, 'manager', true);
        refused := false;
        BEGIN
            INSERT INTO sessions (id, user_id, item_type_id, audience, piece_id, assessed_as)
            VALUES ('00000000-0000-4000-8000-00000000034c', studio, 'wooden-table', 'fundi', labelled, 'owner');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V34 self-test: sessions accepted an unknown assessed_as';
        END IF;
        refused := false;
        BEGIN
            INSERT INTO sessions (id, user_id, item_type_id, audience, is_final)
            VALUES ('00000000-0000-4000-8000-00000000034d', studio, 'wooden-table', 'buyer', true);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V34 self-test: sessions accepted a final Kagua assessment';
        END IF;

        -- One answer per account and day.
        INSERT INTO fundi_studio_days (user_id, day, studio_entry_id) VALUES (worker, current_date, listing);
        refused := false;
        BEGIN
            INSERT INTO fundi_studio_days (user_id, day, studio_entry_id) VALUES (worker, current_date, NULL);
        EXCEPTION WHEN unique_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V34 self-test: fundi_studio_days accepted a second answer for one day';
        END IF;

        RAISE EXCEPTION 'V34 self-test passed' USING ERRCODE = 'P0V34';
    EXCEPTION WHEN SQLSTATE 'P0V34' THEN
        -- Everything inside the block above, the three accounts included, is rolled back.
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V34__pieces_studios')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
