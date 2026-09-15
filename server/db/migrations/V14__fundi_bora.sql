-- Kagua server schema, version 14.
--
-- Fundi Bora: the producer-facing half of the project.
--
-- Design brief and mockup: https://ubora-e1l.pages.dev/ (03- and 04-fundi-bora-*).
--
-- The brief's own sentence is the architectural decision: Fundi Bora "runs the same
-- assessment engine as Kagua, but points it inward". Same capture pipeline, same photo
-- storage, same Claude client, same conversation tables. What differs is who is asking,
-- what the model is asked to produce, and — new here — that the answers accumulate into a
-- record of one maker's habits over months rather than ending with one piece.
--
-- So this migration adds no second copy of anything. It adds:
--   * an audience dimension to the tables both halves share
--   * the identity of a physical piece, so it can be assessed more than once
--   * the maker's own context: workshop, tools, goals
--   * the skill file: per-dimension observations over time
--
-- WHAT IS DELIBERATELY NOT HERE
--
-- No current-level or certification-progress table. Both are derived: the level is a
-- function of the observations, and "3 of 10" is a count of qualifying pieces. Storing
-- either would freeze a rubric that is going to change, and the whole argument for
-- recording measurements rather than scores is that the aggregation stays a choice.
--
-- No badge or publication table yet. Badges are the first thing in this project that
-- would be shown to somebody other than the person who created the data, and the
-- deletion decision — hide from public, retain the record — only has meaning once
-- something is published. That table should arrive with the feature that needs it, and
-- it will need a visibility flag from its first day, not added later.
--
-- No marketplace, no voice, no sketch-to-workplan. Extended is deferred; the one piece
-- of it worth preserving is written up in issue #33.

BEGIN;

-- ---------------------------------------------------------------- audience

-- Which half of the project a row belongs to.
--
-- A column rather than separate tables, because a fundi's assessment IS a Kagua
-- assessment with the answer read differently: the photographs, the shot plan and the
-- defects are the same shape, and splitting the tables would mean maintaining two of
-- everything to express one difference.
--
-- Defaulted to buyer and NOT NULL: every row that exists today was a buyer's.
ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS audience text NOT NULL DEFAULT 'buyer';

ALTER TABLE sessions
    ADD CONSTRAINT sessions_audience_known
        CHECK (audience IN ('buyer', 'fundi'));

-- Mirrored onto spend, or the usage table stops being interpretable the first morning a
-- fundi assesses ten pieces in a row: the two audiences have completely different shapes
-- of use, and a single per-user total would hide that.
ALTER TABLE usage_events
    ADD COLUMN IF NOT EXISTS audience text NOT NULL DEFAULT 'buyer';

CREATE INDEX sessions_audience_created_idx ON sessions (audience, created_at DESC);

-- ------------------------------------------------------------------ pieces

-- A physical object, which may be assessed more than once.
--
-- The buyer's app never needed this: one customer, one piece, once. Fundi Bora's loop is
-- assess, repair, re-assess the next morning — the mockup shows a before-and-after of the
-- same stool — and the maker's skill file is an aggregate over pieces.
--
-- Deliberately NOT sessions.previous_session_id, which already means something else:
-- "the customer tapped check-another-item". Overloading it would make "compare with the
-- last table" and "this is the same table after I re-glued it" indistinguishable.
CREATE TABLE pieces (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_type_id text NOT NULL,
    -- What the maker calls it: "the stool for Mama Njeri". Optional, because the first
    -- assessment happens before anybody has a reason to name it.
    label        text,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX pieces_user_created_idx ON pieces (user_id, created_at DESC);

-- SET NULL, not CASCADE. Same reasoning as previous_session_id: removing the grouping
-- must not destroy the assessments, which are the record.
ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS piece_id uuid REFERENCES pieces(id) ON DELETE SET NULL;

CREATE INDEX sessions_piece_idx ON sessions (piece_id) WHERE piece_id IS NOT NULL;

-- ----------------------------------------------------- the maker's context

-- Workshop setup, profile 1 of 3 in the mockup.
--
-- Its own table rather than columns on users, for two reasons. users is the identity —
-- phone, password, invite — and is shared by both halves; and account_type there means
-- something unrelated (whether a business lets walk-in customers use its handset), so a
-- fundi is not a third value of it.
--
-- The presence of a row here is what makes an account a fundi.
CREATE TABLE fundi_workshops (
    user_id          uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    -- Free text: "Gikomba, third row". A structured location is sessions.location's job.
    works_at         text,
    years_in_trade   integer,
    workers          integer,
    -- What they make, in their words. Not ItemType: a fundi describes a product line
    -- ("beds and wardrobes"), not the protocol an assessment runs.
    makes            text,
    pieces_per_month integer,
    usual_timber     text,
    -- "Would you rent your tools to nearby fundis when idle?" Answered at setup, acted on
    -- only when the marketplace exists. Stored now because asking twice is worse.
    rents_tools      boolean NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fundi_workshops_counts_sane
        CHECK ((years_in_trade IS NULL OR years_in_trade BETWEEN 0 AND 80)
           AND (workers IS NULL OR workers BETWEEN 0 AND 500)
           AND (pieces_per_month IS NULL OR pieces_per_month BETWEEN 0 AND 1000))
);

-- What the maker can actually work with, profile 2 of 3.
--
-- This is an input to the coaching prompt, not a profile ornament. The mockup's fix plan
-- says "rope tourniquet clamp" precisely because no clamps are owned, and it prices a
-- marking gauge at KSh 600–900 because that is the tool whose absence caused the defect.
--
-- kind is a closed set, for the same reason TestDiagram is: prompts are data and change
-- without a release, so a prompt naming a tool this schema has never heard of must
-- degrade to no tool rather than to an unrecordable answer. Adding a tool is a migration,
-- which is honest — the app has to render it too.
CREATE TABLE fundi_tools (
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind         text NOT NULL,
    ownership    text NOT NULL,
    -- Only meaningful alongside rents_tools, and only used by the deferred marketplace.
    day_rate_kes integer,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, kind),
    CONSTRAINT fundi_tools_kind_known
        CHECK (kind IN ('hand_saw', 'hammer_mallet', 'chisels', 'plane_no4',
                        'circular_saw', 'router', 'marking_gauge', 'clamps',
                        'square', 'drill', 'sander', 'other')),
    CONSTRAINT fundi_tools_ownership_known
        CHECK (ownership IN ('owned', 'borrowed', 'none')),
    CONSTRAINT fundi_tools_rate_sane
        CHECK (day_rate_kes IS NULL OR day_rate_kes > 0)
);

