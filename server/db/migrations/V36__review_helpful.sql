-- Kagua server schema, version 36.
--
-- Fundi Bora v0.6.0's tester question (release scope row 7; Nathan, 14 November 2026): after
-- each Fundi Bora verdict, a tester sees "Was this review helpful?" and answers Yes or No.
--
-- One answer per assessment. The first answer stands: the session is the primary key and
-- the server inserts with ON CONFLICT DO NOTHING, so a phone that sends again after a lost
-- reply records nothing new. Only an account marked as a tester may answer, and only on its
-- own Fundi Bora assessment with a verdict; the route checks both, since a CHECK cannot read
-- another table.
--
-- The answer is never sent to Claude: nothing that builds a request reads this table. Kagua's
-- tester_feedback (V10) is unchanged and is not used for Fundi Bora.
--
-- Not append-only, unlike piece_studio_changes: a client-deleted session is purged after seven
-- days, and the purge must be able to take this row with it (ON DELETE CASCADE).
--
-- DELETION. No personal data: an account, a session, a yes or no and a time. anonymise_user
-- is not redefined. As with tester_feedback, the answer outlives its author's account
-- (user_id is set to null), because the research record outlives the profile.

BEGIN;

CREATE TABLE fundi_review_helpful (
    session_id  uuid PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    user_id     uuid REFERENCES users(id) ON DELETE SET NULL,
    helpful     boolean NOT NULL,
    answered_at timestamptz NOT NULL DEFAULT now()
);

-- Self-test: one answer per assessment. Rolled back by the known error at the end. Country
-- code 999 is assigned to nobody (V31).
DO $$
DECLARE
    tester  uuid := '00000000-0000-4000-8000-000000000360';
    session uuid := '00000000-0000-4000-8000-000000000361';
    refused boolean;
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash, is_tester)
        VALUES (tester, 'fundi', '+999000000061', 'self-test', true);
        INSERT INTO sessions (id, user_id, item_type_id, audience) VALUES (session, tester, 'wooden-table', 'fundi');

        INSERT INTO fundi_review_helpful (session_id, user_id, helpful) VALUES (session, tester, true);
        refused := false;
        BEGIN
            INSERT INTO fundi_review_helpful (session_id, user_id, helpful) VALUES (session, tester, false);
        EXCEPTION WHEN unique_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V36 self-test: fundi_review_helpful accepted a second answer';
        END IF;

        -- The purge of a client-deleted session takes the answer with it.
        DELETE FROM sessions WHERE id = session;
        IF EXISTS (SELECT 1 FROM fundi_review_helpful WHERE session_id = session) THEN
            RAISE EXCEPTION 'V36 self-test: the answer outlived its session';
        END IF;

        RAISE EXCEPTION 'V36 self-test passed' USING ERRCODE = 'P0V36';
    EXCEPTION WHEN SQLSTATE 'P0V36' THEN
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V36__review_helpful')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
