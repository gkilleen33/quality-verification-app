-- Kagua server schema, version 10.
--
-- Evaluators, and what they thought of the assistant.
--
-- A tester is one of our own evaluators rather than a buyer. The flag matters in three
-- places: their allowance is higher because assessing furniture is their job, they are
-- asked what they made of the assistant afterwards, and their assessments have to be
-- separable from real ones in any analysis. Mixing staff walkthroughs into pilot findings
-- would quietly overstate how well the thing works.

BEGIN;

-- Set from the invite code they registered with, and editable afterwards from the portal:
-- somebody hired later should not need a new account.
ALTER TABLE users ADD COLUMN is_tester boolean NOT NULL DEFAULT false;

-- Carried by the code rather than typed at registration. The customer cannot choose to be
-- an evaluator, and an evaluator should not have to remember to tick anything.
ALTER TABLE invite_codes ADD COLUMN grants_tester boolean NOT NULL DEFAULT false;

CREATE INDEX users_tester_idx ON users (is_tester) WHERE is_tester;

-- One row per assessment. session_id is the merge key in both directions, so a join gets
-- from an evaluation to its critique and back with no lookup table in between.
CREATE TABLE tester_feedback (
    -- The session, not a surrogate: an assessment has at most one critique, and making that
    -- the primary key means a second submission updates rather than duplicates.
    session_id      uuid PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    -- Kept alongside so feedback survives its author's account being closed, which is the
    -- same reasoning as usage_events: the research record outlives the profile.
    user_id         uuid REFERENCES users(id) ON DELETE SET NULL,
    -- 'yes' | 'no' | 'unsure'. A constraint rather than an enum type so adding an option
    -- later is one migration and not a type rewrite.
    mistakes        text NOT NULL CHECK (mistakes IN ('yes', 'no', 'unsure')),
    -- Only meaningful when mistakes = 'yes', and not required even then: an evaluator who
    -- cannot articulate what was wrong should still be able to record that it was.
    mistakes_detail text,
    -- 1-5. "Not helpful at all" to "very helpful".
    advice_stars    smallint NOT NULL CHECK (advice_stars BETWEEN 1 AND 5),
    -- 1-10 on the furniture itself, 10 being no defects. Deliberately separate from the
    -- assistant's own verdict: comparing the two is the entire point of the exercise.
    item_quality    smallint NOT NULL CHECK (item_quality BETWEEN 1 AND 10),
    extra_feedback  text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX tester_feedback_user_idx ON tester_feedback (user_id);
CREATE INDEX tester_feedback_created_idx ON tester_feedback (created_at DESC);

INSERT INTO schema_migrations (version) VALUES ('V10__testers')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
