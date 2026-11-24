-- Kagua server schema, version 37.
--
-- Fundi Bora's detail answers, prefetched and stored (release scope row 38; Nathan, 18 and 23
-- November 2026). Merge this before PRs 1 to 5 of v0.7.0, which number their migrations from
-- V38.
--
-- Since prompt E (#107) each improvement in a Fundi Bora diagnosis has detail buttons ("More on
-- what we found", "More on what's causing this", "How to upgrade next time", and from v0.7.0's
-- PR 8 "A tool to buy or rent"). A tap sends a composed turn and waits for Claude. Now, when a
-- diagnosis arrives, the server writes the answer to every button of the first two improvements
-- the reply lists, in parallel, and keeps each here. A tap on one of those is answered from this
-- table with no Claude call; a tap on any other is answered by Claude as before, and kept here
-- too.
--
-- THE CONVERSATION. An answer here is not in `messages` and is never sent to Claude. It joins the
-- conversation only when the fundi opens it: the phone sends the composed turn it always sent,
-- and the server stores the answer as the reply to it, so later turns read the history they read
-- before prefetching. `opened_at` and `opened_message_id` record that first opening; an answer
-- never opened has neither.
--
-- WHAT A ROW HOLDS. The assessment; the reply whose improvement it answers and that
-- improvement's place and title (null place when no stored diagnosis names the title a tap
-- sent); which button (`detail_kind`, a closed vocabulary that already includes 'tool', so PR 8
-- needs no migration); prefetched or tapped (`source`); the answer; the model, the thinking
-- effort and the prompt's `prompt_sha`; its `usage_events` row; when it was written; when it was
-- first opened. From #107 a finding's cause and habit are written only when a fundi asks; this
-- table keeps them for the first two improvements of every diagnosis.
--
-- USAGE. Each prefetch is a `usage_events` row with call_kind 'detail_prefetch', so the CHECK on
-- call_kind is replaced. A tap's call stays 'turn', as before. Neither counts against the daily
-- allowance, which counts assessments started.
--
-- DELETION. anonymise_user is redefined with every clearing from V35, plus the improvement title
-- and the answer of every detail answer on the account's assessments: Claude's words about the
-- fundi's pieces. The rows stay, with their times, buttons and usage, for the research record.
-- An answer that was opened is also a message, which deletion leaves as it leaves every message.
--
-- Not append-only: a client-deleted assessment is purged after seven days (V11), and its detail
-- answers go with it (ON DELETE CASCADE).
--
-- The server tests have no database, so the CHECKs and the deletion are proved by the
-- self-tests below, which tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

