-- Kagua server schema, version 35.
--
-- Fundi Bora v0.6.0's tools (release scope rows 5 and 10; design of 3 and 4 November 2026;
-- Nathan, 14 November 2026): who owns each tool, the bench photo that drafts the tool list
-- at setup, and the tools an assessment had to hand.
--
-- TOOL OWNERS. A tool on a fundi's list is now owned by the worker (the account itself), the
-- manager, the studio, or is a rental, in place of "owned" and "borrowed". "none" and
-- "unknown" stay: they are not owners but answers that the tool is not to hand, and V20's
-- reason for "unknown" (what the fundi has been taught) still holds. The three CHECKs that
-- list the states are replaced, as V20 replaced them.
--
-- THE MAPPING of every existing answer, for Nathan to read before merge:
--     owned    -> worker   the fundi's own tool, which is what "Have it" meant
--     borrowed -> rental   a tool from outside the fundi's own kit. "Can borrow" did not say
--                          from whom; most accounts so far are workshop owners, for whom it
--                          meant another workshop's. A worker who meant the manager's tool
--                          can correct it on the setup screen.
--     none     -> none
--     unknown  -> unknown
-- fundi_tools is rewritten. fundi_tool_changes is a record of what was answered at the
-- time, so its rows keep "owned" and "borrowed"; its CHECKs allow both vocabularies. A
-- rewrite of the current answer writes no change row: nothing about the tool changed.
--
-- The opening turn names a "worker", "manager" or "studio" tool as plainly as it named an
-- "owned" one, and a "rental" as it named a "borrowed" one (FundiContextMessage), so the
-- assessment prompt's input is the same bytes for every profile this migration rewrites.
--
-- BENCH PHOTOS (row 5). At setup, before the typed list, the fundi may photograph their bench;
-- one Claude call drafts the tool list from it, and the fundi confirms each tool and says who
-- owns it. Nothing is written to fundi_tools from the draft itself. bench_photos keeps the
-- photo's hash, its GPS point only when the fundi allows location, the server's time, the
-- account, and the draft as Claude returned it, for the accuracy check. Five a day per
-- account, Nairobi day, outside the assessment allowance; each call is a usage_events row
-- with call_kind 'bench', so the CHECK on call_kind is replaced.
--
-- INFERRED TOOLS (row 10). session_tools holds, per assessment of a piece, the tools to hand
-- when it started: the maker's own tools, and on a piece labelled for a studio, everything on
-- the maker's list and on the studio manager's list. Computed by the server in the
-- transaction that creates the session; never shown to fundis and never sent to Claude.
--
-- DELETION. anonymise_user is redefined with every clearing from V33, plus each bench photo's
-- GPS point. The rows stay: the hash, the time and the draft say nothing about where.
--
-- The server tests have no database, so the mapping, the CHECKs and the deletion are proved
-- by the self-tests below, which tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

-- ------------------------------------------------------------------ tool owners

ALTER TABLE fundi_tools DROP CONSTRAINT fundi_tools_ownership_known;
UPDATE fundi_tools
   SET ownership = CASE ownership WHEN 'owned' THEN 'worker' WHEN 'borrowed' THEN 'rental' END
 WHERE ownership IN ('owned', 'borrowed');
ALTER TABLE fundi_tools ADD CONSTRAINT fundi_tools_ownership_known
    CHECK (ownership IN ('worker', 'manager', 'studio', 'rental', 'none', 'unknown'));

-- The record keeps what was answered at the time, in either vocabulary.
ALTER TABLE fundi_tool_changes DROP CONSTRAINT fundi_tool_changes_from_known;
ALTER TABLE fundi_tool_changes ADD CONSTRAINT fundi_tool_changes_from_known
    CHECK (from_ownership IS NULL
           OR from_ownership IN ('worker', 'manager', 'studio', 'rental', 'none', 'unknown',
                                 'owned', 'borrowed'));

ALTER TABLE fundi_tool_changes DROP CONSTRAINT fundi_tool_changes_to_known;
ALTER TABLE fundi_tool_changes ADD CONSTRAINT fundi_tool_changes_to_known
    CHECK (to_ownership IN ('worker', 'manager', 'studio', 'rental', 'none', 'unknown',
                            'owned', 'borrowed'));

