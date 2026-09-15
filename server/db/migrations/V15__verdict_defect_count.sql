-- Kagua server schema, version 15.
--
-- How many defects a verdict found.
--
-- Needed for the two rates a maker's record is judged on:
--
--   "7 of the last 10 pieces came back with no defects the first time."
--   "Of the last 10 pieces with a defect flagged, 6 were confirmed repaired."
--
-- Both are computable from three things — which piece a session was of, in what order,
-- and whether that assessment found any defects. The first two arrived in V14. This is
-- the third, and it is the only one that was being parsed and thrown away: the route
-- already reads the verdict to store its level and language, and the defect list is
-- sitting in the same parsed object.
--
-- NULL AND ZERO ARE DIFFERENT, AND THE DIFFERENCE IS THE WHOLE POINT.
--
--   NULL = no verdict was recorded for this session. Either it has not reached one yet,
--          or it never will — every full assessment between 3 and 8 September produced no
--          verdict at all, because the reply was truncated at max_tokens and the
--          unparseable block was dropped.
--   0    = a verdict was recorded and it found nothing wrong.
--
-- Conflating them would have counted every one of those truncated assessments as a
-- defect-free piece, which is the exact opposite of what happened. Any query over this
-- column has to say which it means; `IS NOT NULL` is not optional decoration.
--
-- Deliberately a count and not a defects table. The two rates need only "any or none",
-- and a per-defect table — area, severity, one row each — would be the right way to ask
-- *which* defects a shop tends to make. That is a different question, worth its own
-- change, and worth having the first rates in hand before designing for it.

BEGIN;

ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS verdict_defect_count integer;

ALTER TABLE sessions
    -- A negative count is not a thing. A verdict with no level but a defect count, or the
    -- reverse, means something wrote half a verdict — worth refusing at the column rather
    -- than discovering in an average.
    ADD CONSTRAINT sessions_defect_count_sane
        CHECK (verdict_defect_count IS NULL OR verdict_defect_count >= 0),
    ADD CONSTRAINT sessions_defect_count_needs_verdict
        CHECK (verdict_defect_count IS NULL OR verdict_level_id IS NOT NULL);

-- The rates walk a maker's pieces in assessment order, so this is the access path.
CREATE INDEX sessions_piece_ordered_idx
    ON sessions (piece_id, created_at)
    WHERE piece_id IS NOT NULL;

INSERT INTO schema_migrations (version) VALUES ('V15__verdict_defect_count')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