CREATE TABLE fundi_detail_answers (
    -- Made by the server when the answer is stored.
    id                uuid PRIMARY KEY,
    session_id        uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    -- The reply whose improvement this answers.
    source_message_id uuid REFERENCES messages(id) ON DELETE SET NULL,
    -- The improvement's place in that reply's findings, from 0, and its title as the reply
    -- wrote it, which is how the phone's tap names it. Title cleared on deletion.
    finding_index     int,
    finding_title     text,
    detail_kind       text NOT NULL,
    source            text NOT NULL,
    -- Cleared on deletion.
    answer_text       text,
    model             text,
    -- The thinking effort the call asked for (row 50); null for the API's default.
    effort            text,
    prompt_sha        text,
    usage_event_id    bigint REFERENCES usage_events(id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    -- The first time the fundi opened it, and the reply that showed it.
    opened_at         timestamptz,
    opened_message_id uuid REFERENCES messages(id) ON DELETE SET NULL,
    CONSTRAINT fundi_detail_answers_kind_known
        CHECK (detail_kind IN ('what_we_found', 'cause', 'next_time', 'tool')),
    CONSTRAINT fundi_detail_answers_source_known
        CHECK (source IN ('prefetch', 'tap')),
    CONSTRAINT fundi_detail_answers_effort_known
        CHECK (effort IS NULL OR effort IN ('low', 'medium', 'high', 'xhigh', 'max')),
    CONSTRAINT fundi_detail_answers_index_sane
        CHECK (finding_index IS NULL OR finding_index >= 0),
    CONSTRAINT fundi_detail_answers_answer_present
        CHECK (answer_text IS NULL OR length(answer_text) > 0),
    -- An opening has a time; the message may later be purged, the time stays.
    CONSTRAINT fundi_detail_answers_opened_has_time
        CHECK (opened_message_id IS NULL OR opened_at IS NOT NULL)
);

-- A tap looks up the newest answer to one button of one improvement of its assessment.
CREATE INDEX fundi_detail_answers_lookup_idx
    ON fundi_detail_answers (session_id, detail_kind, finding_title, created_at DESC);

ALTER TABLE usage_events DROP CONSTRAINT usage_events_call_kind_known;
ALTER TABLE usage_events ADD CONSTRAINT usage_events_call_kind_known
    CHECK (call_kind IN ('turn', 'digest', 'bench', 'detail_prefetch'));

-- Self-test of the CHECKs, before anonymise_user is redefined below: MigrationsTest reads every
-- UPDATE after that definition as part of the function. One throwaway account; everything is
-- rolled back by the known error at the end. Country code 999 is assigned to nobody (V31).
DO $$
DECLARE
    probe   uuid := '00000000-0000-4000-8000-000000000370';
    session uuid := '00000000-0000-4000-8000-000000000371';
    reply   uuid := '00000000-0000-4000-8000-000000000372';
    prefetch_usage bigint;
    refused boolean;
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (probe, 'fundi', '+999000000071', 'self-test');
        INSERT INTO sessions (id, user_id, item_type_id, audience) VALUES (session, probe, 'wooden-table', 'fundi');
        INSERT INTO messages (id, session_id, role, text, ordinal) VALUES (reply, session, 'ASSISTANT', 'diagnosis', 1);

        -- A prefetch's usage row.
        INSERT INTO usage_events (user_id, session_id, model, input_tokens, output_tokens, latency_ms, call_kind, audience)
        VALUES (probe, session, 'm', 1, 1, 1, 'detail_prefetch', 'fundi')
        RETURNING id INTO prefetch_usage;

        -- Every button, both sources, and a tap with no stored diagnosis to point at.
        INSERT INTO fundi_detail_answers
            (id, session_id, source_message_id, finding_index, finding_title, detail_kind, source,
             answer_text, model, effort, prompt_sha, usage_event_id)
        VALUES
            ('00000000-0000-4000-8000-000000000373', session, reply, 0, 'Reglue the joint', 'what_we_found', 'prefetch', 'a', 'm', 'medium', 'sha', prefetch_usage),
            ('00000000-0000-4000-8000-000000000374', session, reply, 0, 'Reglue the joint', 'cause', 'prefetch', 'b', 'm', 'medium', 'sha', prefetch_usage),
            ('00000000-0000-4000-8000-000000000375', session, reply, 1, 'Sand the top', 'next_time', 'prefetch', 'c', 'm', 'medium', 'sha', prefetch_usage),
            ('00000000-0000-4000-8000-000000000376', session, reply, 1, 'Sand the top', 'tool', 'prefetch', 'd', 'm', 'medium', 'sha', prefetch_usage),
            ('00000000-0000-4000-8000-000000000377', session, NULL, NULL, 'A title no reply wrote', 'cause', 'tap', 'e', 'm', NULL, 'sha', NULL);

        refused := false;
        BEGIN
            INSERT INTO fundi_detail_answers (id, session_id, finding_title, detail_kind, source, answer_text)
            VALUES ('00000000-0000-4000-8000-000000000378', session, 't', 'price', 'prefetch', 'x');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V37 self-test: fundi_detail_answers accepted an unknown button';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO fundi_detail_answers (id, session_id, finding_title, detail_kind, source, answer_text)
            VALUES ('00000000-0000-4000-8000-000000000379', session, 't', 'cause', 'guess', 'x');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V37 self-test: fundi_detail_answers accepted an unknown source';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO fundi_detail_answers (id, session_id, finding_title, detail_kind, source, answer_text, effort)
            VALUES ('00000000-0000-4000-8000-00000000037a', session, 't', 'cause', 'tap', 'x', 'medium-high');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V37 self-test: fundi_detail_answers accepted an unknown effort';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO fundi_detail_answers (id, session_id, finding_title, detail_kind, source, answer_text, opened_message_id)
            VALUES ('00000000-0000-4000-8000-00000000037b', session, 't', 'cause', 'tap', 'x', reply);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V37 self-test: fundi_detail_answers accepted an opening with no time';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO usage_events (user_id, model, input_tokens, output_tokens, latency_ms, call_kind, audience)
            VALUES (probe, 'm', 1, 1, 1, 'prefetch', 'fundi');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V37 self-test: usage_events accepted an unknown call kind';
        END IF;

        -- The purge of a client-deleted session takes its detail answers with it.
        DELETE FROM sessions WHERE id = session;
        IF EXISTS (SELECT 1 FROM fundi_detail_answers WHERE session_id = session) THEN
            RAISE EXCEPTION 'V37 self-test: a detail answer outlived its session';
        END IF;

        RAISE EXCEPTION 'V37 self-test passed' USING ERRCODE = 'P0V37';
    EXCEPTION WHEN SQLSTATE 'P0V37' THEN
        -- Everything inside the block above, the account included, is rolled back.
        NULL;
    END;
END
$$;

-- As V35, plus the title and the answer of every detail answer on the account's assessments.
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
-- for the reason the first block gives. After anonymise_user the detail answer is still there,
-- with its button and time, and has no title or answer.
DO $$
DECLARE
    probe   uuid := '00000000-0000-4000-8000-00000000037c';
    session uuid := '00000000-0000-4000-8000-00000000037d';
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (probe, 'fundi', '+999000000072', 'self-test');
        INSERT INTO sessions (id, user_id, item_type_id, audience) VALUES (session, probe, 'wooden-table', 'fundi');
        INSERT INTO fundi_detail_answers (id, session_id, finding_index, finding_title, detail_kind, source, answer_text)
        VALUES ('00000000-0000-4000-8000-00000000037e', session, 0, 'Reglue the joint', 'cause', 'prefetch', 'The glue was spread thin.');

        IF NOT anonymise_user(probe) THEN
            RAISE EXCEPTION 'V37 self-test: anonymise_user did not anonymise the probe account';
        END IF;
        IF EXISTS (SELECT 1 FROM fundi_detail_answers WHERE session_id = session
                    AND (answer_text IS NOT NULL OR finding_title IS NOT NULL)) THEN
            RAISE EXCEPTION 'V37 self-test: anonymise_user left a detail answer''s words behind';
        END IF;
        IF (SELECT count(*) FROM fundi_detail_answers WHERE session_id = session AND detail_kind = 'cause') <> 1 THEN
            RAISE EXCEPTION 'V37 self-test: anonymise_user lost the detail answer row';
        END IF;

        RAISE EXCEPTION 'V37 deletion self-test passed' USING ERRCODE = 'P0V37';
    EXCEPTION WHEN SQLSTATE 'P0V37' THEN
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V37__fundi_detail_answers')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