-- --------------------------------------------------------------- bench photos

CREATE TABLE bench_photos (
    -- Made on the phone, so a retry after a lost answer is the same row, not a second call.
    id                  uuid PRIMARY KEY,
    user_id             uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- The photo, in the blob store as an assessment's photos are. Lowercase hex.
    sha256              char(64) NOT NULL,
    -- Only when the fundi allows location; all three or none. Cleared on deletion.
    location            geography(Point, 4326),
    location_accuracy_m real,
    location_at         timestamptz,
    -- The server's receipt time, never the phone's clock (V18).
    taken_at            timestamptz NOT NULL DEFAULT now(),
    -- Claude's draft as returned: {"tools":[{"kind":..., "seen":...}]}. Null until it
    -- answers, and when the call failed.
    draft               jsonb,
    -- Which prompt drafted it, as sessions.prompt_sha records an assessment's.
    prompt_sha          text,
    model               text,
    CONSTRAINT bench_photos_sha256_hex
        CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT bench_photos_location_whole
        CHECK ((location IS NULL) = (location_accuracy_m IS NULL)
           AND (location IS NULL) = (location_at IS NULL)),
    CONSTRAINT bench_photos_accuracy_sane
        CHECK (location_accuracy_m IS NULL OR (location_accuracy_m > 0 AND location_accuracy_m <= 5000))
);

-- "How many today" for the five-a-day limit.
CREATE INDEX bench_photos_user_taken_idx ON bench_photos (user_id, taken_at);

ALTER TABLE usage_events DROP CONSTRAINT usage_events_call_kind_known;
ALTER TABLE usage_events ADD CONSTRAINT usage_events_call_kind_known
    CHECK (call_kind IN ('turn', 'digest', 'bench'));

-- ------------------------------------------------------------- inferred tools

CREATE TABLE session_tools (
    session_id uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    kind       text NOT NULL,
    -- Whose list it came from: the piece's maker's ('maker') or the labelled studio's
    -- manager's ('studio').
    source     text NOT NULL,
    -- Who owns it, as that list says.
    owner      text NOT NULL,
    PRIMARY KEY (session_id, kind, source),
    CONSTRAINT session_tools_kind_known
        CHECK (kind IN ('hand_saw', 'hammer_mallet', 'chisels', 'plane_no4',
                        'circular_saw', 'router', 'marking_gauge', 'clamps',
                        'square', 'drill', 'sander', 'other')),
    CONSTRAINT session_tools_source_known
        CHECK (source IN ('maker', 'studio')),
    -- Only tools to hand: never 'none' or 'unknown'.
    CONSTRAINT session_tools_owner_known
        CHECK (owner IN ('worker', 'manager', 'studio', 'rental'))
);

