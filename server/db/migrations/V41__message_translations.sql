-- Kagua server schema, version 41.
--
-- English and Kiswahili on a Fundi Bora assessment (release scope row 29; Nathan, 16 November
-- 2026). Merge after V40 (#116), before v0.7.0's PR 5, which numbers its migration V42.
--
-- WHAT: each Fundi Bora assessment has a switch between English and Kiswahili. Replies are not
-- translated in advance. The first tap on the other language asks the server to translate the
-- assessment's replies, one Claude call per reply; each translation is kept here, so later taps,
-- on any phone signed in to the account, switch at once with no second call.
--
-- ONE ROW PER REPLY AND LANGUAGE. A reply is named by the SHA-256 of its text (source_sha256),
-- because the phone's id for a reply it received is its own and not the server's; the unique
-- index keeps one translation per assessment, text and language. message_id is the first stored
-- reply with that text, for joining.
--
-- NEVER IN THE CONVERSATION. Nothing that builds a request to Claude reads this table: the
-- history of the next turn is read from `messages` alone (ChatStore.history), so a translation
-- cannot change what Claude is sent or the bytes of its cached prefix. A translation is stored
-- beside its reply, never in place of it.
--
-- USAGE. Each translation call is a `usage_events` row with call_kind 'translation', so the CHECK
-- on call_kind is replaced. Outside the daily allowance, which counts assessments started.
--
-- DELETION. anonymise_user is redefined with every clearing from V37, plus the text of every
-- translation on the account's assessments: Claude's words about the fundi's pieces, as V37
-- clears the detail answers. The original replies are messages, which deletion leaves as it
-- leaves every message, for the research record; a translation adds nothing to that record that
-- cannot be made again from the original, so it is not kept. The rows stay, with their
-- language, times and usage.
--
-- Not append-only: a client-deleted assessment is purged after seven days (V11), and its
-- translations go with it (ON DELETE CASCADE).
--
-- ROLLBACK. Revert the code first, then DROP TABLE message_translations and restore V37's CHECK
-- on usage_events.call_kind (after deleting or relabelling any 'translation' usage rows), and
-- V37's anonymise_user. The data API's /translations reads the table, so dropping it under this
-- code would fail that route; nothing else reads it. Reverting the code and keeping the table is
-- safe: the old code never reads it.
--
-- The server tests have no database, so the CHECKs and the deletion are proved by the self-tests
-- below, which tools/deploy.sh runs in its rolled-back rehearsal. Each counts only rows it made.

BEGIN;

CREATE TABLE message_translations (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id        uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    -- The first stored reply with this text.
    message_id        uuid REFERENCES messages(id) ON DELETE SET NULL,
    -- SHA-256 of the original reply's text, lowercase hex.
    source_sha256     char(64) NOT NULL,
    -- The language translated into.
    language          text NOT NULL,
    -- Cleared on deletion.
    translated_text   text,
    model             text,
    -- SHA-256 of the translation prompt (prompts/fundi-translate.txt) that wrote it.
    prompt_sha        text,
    usage_event_id    bigint REFERENCES usage_events(id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT message_translations_language_known
        CHECK (language IN ('en', 'sw')),
    CONSTRAINT message_translations_source_hex
        CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT message_translations_text_present
        CHECK (translated_text IS NULL OR length(translated_text) > 0)
);

-- One translation per assessment, reply text and language; the route reads them by the first two.
CREATE UNIQUE INDEX message_translations_key
    ON message_translations (session_id, language, source_sha256);

ALTER TABLE usage_events DROP CONSTRAINT usage_events_call_kind_known;
ALTER TABLE usage_events ADD CONSTRAINT usage_events_call_kind_known
    CHECK (call_kind IN ('turn', 'digest', 'bench', 'detail_prefetch', 'translation'));

-- Self-test of the CHECKs and the index, before anonymise_user is redefined below: MigrationsTest
-- reads every UPDATE and DELETE after that definition as part of the function. One throwaway
-- account; everything is rolled back by the known error at the end. Country code 999 is assigned
-- to nobody (V31).
DO $$
DECLARE
    probe    uuid := '00000000-0000-4000-8000-000000000410';
    session_ uuid := '00000000-0000-4000-8000-000000000411';
    reply    uuid := '00000000-0000-4000-8000-000000000412';
    hash     text := repeat('ab', 32);
    usage_id bigint;
    refused  boolean;
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (probe, 'fundi', '+999000000410', 'self-test');
        INSERT INTO sessions (id, user_id, item_type_id, audience) VALUES (session_, probe, 'wooden-table', 'fundi');
        INSERT INTO messages (id, session_id, role, text, ordinal) VALUES (reply, session_, 'ASSISTANT', 'diagnosis', 1);

        -- A translation's usage row.
        INSERT INTO usage_events (user_id, session_id, model, input_tokens, output_tokens, latency_ms, call_kind, audience)
        VALUES (probe, session_, 'm', 1, 1, 1, 'translation', 'fundi')
        RETURNING id INTO usage_id;

        -- Both languages of the same reply.
        INSERT INTO message_translations (session_id, message_id, source_sha256, language, translated_text, model, prompt_sha, usage_event_id)
        VALUES (session_, reply, hash, 'sw', 'utambuzi', 'm', 'sha', usage_id),
               (session_, reply, hash, 'en', 'diagnosis', 'm', 'sha', usage_id);

        refused := false;
        BEGIN
            INSERT INTO message_translations (session_id, source_sha256, language, translated_text)
            VALUES (session_, repeat('cd', 32), 'fr', 'x');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V41 self-test: message_translations accepted an unknown language';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO message_translations (session_id, source_sha256, language, translated_text)
            VALUES (session_, upper(repeat('cd', 32)), 'sw', 'x');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V41 self-test: message_translations accepted a hash that is not lowercase hex';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO message_translations (session_id, source_sha256, language, translated_text)
            VALUES (session_, repeat('cd', 32), 'sw', '');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V41 self-test: message_translations accepted an empty translation';
        END IF;

        -- A second translation of the same reply into the same language.
        refused := false;
        BEGIN
            INSERT INTO message_translations (session_id, source_sha256, language, translated_text)
            VALUES (session_, hash, 'sw', 'tena');
        EXCEPTION WHEN unique_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V41 self-test: a reply was translated twice into one language';
        END IF;
        -- What the store does instead: the first stands.
        INSERT INTO message_translations (session_id, source_sha256, language, translated_text)
        VALUES (session_, hash, 'sw', 'tena')
        ON CONFLICT (session_id, language, source_sha256) DO NOTHING;
        IF (SELECT translated_text FROM message_translations
             WHERE session_id = session_ AND language = 'sw' AND source_sha256 = hash) <> 'utambuzi' THEN
            RAISE EXCEPTION 'V41 self-test: a second translation replaced the first';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO usage_events (user_id, model, input_tokens, output_tokens, latency_ms, call_kind, audience)
            VALUES (probe, 'm', 1, 1, 1, 'translate', 'fundi');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V41 self-test: usage_events accepted an unknown call kind';
        END IF;

        -- The purge of a client-deleted session takes its translations with it.
        DELETE FROM sessions WHERE id = session_;
        IF EXISTS (SELECT 1 FROM message_translations WHERE session_id = session_) THEN
            RAISE EXCEPTION 'V41 self-test: a translation outlived its session';
        END IF;

        RAISE EXCEPTION 'V41 self-test passed' USING ERRCODE = 'P0V41';
    EXCEPTION WHEN SQLSTATE 'P0V41' THEN
        -- Everything inside the block above, the account included, is rolled back.
        NULL;
    END;
END
$$;

-- As V37, plus the text of every translation on the account's assessments.
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

    -- Where each bench photo was taken (V35): the fundi's bench, which is their workshop.
    UPDATE bench_photos SET
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

    -- The detail answers on this account's assessments (V37): Claude's words about the
    -- fundi's pieces, and the improvement's title. The rows stay, with their times, buttons
    -- and usage.
    UPDATE fundi_detail_answers SET
        finding_title = NULL,
        answer_text   = NULL
    WHERE session_id IN (SELECT s.id FROM sessions s WHERE s.user_id = target);

    -- The translations of this account's assessments' replies (V41): Claude's words about
    -- the fundi's pieces, in the other language. The rows stay, with their language, times
    -- and usage.
    UPDATE message_translations SET
        translated_text = NULL
    WHERE session_id IN (SELECT s.id FROM sessions s WHERE s.user_id = target);

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

    -- The same for PesaLog's (V33). tracker_deletions stays: it holds counts and times.
    DELETE FROM tracker_transactions WHERE user_id = target;
    DELETE FROM tracker_unread_counts WHERE user_id = target;

    -- Every device signed out. Otherwise a phone holding a live refresh token keeps
    -- working against an account that no longer has an owner.
    DELETE FROM refresh_tokens WHERE user_id = target;

    -- The numbers above being gone ends every link this account was on, on either side.
    -- Recorded now, so the record says when.
    PERFORM sync_worker_link_events();

    RETURN affected > 0;
END;
$$;

-- Self-test of the deletion, after the definition: INSERTs only, never an UPDATE or a DELETE,
-- for the reason the first block gives. After anonymise_user the translation is still there,
-- with its language and time, and has no text; only this test's own rows are counted.
DO $$
DECLARE
    probe    uuid := '00000000-0000-4000-8000-000000000413';
    session_ uuid := '00000000-0000-4000-8000-000000000414';
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (probe, 'fundi', '+999000000411', 'self-test');
        INSERT INTO sessions (id, user_id, item_type_id, audience) VALUES (session_, probe, 'wooden-table', 'fundi');
        INSERT INTO message_translations (session_id, source_sha256, language, translated_text)
        VALUES (session_, repeat('ef', 32), 'sw', 'Gundi ilienezwa kidogo.');

        IF NOT anonymise_user(probe) THEN
            RAISE EXCEPTION 'V41 self-test: anonymise_user did not anonymise the probe account';
        END IF;
        IF EXISTS (SELECT 1 FROM message_translations WHERE session_id = session_ AND translated_text IS NOT NULL) THEN
            RAISE EXCEPTION 'V41 self-test: anonymise_user left a translation''s words behind';
        END IF;
        IF (SELECT count(*) FROM message_translations WHERE session_id = session_ AND language = 'sw') <> 1 THEN
            RAISE EXCEPTION 'V41 self-test: anonymise_user lost the translation row';
        END IF;

        RAISE EXCEPTION 'V41 deletion self-test passed' USING ERRCODE = 'P0V41';
    EXCEPTION WHEN SQLSTATE 'P0V41' THEN
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V41__message_translations')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
