-- Kagua server schema, version 28.
--
-- The record of each Fundi Bora account's agreement to the terms, and of each withdrawal.
--
-- WHY: Fundi Bora sends a maker's photos and workshop location to Anthropic and Amazon Web
-- Services in the United States. Kenya's Data Protection Act asks for a specific agreement
-- to that transfer, separate from agreeing to the terms, and lets the person withdraw it at
-- any time (section 14 of the terms; section 32(2) of the Act). The terms screen therefore
-- has two boxes, and this table keeps one row per box ticked and one per withdrawal. The
-- server refuses an account whose newest rows are not both agreements to the current
-- terms_version (V27), but only while that app's terms_gate is on.
--
-- APPEND-ONLY. A row is never edited and never deleted: what somebody agreed to, and when,
-- is the record, and a withdrawal is a new row rather than a change to the old one. The
-- newest row for an account and kind is its current state. V27's refuse_row_change()
-- refuses UPDATE, DELETE and TRUNCATE.
--
-- Only the transfer agreement can be withdrawn. Withdrawing the terms themselves means
-- deleting the account, which Settings already does.
--
-- WHAT A ROW PROVES. version is the label of the text; text_sha256 is the SHA-256 of the
-- exact file shown, so a label reused by mistake cannot hide a changed text. language is
-- the language shown, since a Kiswahili agreement is to a different file. app_version is
-- the build whose screen applied the rules (both boxes unticked, enabled only at the end
-- of the text). The server writes audience from users.audience and created_at from its own
-- clock; neither comes from the phone.
--
-- DELETION. anonymise_user is unchanged, deliberately. A row holds no name, number,
-- location or free text: an account id, two closed vocabularies, a version label, a hash,
-- an app version and a time. Keeping it after the account is deleted is what lets the
-- research record show that every assessment was made under an agreement. user_id
-- references users with no cascade; anonymise_user keeps the users row (V11), so nothing
-- has to be deleted for the reference to hold.
--
-- The server tests have no database, so the CHECKs and triggers are proved by the
-- self-test at the end, which tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

CREATE TABLE consents (
    -- Orders the rows. The newest row per account and kind is the current state.
    id           bigserial PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users(id),
    kind         text NOT NULL,
    action       text NOT NULL,
    -- The terms_version agreed to. Null on a withdrawal, which withdraws whatever was
    -- agreed before it.
    version      text,
    language     text,
    text_sha256  text,
    -- Never defaulted: written from users.audience on every insert.
    audience     text NOT NULL,
    app_version  integer NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    -- The two boxes on the terms screen. A research consent would be a third kind, added
    -- by replacing this CHECK as V20 did, once the PIs decide it belongs in the app.
    CONSTRAINT consents_kind_known
        CHECK (kind IN ('terms', 'transfer')),
    CONSTRAINT consents_action_known
        CHECK (action IN ('agreed', 'withdrawn')),
    CONSTRAINT consents_only_transfer_withdrawn
        CHECK (action = 'agreed' OR kind = 'transfer'),
    CONSTRAINT consents_language_known
        CHECK (language IS NULL OR language IN ('en', 'sw')),
    CONSTRAINT consents_sha_shape
        CHECK (text_sha256 IS NULL OR text_sha256 ~ '^[0-9a-f]{64}$'),
    -- An agreement names the text; a withdrawal names none.
    CONSTRAINT consents_agreement_complete
        CHECK ((action = 'agreed'
                AND version IS NOT NULL AND length(btrim(version)) > 0
                AND language IS NOT NULL AND text_sha256 IS NOT NULL)
               OR (action = 'withdrawn'
                   AND version IS NULL AND language IS NULL AND text_sha256 IS NULL)),
    CONSTRAINT consents_audience_known
        CHECK (audience IN ('buyer', 'fundi')),
    CONSTRAINT consents_app_version_positive
        CHECK (app_version > 0)
);

-- The current state is one lookup: the newest row for an account and kind.
CREATE INDEX consents_current_idx
    ON consents (user_id, kind, id DESC);

CREATE TRIGGER consents_append_only
    BEFORE UPDATE OR DELETE ON consents
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();

-- Row triggers do not fire on TRUNCATE.
CREATE TRIGGER consents_no_truncate
    BEFORE TRUNCATE ON consents
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_row_change();

-- Self-test, against a throwaway account created and removed inside the test. Each
-- refusal rolls back only its own block; a statement that should have been refused and
-- was not fails the migration. The whole DO block ends by raising a known error that its
-- outer handler catches, so the account and its rows never outlive the test.
DO $$
DECLARE
    probe   uuid := '00000000-0000-4000-8000-0000000000c8';
    refused boolean;
BEGIN
    BEGIN
        INSERT INTO users (id, audience) VALUES (probe, 'fundi');

        INSERT INTO consents (user_id, kind, action, version, language, text_sha256, audience, app_version)
        VALUES (probe, 'transfer', 'agreed', '2026-11', 'en', repeat('a', 64), 'fundi', 5);

        refused := false;
        BEGIN
            UPDATE consents SET version = '2026-12' WHERE user_id = probe;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V28 self-test: consents accepted an UPDATE';
        END IF;

        refused := false;
        BEGIN
            DELETE FROM consents WHERE user_id = probe;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V28 self-test: consents accepted a DELETE';
        END IF;

        refused := false;
        BEGIN
            TRUNCATE consents;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V28 self-test: consents accepted a TRUNCATE';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO consents (user_id, kind, action, version, language, text_sha256, audience, app_version)
            VALUES (probe, 'research', 'agreed', '2026-11', 'en', repeat('a', 64), 'fundi', 5);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V28 self-test: consents accepted the kind research';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO consents (user_id, kind, action, audience, app_version)
            VALUES (probe, 'terms', 'withdrawn', 'fundi', 5);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V28 self-test: consents accepted a withdrawal of the terms';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO consents (user_id, kind, action, version, language, audience, app_version)
            VALUES (probe, 'terms', 'agreed', '2026-11', 'en', 'fundi', 5);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V28 self-test: consents accepted an agreement without a hash';
        END IF;

        -- A withdrawal of the transfer agreement is accepted.
        INSERT INTO consents (user_id, kind, action, audience, app_version)
        VALUES (probe, 'transfer', 'withdrawn', 'fundi', 5);

        RAISE EXCEPTION 'V28 self-test passed' USING ERRCODE = 'P0V28';
    EXCEPTION WHEN SQLSTATE 'P0V28' THEN
        -- Everything inside the block above, the probe account included, is rolled back.
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V28__consents')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