-- Self-test of the mapping and the CHECKs, before anonymise_user is redefined below:
-- MigrationsTest reads every UPDATE after that definition as part of the function. One
-- throwaway account; everything is rolled back by the known error at the end.
DO $$
DECLARE
    probe   uuid := '00000000-0000-4000-8000-000000000350';
    session uuid := '00000000-0000-4000-8000-000000000351';
    refused boolean;
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (probe, 'fundi', '+999000000051', 'self-test');
        INSERT INTO sessions (id, user_id, item_type_id, audience) VALUES (session, probe, 'wooden-table', 'fundi');

        -- Every owner and both answers that are not owners are allowed.
        INSERT INTO fundi_tools (user_id, kind, ownership)
        VALUES (probe, 'hand_saw', 'worker'), (probe, 'hammer_mallet', 'manager'),
               (probe, 'chisels', 'studio'), (probe, 'plane_no4', 'rental'),
               (probe, 'router', 'none'), (probe, 'marking_gauge', 'unknown');

        -- The old words are refused on the current answer...
        refused := false;
        BEGIN
            INSERT INTO fundi_tools (user_id, kind, ownership) VALUES (probe, 'clamps', 'owned');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V35 self-test: fundi_tools accepted owned';
        END IF;

        -- ...and allowed in the record, beside the new ones.
        INSERT INTO fundi_tool_changes (user_id, kind, from_ownership, to_ownership)
        VALUES (probe, 'clamps', 'borrowed', 'owned'), (probe, 'square', 'owned', 'manager');

        refused := false;
        BEGIN
            INSERT INTO fundi_tool_changes (user_id, kind, from_ownership, to_ownership)
            VALUES (probe, 'drill', NULL, 'mine');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V35 self-test: fundi_tool_changes accepted an unknown owner';
        END IF;

        -- A bench photo with its point whole, and one with none.
        INSERT INTO bench_photos (id, user_id, sha256, location, location_accuracy_m, location_at)
        VALUES ('00000000-0000-4000-8000-000000000352', probe, repeat('ab', 32),
                ST_SetSRID(ST_MakePoint(36.82, -1.29), 4326)::geography, 12, now());
        INSERT INTO bench_photos (id, user_id, sha256)
        VALUES ('00000000-0000-4000-8000-000000000353', probe, repeat('cd', 32));

        refused := false;
        BEGIN
            INSERT INTO bench_photos (id, user_id, sha256, location)
            VALUES ('00000000-0000-4000-8000-000000000354', probe, repeat('ef', 32),
                    ST_SetSRID(ST_MakePoint(36.82, -1.29), 4326)::geography);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V35 self-test: bench_photos accepted a point without its accuracy';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO bench_photos (id, user_id, sha256)
            VALUES ('00000000-0000-4000-8000-000000000355', probe, upper(repeat('ab', 32)));
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V35 self-test: bench_photos accepted an uppercase hash';
        END IF;

        -- A bench call's usage row.
        INSERT INTO usage_events (user_id, model, input_tokens, output_tokens, latency_ms, call_kind, audience)
        VALUES (probe, 'm', 1, 1, 1, 'bench', 'fundi');

        -- Inferred tools: owners only, never "none".
        INSERT INTO session_tools (session_id, kind, source, owner)
        VALUES (session, 'hand_saw', 'maker', 'worker'), (session, 'hand_saw', 'studio', 'studio');
        refused := false;
        BEGIN
            INSERT INTO session_tools (session_id, kind, source, owner) VALUES (session, 'router', 'maker', 'none');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V35 self-test: session_tools accepted a tool not to hand';
        END IF;

        RAISE EXCEPTION 'V35 self-test passed' USING ERRCODE = 'P0V35';
    EXCEPTION WHEN SQLSTATE 'P0V35' THEN
        -- Everything inside the block above, the account included, is rolled back.
        NULL;
    END;
END
$$;

-- As V33, plus each bench photo's GPS point.
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

-- Self-test of the deletion, after the definition: INSERTs only, never an UPDATE or a
-- DELETE, for the reason the first block gives. After anonymise_user the bench photo is
-- still there, with its hash and draft, and has no point.
DO $$
DECLARE
    probe uuid := '00000000-0000-4000-8000-000000000356';
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (probe, 'fundi', '+999000000056', 'self-test');
        INSERT INTO bench_photos (id, user_id, sha256, location, location_accuracy_m, location_at, draft)
        VALUES ('00000000-0000-4000-8000-000000000357', probe, repeat('ab', 32),
                ST_SetSRID(ST_MakePoint(36.82, -1.29), 4326)::geography, 12, now(),
                '{"tools":[{"kind":"clamps"}]}');

        IF NOT anonymise_user(probe) THEN
            RAISE EXCEPTION 'V35 self-test: anonymise_user did not anonymise the probe account';
        END IF;
        IF EXISTS (SELECT 1 FROM bench_photos WHERE user_id = probe AND location IS NOT NULL) THEN
            RAISE EXCEPTION 'V35 self-test: anonymise_user left a bench photo''s point behind';
        END IF;
        IF (SELECT count(*) FROM bench_photos WHERE user_id = probe AND draft IS NOT NULL) <> 1 THEN
            RAISE EXCEPTION 'V35 self-test: anonymise_user lost the bench photo row';
        END IF;

        RAISE EXCEPTION 'V35 deletion self-test passed' USING ERRCODE = 'P0V35';
    EXCEPTION WHEN SQLSTATE 'P0V35' THEN
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V35__tools')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
