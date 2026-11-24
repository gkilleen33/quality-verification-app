-- Kagua server schema, version 40.
--
-- The customer's PDF by QR code (release scope row 31; Nathan, 16 November 2026). Merge after
-- V39 (#115), before v0.7.0's PRs 4 and 5.
--
-- WHAT: on a shop's tablet (V39), after the verdict, "Send this report to my phone" shows a QR
-- code. The customer scans it with their own phone's camera and downloads a PDF of the
-- assessment from a link that needs no sign-in. No phone number is collected, nothing is sent
-- by SMS, and nothing stays on the tablet: the code goes with the tablet's wipe.
--
-- ONE ROW PER LINK ISSUED. The link holds a random token of 128 bits; this table keeps only its
-- SHA-256, so a copy of the table, a backup, or the data API cannot download anyone's report.
-- A link works for 30 minutes from created_at and for three downloads; downloaded_at holds the
-- time of each one, so the row records when the link was made, each download, and when it
-- stopped working (expires_at, or the third download, whichever came first).
--
-- WHY A TABLE OF ITS OWN, NOT reports (V32). A reports row is a PDF the server rendered once,
-- about a Fundi Bora piece, with its bytes' hash and a snapshot of what it printed; its CHECKs
-- require a piece for every kind but one, and Kagua assessments have none. A link is a
-- different thing: it is issued before any PDF exists, may be downloaded up to three times or
-- never, and each download renders the assessment as it then stands. Loosening V32's CHECKs
-- for it would weaken the rules every Fundi Bora report row is held to, for rows that share
-- none of its columns but the session.
--
-- Only a shop account's assessment in progress (V39's shop_session_in_progress) gets a link;
-- the server checks that before the insert, since a CHECK cannot read another table.
--
-- DELETION. anonymise_user is not redefined. A row holds the token's hash, the assessment, the
-- shop account's id, and times: nothing that names or locates a person. The client's address,
-- which the route rate-limits on, is held in memory only and never stored. The PDF holds no
-- shop name, account name, or phone number, and is never stored either.
--
-- ROLLBACK. Revert the code first, then DROP TABLE shop_report_links. The data API's assessment
-- query counts this table's rows (report_links, report_downloads), so dropping it under this
-- code would fail every GET /api/v1/assessments; nothing else reads it. The rollback loses the
-- record of which assessments a customer took away and when; export the table first if that
-- matters. Reverting the code and keeping the table is safe: the old code never reads it.
--
-- The server tests have no database, so the CHECKs are proved by the self-test below, which
-- tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

CREATE TABLE shop_report_links (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- SHA-256 of the token, lowercase hex. The token itself is never stored.
    token_sha256    char(64) NOT NULL,
    -- The assessment the PDF is of. Gone with it if V11's purge ever removes it.
    session_id      uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    -- The shop account whose tablet showed the code.
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    -- Each download, in order. At most three.
    downloaded_at   timestamptz[] NOT NULL DEFAULT '{}',
    CONSTRAINT shop_report_links_token_hex
        CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
    -- Thirty minutes at most, as ShopReportService.LINK_LIFETIME says.
    CONSTRAINT shop_report_links_lifetime
        CHECK (expires_at > created_at AND expires_at <= created_at + interval '30 minutes'),
    -- Three downloads at most, as ShopReportService.MAX_DOWNLOADS says.
    CONSTRAINT shop_report_links_downloads
        CHECK (cardinality(downloaded_at) <= 3)
);

-- The public route looks a link up by its hash and nothing else.
CREATE UNIQUE INDEX shop_report_links_token_key ON shop_report_links (token_sha256);
-- How many links an assessment already has (at most five), and research reads per assessment.
CREATE INDEX shop_report_links_session_idx ON shop_report_links (session_id, created_at);

-- Self-test of the CHECKs and the index. One shop account and its assessment; everything is
-- rolled back by the known error at the end, and nothing outside this test's own rows is
-- counted. Country code 999 is assigned to nobody (V31).
DO $$
DECLARE
    shop     uuid := '00000000-0000-4000-8000-000000000400';
    session_ uuid := '00000000-0000-4000-8000-000000000401';
    link     uuid := '00000000-0000-4000-8000-000000000402';
    hash     text := repeat('ab', 32);
    refused  boolean;
    taken    integer;
BEGIN
    BEGIN
        INSERT INTO users (id, audience, shop_mode, phone, password_hash)
        VALUES (shop, 'buyer', true, '+999000000400', 'self-test');
        INSERT INTO sessions (id, user_id, item_type_id, audience, shop_tablet)
        VALUES (session_, shop, 'wooden-table', 'buyer', true);

        INSERT INTO shop_report_links (id, token_sha256, session_id, user_id, created_at, expires_at)
        VALUES (link, hash, session_, shop, now(), now() + interval '30 minutes');

        -- Three downloads are taken, the fourth is refused.
        UPDATE shop_report_links SET downloaded_at = downloaded_at || now() WHERE id = link;
        UPDATE shop_report_links SET downloaded_at = downloaded_at || now() WHERE id = link;
        UPDATE shop_report_links SET downloaded_at = downloaded_at || now() WHERE id = link;
        SELECT cardinality(downloaded_at) INTO taken FROM shop_report_links WHERE id = link;
        IF taken <> 3 THEN
            RAISE EXCEPTION 'V40 self-test: three downloads were not recorded';
        END IF;
        refused := false;
        BEGIN
            UPDATE shop_report_links SET downloaded_at = downloaded_at || now() WHERE id = link;
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V40 self-test: a fourth download was recorded';
        END IF;

        -- A link that lives longer than 30 minutes, or ends before it starts.
        refused := false;
        BEGIN
            INSERT INTO shop_report_links (token_sha256, session_id, user_id, created_at, expires_at)
            VALUES (repeat('cd', 32), session_, shop, now(), now() + interval '31 minutes');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V40 self-test: a link lived longer than 30 minutes';
        END IF;
        refused := false;
        BEGIN
            INSERT INTO shop_report_links (token_sha256, session_id, user_id, created_at, expires_at)
            VALUES (repeat('cd', 32), session_, shop, now(), now());
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V40 self-test: a link expired as it was made';
        END IF;

        -- A hash that is not a lowercase SHA-256: the token itself would fail this.
        refused := false;
        BEGIN
            INSERT INTO shop_report_links (token_sha256, session_id, user_id, created_at, expires_at)
            VALUES (upper(repeat('cd', 32)), session_, shop, now(), now() + interval '30 minutes');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V40 self-test: an uppercase hash was stored';
        END IF;

        -- The same hash twice.
        refused := false;
        BEGIN
            INSERT INTO shop_report_links (token_sha256, session_id, user_id, created_at, expires_at)
            VALUES (hash, session_, shop, now(), now() + interval '30 minutes');
        EXCEPTION WHEN unique_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V40 self-test: two links shared a token hash';
        END IF;

        RAISE EXCEPTION 'V40 self-test passed' USING ERRCODE = 'P0V40';
    EXCEPTION WHEN SQLSTATE 'P0V40' THEN
        -- Everything inside the block above, the account included, is rolled back.
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V40__shop_report_links')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
