-- Kagua server schema, version 44.
--
-- Fundi Bora v0.7.1: whether a piece is finished (release scope row 55; Nathan, 24 November
-- 2026: "we need to save it in the server").
--
-- THE STAGE. A piece's page shows a Status row, "In progress" or "Finished", that the fundi
-- can edit. The fundi's answer lives on the piece: pieces.stage, 'in_progress' or
-- 'finished', and pieces.stage_set_at, the server's time of the answer. Both are null until
-- the fundi first sets it; until then, and after any later assessment, the phone shows the
-- stage the latest assessment implies (its purpose: checking work in progress means in
-- progress, any other purpose finished). The newer of the two shows. Only the piece's maker
-- sets it; the store checks that on every write, since a CHECK cannot read the caller.
--
-- THE RECORD. Every change is a row in piece_stage_changes, append-only, with the old and
-- new stage, the account, and the server's time, as piece_studio_changes keeps the studio
-- label's (V34). The current value on pieces is what the phone reads; the table is what
-- research reads to see when a fundi said a piece was finished.
--
-- WHAT IT DOES NOT CHANGE. The stage decides nothing else: a customer report still needs a
-- final assessment (row 27, sessions.is_final), whatever the stage says.
--
-- REPORTS SENT (row 56). The piece's page lists the reports the server issued on the piece
-- for its maker. V32's reports table already holds the kind, the piece, the account and the
-- time of each, so nothing is added for it here.
--
-- DELETION. anonymise_user is not redefined. A stage is a word and a time; the change rows
-- hold ids, words and times. Nothing here names or locates a person.
--
-- The server tests have no database, so the CHECKs, the index and the triggers are proved by
-- the self-test below, which tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

ALTER TABLE pieces
    ADD COLUMN IF NOT EXISTS stage text,
    ADD COLUMN IF NOT EXISTS stage_set_at timestamptz;

-- A closed vocabulary, mirrored by PieceStageTest (PieceStage in :shared).
ALTER TABLE pieces
    ADD CONSTRAINT pieces_stage_known
        CHECK (stage IS NULL OR stage IN ('in_progress', 'finished'));

-- An answer has a time, and a time is an answer's.
ALTER TABLE pieces
    ADD CONSTRAINT pieces_stage_set_together
        CHECK ((stage IS NULL) = (stage_set_at IS NULL));

CREATE TABLE piece_stage_changes (
    id          bigserial PRIMARY KEY,
    piece_id    uuid NOT NULL REFERENCES pieces(id),
    -- The account that made the change: always the piece's maker.
    user_id     uuid NOT NULL REFERENCES users(id),
    -- Null on the first answer, which had none before it.
    old_stage   text,
    new_stage   text NOT NULL,
    -- The server's clock, never the phone's (see V18).
    at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT piece_stage_changes_old_known
        CHECK (old_stage IS NULL OR old_stage IN ('in_progress', 'finished')),
    CONSTRAINT piece_stage_changes_new_known
        CHECK (new_stage IN ('in_progress', 'finished'))
);

CREATE INDEX piece_stage_changes_piece_idx ON piece_stage_changes (piece_id, at);

CREATE TRIGGER piece_stage_changes_append_only
    BEFORE UPDATE OR DELETE ON piece_stage_changes
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();

CREATE TRIGGER piece_stage_changes_no_truncate
    BEFORE TRUNCATE ON piece_stage_changes
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_row_change();

-- The reports a piece's page lists (row 56): the piece's own, by the account that issued
-- them, newest first.
CREATE INDEX IF NOT EXISTS reports_piece_user_idx ON reports (piece_id, user_id, issued_at DESC)
    WHERE piece_id IS NOT NULL;

-- Self-test: the stage's two CHECKs, the history's CHECKs, and the append-only triggers. Run
-- inside its own block and rolled back by the known error at the end. Country code 999 is
-- assigned to nobody (V31).
DO $$
DECLARE
    maker   uuid := '00000000-0000-4000-8000-000000000440';
    piece   uuid := '00000000-0000-4000-8000-000000000441';
    refused boolean;
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (maker, 'fundi', '+999000000044', 'self-test');
        INSERT INTO pieces (id, user_id, item_type_id) VALUES (piece, maker, 'wooden-table');

        -- A known stage with its time is taken.
        UPDATE pieces SET stage = 'finished', stage_set_at = now() WHERE id = piece;

        -- An unknown stage is refused.
        refused := false;
        BEGIN
            UPDATE pieces SET stage = 'done', stage_set_at = now() WHERE id = piece;
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V44 self-test: pieces accepted an unknown stage';
        END IF;

        -- A stage without its time, and a time without a stage, are refused.
        refused := false;
        BEGIN
            UPDATE pieces SET stage = 'in_progress', stage_set_at = NULL WHERE id = piece;
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V44 self-test: pieces accepted a stage with no time';
        END IF;
        refused := false;
        BEGIN
            UPDATE pieces SET stage = NULL, stage_set_at = now() WHERE id = piece;
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V44 self-test: pieces accepted a time with no stage';
        END IF;

        -- The history: a first answer and a change are taken; an unknown stage is not.
        INSERT INTO piece_stage_changes (piece_id, user_id, old_stage, new_stage) VALUES (piece, maker, NULL, 'finished');
        INSERT INTO piece_stage_changes (piece_id, user_id, old_stage, new_stage) VALUES (piece, maker, 'finished', 'in_progress');
        refused := false;
        BEGIN
            INSERT INTO piece_stage_changes (piece_id, user_id, old_stage, new_stage) VALUES (piece, maker, 'in_progress', 'sold');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V44 self-test: piece_stage_changes accepted an unknown new stage';
        END IF;
        refused := false;
        BEGIN
            INSERT INTO piece_stage_changes (piece_id, user_id, old_stage, new_stage) VALUES (piece, maker, 'sold', 'finished');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V44 self-test: piece_stage_changes accepted an unknown old stage';
        END IF;

        -- The record cannot be changed or emptied.
        refused := false;
        BEGIN
            UPDATE piece_stage_changes SET new_stage = 'finished' WHERE piece_id = piece;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V44 self-test: piece_stage_changes accepted an UPDATE';
        END IF;
        refused := false;
        BEGIN
            DELETE FROM piece_stage_changes WHERE piece_id = piece;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V44 self-test: piece_stage_changes accepted a DELETE';
        END IF;
        refused := false;
        BEGIN
            TRUNCATE piece_stage_changes;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V44 self-test: piece_stage_changes accepted a TRUNCATE';
        END IF;

        RAISE EXCEPTION 'V44 self-test passed' USING ERRCODE = 'P0V44';
    EXCEPTION WHEN SQLSTATE 'P0V44' THEN
        -- Everything inside the block above, the account included, is rolled back.
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V44__piece_stage')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
