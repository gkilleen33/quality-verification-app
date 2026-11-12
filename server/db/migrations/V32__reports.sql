-- Kagua server schema, version 32.
--
-- The PDF reports Fundi Bora sends about a piece (plan v7 §3 and §5; Nathan, 12 November
-- 2026): one row per report the server issued, cached or freshly rendered, so the rows count
-- downloads started and a Phase Two check can look a report up by its id or its sha256.
--
-- WHY A ROW HOLDS A SNAPSHOT. The problems a report printed are read from the stored replies
-- on each render and never stored on their own; a client-deleted assessment is purged after
-- seven days (V11), and the parser can change. defect_refs alone would then point at nothing,
-- so defect_snapshot keeps what was printed: each assessment's level and headline, each
-- problem's title, severity and part, and, since 12 November 2026, the worker names printed
-- under each part and the name of the account that made the report. The names are part of
-- what was sent, which is the research record.
--
-- SIX KINDS, all in the CHECK now so later PRs need no migration: customer (every assessment,
-- no names), part_issues and part_fixes (one assessment, the chosen parts), full (one
-- assessment, every part), to_manager (the full report sent by a worker to a linked studio),
-- and worker_work (one linked roster worker's work across pieces, from the worker's page).
-- A worker_work report names the roster worker it is about and no piece; every other kind
-- names its piece and no worker. The part kinds carry their parts; no other kind does.
--
-- DELETION. anonymise_user is redefined with every clearing from V31, plus the deleting
-- account's reports: the snapshot is emptied and the printed names cleared. The rows stay,
-- so the count of reports and their hashes survive.
--
-- The server tests have no database, so the CHECKs and the deletion are proved by the
-- self-tests below, which tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

CREATE TABLE reports (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- The piece, for every kind but worker_work, which spans pieces.
    piece_id        uuid REFERENCES pieces(id) ON DELETE CASCADE,
    -- Who issued it.
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind            text NOT NULL,
    -- Every assessment covered: for the customer kind always all of the piece's.
    session_ids     uuid[] NOT NULL,
    -- Every problem printed, as <message_id>:<ordinal>, the message being the newest reply
    -- the problem list was read from.
    defect_refs     text[] NOT NULL,
    -- What was printed. Emptied by anonymise_user.
    defect_snapshot jsonb NOT NULL,
    -- The parts a part report covers; null for every other kind.
    parts           text[],
    -- The roster worker a worker_work report is about; null for every other kind.
    worker_id       uuid REFERENCES workers(id) ON DELETE CASCADE,
    -- Every name the report printed: the workers under each part and the account that made
    -- it. Nullable only so anonymise_user can clear it; empty for a customer report.
    printed_names   text[],
    -- Of the bytes returned. Lowercase hex, as BlobStore writes a hash.
    sha256          char(64) NOT NULL,
    byte_size       integer NOT NULL,
    issued_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reports_kind_known
        CHECK (kind IN ('customer', 'part_issues', 'part_fixes', 'full', 'to_manager', 'worker_work')),
    CONSTRAINT reports_parts_known
        CHECK (parts IS NULL OR (
            cardinality(parts) >= 1
            AND parts <@ ARRAY['frame', 'joinery', 'upholstery', 'finishing', 'assembly', 'other']::text[]
        )),
    CONSTRAINT reports_parts_match_kind
        CHECK ((kind IN ('part_issues', 'part_fixes')) = (parts IS NOT NULL)),
    CONSTRAINT reports_worker_matches_kind
        CHECK ((kind = 'worker_work') = (worker_id IS NOT NULL)),
    CONSTRAINT reports_piece_matches_kind
        CHECK ((kind = 'worker_work') = (piece_id IS NULL)),
    CONSTRAINT reports_sha256_hex
        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT reports_byte_size_positive
        CHECK (byte_size > 0)
);

CREATE INDEX reports_piece_idx ON reports (piece_id, issued_at);
CREATE INDEX reports_user_idx ON reports (user_id, issued_at);
CREATE INDEX reports_sha256_idx ON reports (sha256);

-- Self-test of the CHECKs, before anonymise_user is redefined below (MigrationsTest reads
-- every UPDATE after that definition as part of the function). One throwaway account with
-- a piece and a roster entry; everything is rolled back by the known error at the end.
DO $$
DECLARE
    maker  uuid := '00000000-0000-4000-8000-000000000320';
    piece  uuid := '00000000-0000-4000-8000-000000000321';
    worker uuid := '00000000-0000-4000-8000-000000000322';
    sha    text := repeat('ab', 32);
    refused boolean;