-- What they want out of it, profile 3 of 3. A closed set because the mockup offers three
-- buttons, and because a goal the coaching cannot act on is worse than no goal.
CREATE TABLE fundi_goals (
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    goal       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, goal),
    CONSTRAINT fundi_goals_known
        CHECK (goal IN ('price_per_piece', 'more_orders', 'zero_comebacks'))
);

-- -------------------------------------------------------------- skill file

-- One measurement, from one assessment, on one dimension.
--
-- This is the table the whole longitudinal claim rests on, and it stores measurements
-- rather than grades on purpose. The mockup shows a joint gap going 5mm, 3mm, 2mm across
-- assessments #12, #14 and #15 — that trend is the evidence a badge is "earned from 14
-- assessed pieces", and it is the only form of this data that survives a change of rubric.
--
-- Keyed to the session, so every number traces back to the photographs it came from. A
-- number in here that cannot be re-derived from its assessment is not evidence.
CREATE TABLE fundi_observations (
    id             bigserial PRIMARY KEY,
    session_id     uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    dimension      text NOT NULL,
    -- The measurement, where the assessment produced one. Null is ordinary: plenty of
    -- findings are categorical, and plenty of photographs do not support a measurement.
    measured_value real,
    -- Never a bare number. A gap of 3 means nothing without knowing 3 of what, and the
    -- same false-precision argument as location accuracy applies: dropping the unit makes
    -- two incomparable observations look identical.
    measured_unit  text,
    -- The coarse reading, 1 to 5, as the mockup's "Joint fit L2" shows it. Kept alongside
    -- the measurement rather than derived from it here, because the mapping from
    -- millimetres to a level is a rubric decision that belongs in code where it can change.
    level          smallint,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fundi_observations_dimension_known
        CHECK (dimension IN ('frame_squareness', 'joint_fit', 'finishing',
                             'surface', 'material', 'hardware')),
    CONSTRAINT fundi_observations_unit_known
        CHECK (measured_unit IS NULL OR measured_unit IN ('mm', 'deg', 'pct')),
    CONSTRAINT fundi_observations_value_has_unit
        CHECK ((measured_value IS NULL) = (measured_unit IS NULL)),
    CONSTRAINT fundi_observations_level_sane
        CHECK (level IS NULL OR level BETWEEN 1 AND 5),
    CONSTRAINT fundi_observations_says_something
        CHECK (measured_value IS NOT NULL OR level IS NOT NULL)
);

CREATE INDEX fundi_observations_session_idx ON fundi_observations (session_id);
CREATE INDEX fundi_observations_dimension_idx ON fundi_observations (dimension, created_at);

-- The maker's own annotations on their skill file.
--
-- The mockup says of the skill file: "What I'll remember — yours to edit". An edit is
-- recorded HERE, alongside the observation, and never as an update to it.
--
-- That is a research decision, not a technical one. If a maker can overwrite a measured
-- observation, the longitudinal series silently becomes a mixture of what was measured
-- and what the subject preferred to record — and nothing downstream can tell which is
-- which. Disagreement is worth keeping; it just has to be kept as disagreement.
CREATE TABLE fundi_skill_notes (
    id         bigserial PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    dimension  text NOT NULL,
    -- The observation being disputed, when the note is about a specific one.
    about      bigint REFERENCES fundi_observations(id) ON DELETE SET NULL,
    note       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fundi_skill_notes_dimension_known
        CHECK (dimension IN ('frame_squareness', 'joint_fit', 'finishing',
                             'surface', 'material', 'hardware'))
);

CREATE INDEX fundi_skill_notes_user_idx ON fundi_skill_notes (user_id, dimension);

-- ---------------------------------------------------------------- deletion
--
-- anonymise_user is deliberately NOT changed.
--
-- The decision is that deletion hides a fundi's record from public view rather than
-- erasing it, and nothing in this migration is public: there is no badge table and no
-- publication surface yet. When one arrives it needs a visibility flag from its first
-- day, and that is the change that belongs in the same migration as the badge.
--
-- What V11 already does still applies here through the foreign keys: the profile tables
-- cascade from users, so an account that is genuinely deleted takes its workshop, tools
-- and goals with it. The observations cascade from sessions, which V11 keeps on purpose.
--
-- One thing this leaves unresolved and worth stating rather than discovering: the app's
-- deletion wording promises to remove what we hold about somebody. Hidden-but-retained is
-- not that, so the wording needs a sentence before a fundi is ever asked to agree to it.
-- Recorded in docs/retention-and-profile-wording.md.

INSERT INTO schema_migrations (version) VALUES ('V14__fundi_bora')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
