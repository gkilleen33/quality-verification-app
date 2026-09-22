-- Kagua server schema, version 16.
--
-- Which app an account belongs to.
--
-- Kagua and Fundi Bora are two apps sharing one backend, and their accounts are entirely
-- independent: a fundi registers in Fundi Bora and a buyer registers in Kagua, and
-- neither account exists in the other app. Nobody holds one account that is both.
--
-- V14 inferred this instead, from whether the account had a fundi_workshops row, and that
-- inference is wrong under the rule above in a way that bites immediately:
--
--   * A fundi who has registered but not yet filled in their workshop setup — which is
--     every fundi between signing up and finishing the profile screens — has no row, so
--     they read as a buyer and get the buying prompt for their first assessments.
--   * It makes the audience a function of profile completeness, so a fundi who cleared
--     their workshop details would silently become a buyer mid-pilot.
--
-- The audience is a fact about the account, established when it is created and never
-- changed. So it is a column, and the inference is gone.
--
-- WHERE IT COMES FROM: THE INVITE CODE
--
-- Exactly as is_tester does (V10), and for the same reason. Registration is invite-gated
-- for the pilot and every tester is somebody we hand a code to by name, so the code is
-- already how we decide who may spend the budget — it can decide which app they are
-- spending it in.
--
-- The alternative was an app identifier on the register request. Rejected: the audience
-- selects the system prompt, and the one property worth keeping about that choice is that
-- no client ever makes it. Kagua's prompt decides whether to buy a piece; Fundi Bora's
-- decides how to put it right. They are not interchangeable, and a body that could ask
-- for either would be a body that chooses what the assistant is for.
--
-- The invite is only the source. users.audience is the record, so when invite codes give
-- way to phone auth — V1 says they will — the column stays and only its source changes.

BEGIN;

-- Carried by the code, never typed at registration.
ALTER TABLE invite_codes
    ADD COLUMN audience text NOT NULL DEFAULT 'buyer';

ALTER TABLE invite_codes
    ADD CONSTRAINT invite_codes_audience_known
        CHECK (audience IN ('buyer', 'fundi'));

-- Copied from the invite at registration, in the same transaction that redeems it.
--
-- Deliberately NOT editable from the portal, which is where this differs from is_tester.
-- An evaluator hired later should not need a new account, so that flag can be turned on.
-- An audience cannot: the account's assessments, its profile tables and its whole history
-- were made as one audience or the other, and flipping the column would leave a fundi's
-- workshop profile hanging off an account the server now treats as a buyer's.
ALTER TABLE users
    ADD COLUMN audience text NOT NULL DEFAULT 'buyer';

ALTER TABLE users
    ADD CONSTRAINT users_audience_known
        CHECK (audience IN ('buyer', 'fundi'));

-- Defaulted to buyer, and correct without a backfill: every code and every account that
-- exists today is Kagua's. Fundi Bora has no users yet.

-- Read once per assessment, on the path that assembles the system prompt.
CREATE INDEX users_audience_idx ON users (audience) WHERE audience <> 'buyer';

-- sessions.audience (V14) stays as it is, and stays written once at creation. It is not
-- redundant with this column: it records the audience an assessment was *conducted* as,
-- which is a property of the assessment and not of the account. They cannot diverge while
-- an account keeps one audience for life — but the session is the research record, and a
-- record that has to join to a mutable profile to say what it was is a worse record.

INSERT INTO schema_migrations (version) VALUES ('V16__account_audience')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
