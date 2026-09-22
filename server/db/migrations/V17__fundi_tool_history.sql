-- Kagua server schema, version 17.
--
-- What happened to a maker's tools, and not just what they own today.
--
-- `fundi_tools` holds current state, and the first version of the save path replaced it
-- wholesale on every profile update: delete every row, insert the new answers. That threw
-- away the most interesting thing in the table.
--
-- A shop that owned a circular saw last March and does not now has told us something. It
-- may have been sold to cover a bad month, or broken and never replaced, or stolen — and
-- each of those is a different story about the business. The reverse matters as much: a
-- maker who bought a marking gauge after the coaching named its absence as the cause of a
-- defect is the clearest evidence the intervention did anything at all. Neither is
-- recoverable from a row that was overwritten.
--
-- So: `fundi_tools` stays as the current answer and is upserted, never deleted, and every
-- transition is appended here.
--
-- WHY A SEPARATE TABLE RATHER THAN VERSIONING fundi_tools
--
-- Because the coaching reads current state on every assessment and must not have to pick
-- the latest of several rows to do it. The history is read rarely, by us, in analysis;
-- the state is read constantly, by the thing a maker is standing in front of. Keeping the
-- hot path a single row per tool keeps that honest.

BEGIN;

CREATE TABLE fundi_tool_changes (
    id             bigserial PRIMARY KEY,
    user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind           text NOT NULL,
    -- NULL means this is the first time we were told about this tool at all, which is not
    -- the same as having had none of it: "I have never mentioned a router" and "I used to
    -- own a router and sold it" are different facts, and only the second has a from.
    from_ownership text,
    to_ownership   text NOT NULL,
    -- Why, when we asked and they answered. Null is ordinary and will be the common case
    -- until the setup screen asks — and it must stay allowed even after, because a maker
    -- who does not want to say should still be able to change the answer.
    reason         text,
    -- Their own words, when the closed set does not fit. Kept alongside the reason rather
    -- than instead of it, for the same argument as fundi_skill_notes: a free-text field
    -- that can overwrite a coded one turns the column into a mixture of the two.
    note           text,
    changed_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fundi_tool_changes_kind_known
        CHECK (kind IN ('hand_saw', 'hammer_mallet', 'chisels', 'plane_no4',
                        'circular_saw', 'router', 'marking_gauge', 'clamps',
                        'square', 'drill', 'sander', 'other')),
    CONSTRAINT fundi_tool_changes_from_known
        CHECK (from_ownership IS NULL
               OR from_ownership IN ('owned', 'borrowed', 'none')),
    CONSTRAINT fundi_tool_changes_to_known
        CHECK (to_ownership IN ('owned', 'borrowed', 'none')),
    -- A row saying nothing changed is not history, it is noise, and it would make any
    -- count of "tools sold this quarter" wrong by however many times somebody re-saved
    -- their profile without touching it.
    CONSTRAINT fundi_tool_changes_is_a_change
        CHECK (from_ownership IS DISTINCT FROM to_ownership),
    -- A guess, and flagged as one. These are the reasons that seemed to matter for
    -- furniture workshops, but nobody with a workshop has looked at the list yet — it
    -- belongs with the prompt and setup-copy review in issue #41. Adding one is a
    -- migration, which is honest: the setup screen has to offer it too.
    CONSTRAINT fundi_tool_changes_reason_known
        CHECK (reason IS NULL OR reason IN ('bought', 'gift', 'sold', 'broke',
                                            'stolen', 'returned', 'other'))
);

CREATE INDEX fundi_tool_changes_user_idx
    ON fundi_tool_changes (user_id, changed_at DESC);

-- The analysis access path: "every tool of this kind that stopped being owned".
CREATE INDEX fundi_tool_changes_kind_idx
    ON fundi_tool_changes (kind, changed_at);

-- Deletion, as in V14: this cascades from users, so an account that is genuinely deleted
-- takes its tool history with it. The decision that deletion hides rather than erases has
-- no public surface to act on yet, and when badges arrive they need a visibility flag in
-- the same migration.

INSERT INTO schema_migrations (version) VALUES ('V17__fundi_tool_history')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