BEGIN
    BEGIN
        -- A number needs a credential beside it (users_credentials_are_paired, V5); the hash
        -- is any text, since nothing signs in as this account.
        INSERT INTO users (id, audience, phone, password_hash) VALUES (maker, 'fundi', '+999000000031', 'self-test');
        INSERT INTO pieces (id, user_id, item_type_id, label) VALUES (piece, maker, 'wooden-table', 'Hotel table');
        INSERT INTO workers (id, user_id, name) VALUES (worker, maker, 'Kamau');

        -- Each kind in the shape its row must take.
        INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, sha256, byte_size)
        VALUES (piece, maker, 'customer', '{}', '{}', '{}', sha, 1);
        INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, parts, printed_names, sha256, byte_size)
        VALUES (piece, maker, 'part_issues', '{}', '{}', '{}', ARRAY['joinery'], ARRAY['Kamau'], sha, 1);
        INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, parts, sha256, byte_size)
        VALUES (piece, maker, 'part_fixes', '{}', '{}', '{}', ARRAY['joinery', 'finishing'], sha, 1);
        INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, sha256, byte_size)
        VALUES (piece, maker, 'full', '{}', '{}', '{}', sha, 1);
        INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, sha256, byte_size)
        VALUES (piece, maker, 'to_manager', '{}', '{}', '{}', sha, 1);
        INSERT INTO reports (user_id, kind, session_ids, defect_refs, defect_snapshot, worker_id, sha256, byte_size)
        VALUES (maker, 'worker_work', '{}', '{}', '{}', worker, sha, 1);

        -- An unknown kind.
        refused := false;
        BEGIN
            INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, sha256, byte_size)
            VALUES (piece, maker, 'summary', '{}', '{}', '{}', sha, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V32 self-test: reports accepted an unknown kind';
        END IF;

        -- Parts on a kind that takes none.
        refused := false;
        BEGIN
            INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, parts, sha256, byte_size)
            VALUES (piece, maker, 'customer', '{}', '{}', '{}', ARRAY['joinery'], sha, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V32 self-test: reports accepted parts on a customer report';
        END IF;

        -- A part report with no parts, and one with a part that is not a part.
        refused := false;
        BEGIN
            INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, sha256, byte_size)
            VALUES (piece, maker, 'part_issues', '{}', '{}', '{}', sha, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V32 self-test: reports accepted a part report with no parts';
        END IF;
        refused := false;
        BEGIN
            INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, parts, sha256, byte_size)
            VALUES (piece, maker, 'part_issues', '{}', '{}', '{}', ARRAY['legs'], sha, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V32 self-test: reports accepted a part that is not a part';
        END IF;

        -- A worker report naming a piece, and a piece report naming a worker.
        refused := false;
        BEGIN
            INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, worker_id, sha256, byte_size)
            VALUES (piece, maker, 'worker_work', '{}', '{}', '{}', worker, sha, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V32 self-test: reports accepted a worker report with a piece';
        END IF;
        refused := false;
        BEGIN
            INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, worker_id, sha256, byte_size)
            VALUES (piece, maker, 'full', '{}', '{}', '{}', worker, sha, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V32 self-test: reports accepted a full report naming a worker';
        END IF;

        -- A hash that is not a hash.
        refused := false;
        BEGIN
            INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, sha256, byte_size)
            VALUES (piece, maker, 'customer', '{}', '{}', '{}', upper(sha), 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V32 self-test: reports accepted an uppercase hash';
        END IF;

        RAISE EXCEPTION 'V32 reports self-test passed' USING ERRCODE = 'P0V32';
    EXCEPTION WHEN SQLSTATE 'P0V32' THEN
        -- Everything inside the block above, the account included, is rolled back.
        NULL;
    END;
END
$$;

-- As V31, plus the deleting account's reports: the snapshot emptied and the printed names
-- cleared.
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

    -- The reports this account sent (V32): the names it printed, and the snapshot of what
    -- it said, which holds those names and verdict text. The rows stay, with their hashes.
    UPDATE reports SET
        defect_snapshot = '{}'::jsonb,
        printed_names   = NULL
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
-- reason the first block gives. One account with a report that printed names; after
-- anonymise_user the row is still there with its hash, and says no name.
DO $$
DECLARE
    maker uuid := '00000000-0000-4000-8000-000000000330';
    piece uuid := '00000000-0000-4000-8000-000000000331';
    sha   text := repeat('cd', 32);
    row_count integer;
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (maker, 'fundi', '+999000000032', 'self-test');
        INSERT INTO pieces (id, user_id, item_type_id) VALUES (piece, maker, 'wooden-table');
        INSERT INTO reports (piece_id, user_id, kind, session_ids, defect_refs, defect_snapshot, printed_names, sha256, byte_size)
        VALUES (piece, maker, 'full', '{}', '{}',
                '{"workers":[{"part":"joinery","name":"Kamau"}],"made_by":"Wanjiru"}',
                ARRAY['Kamau', 'Wanjiru'], sha, 1);

        IF NOT anonymise_user(maker) THEN
            RAISE EXCEPTION 'V32 self-test: anonymise_user did not anonymise the maker';
        END IF;
        SELECT count(*) INTO row_count FROM reports WHERE user_id = maker AND sha256 = sha;
        IF row_count <> 1 THEN
            RAISE EXCEPTION 'V32 self-test: anonymise_user lost the report row';
        END IF;
        IF EXISTS (
            SELECT 1 FROM reports WHERE user_id = maker
             AND (printed_names IS NOT NULL OR defect_snapshot <> '{}'::jsonb)
        ) THEN
            RAISE EXCEPTION 'V32 self-test: anonymise_user left a printed name or the snapshot behind';
        END IF;

        RAISE EXCEPTION 'V32 deletion self-test passed' USING ERRCODE = 'P0V32';
    EXCEPTION WHEN SQLSTATE 'P0V32' THEN
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V32__reports')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
