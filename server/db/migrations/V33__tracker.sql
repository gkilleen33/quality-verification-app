-- Kagua server schema, version 33.
--
-- PesaLog's records: the mobile money payments a third app (module :tracker) reads from the
-- phone's SMS notifications, what the person says each was for, a daily count of messages
-- it could not read, and a record of each deletion the person asked for. Plus four consent
-- kinds: PesaLog's two terms boxes and its two questions.
--
-- WHY: Fundi Bora v0.5.0 reads M-Pesa messages from the SMS store, and on 12 November 2026
-- Google Play Protect blocked its install for asking to. Grady's answer (Slack, 12 November
-- 2026): read notifications instead, say plainly the data is for research, let people opt
-- out and delete their data, and do it in a separate app so that any objection from Google
-- lands on it and not on Fundi Bora. Nathan asked for the build on 13 November 2026.
--
-- SEPARATE TABLES. Not mpesa_transactions: the two sources read differently (the inbox
-- against notifications) and cover different networks, and a mixed table would need a
-- source column on every query to keep them apart. A PesaLog account is a Fundi Bora
-- account (audience fundi), so the two datasets join on user_id; and counterparties are
-- coded with the same key and rules (CounterpartyCoder.kt), so the same number or name has
-- the same code in both tables. Those are the two links between them.
--
-- WHAT IS NEVER STORED. As in V29: no message text, and no counterparty number or name,
-- only the HMAC code and its basis. No piece_id: pieces belong to Fundi Bora.
--
-- NETWORKS. M-Pesa and Airtel Money, the two MobileMoneyParser reads, mirrored by
-- MobileMoneyNetwork.kt and TrackerVocabularyTest. The code CHECK differs by network: an
-- M-Pesa code is V29's 8 to 12 letters and digits; an Airtel Money id is a letter or digit
-- and then 5 to 31 letters, digits, dots or hyphens, the shapes in MobileMoneyParserTest
-- ("P3RAIQM1E4N", "07912702081", "PP260909.1015.A12345", "AIR123456789"). A code is unique
-- per account and network, so an Airtel id that happens to look like an M-Pesa code is a
-- separate payment.
--
-- CONSENTS. consents_kind_known and consents_only_transfer_withdrawn are replaced, as V29
-- replaced them, to add tracker_terms and tracker_transfer (the two boxes on PesaLog's
-- terms screen, agreed together to the text in terms/tracker/) and tracker_read and
-- tracker_send (its two questions, TrackerConsentText.kt). Every kind but tracker_terms can
-- be withdrawn. PesaLog's terms have their own kinds rather than reusing terms and transfer
-- because Fundi Bora's gate reads the newest terms row for the account, which is the same
-- account.
--
-- DELETION ON REQUEST (Grady, 12 November 2026). The route behind "Delete my records from
-- the server" deletes the account's rows in both data tables and writes one row to
-- tracker_deletions: when, which account, and how many rows of each. That row holds none of
-- the deleted data. It is append-only, as V28's consents are, and stays when the account is
-- deleted, since it holds no name, number or text.
--
-- ACCOUNT DELETION. anonymise_user is redefined with every clearing from V32, plus the
-- account's rows in tracker_transactions and tracker_unread_counts, deleted whole for the
-- reasons V29 gives for the M-Pesa tables. The consent rows and tracker_deletions stay.
--
-- The server tests have no database, so the CHECKs, the triggers and the deletion are
-- proved by the self-tests below, which tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

CREATE TABLE tracker_transactions (
    id                   bigserial PRIMARY KEY,
    user_id              uuid NOT NULL REFERENCES users(id),
    -- Which provider's message it was (MobileMoneyNetwork).
    network              text NOT NULL,
    -- The code the message prints: M-Pesa's "SKA1B2C3D4", Airtel Money's transaction id.
    code                 text NOT NULL,
    direction            text NOT NULL,
    -- The parser's name for what happened (MpesaType), for both networks.
    tx_type              text NOT NULL,
    amount_kes           numeric(12, 2) NOT NULL,
    fee_kes              numeric(12, 2),
    balance_after_kes    numeric(14, 2),
    -- When the notification says the message arrived, on the phone's clock.
    occurred_at          timestamptz NOT NULL,
    counterparty_code    text,
    counterparty_basis   text,
    parser_version       integer NOT NULL,
    app_version          integer NOT NULL,
    received_at          timestamptz NOT NULL DEFAULT now(),
    category             text,
    comment              text,
    annotated_at         timestamptz,
    CONSTRAINT tracker_transactions_code_unique
        UNIQUE (user_id, network, code),
    CONSTRAINT tracker_transactions_network_known
        CHECK (network IN ('mpesa', 'airtel')),
    CONSTRAINT tracker_transactions_code_shape
        CHECK ((network = 'mpesa' AND code ~ '^[A-Z0-9]{8,12}$')
               OR (network = 'airtel' AND code ~ '^[A-Z0-9][A-Z0-9.-]{5,31}$')),
    CONSTRAINT tracker_transactions_direction_known
        CHECK (direction IN ('in', 'out', 'other')),
    CONSTRAINT tracker_transactions_type_known
        CHECK (tx_type IN ('send', 'receive', 'paybill', 'buy_goods', 'bundle', 'withdraw',
                           'deposit', 'airtime', 'fuliza_draw', 'fuliza_repay',
                           'savings_deposit', 'savings_withdraw', 'reversal')),
    CONSTRAINT tracker_transactions_amount_valid
        CHECK (amount_kes >= 0),
    CONSTRAINT tracker_transactions_fee_valid
        CHECK (fee_kes IS NULL OR fee_kes >= 0),
    CONSTRAINT tracker_transactions_basis_known
        CHECK (counterparty_basis IS NULL OR counterparty_basis IN ('phone', 'name')),
    CONSTRAINT tracker_transactions_counterparty_complete
        CHECK ((counterparty_code IS NULL AND counterparty_basis IS NULL)
               OR (counterparty_code IS NOT NULL AND counterparty_code ~ '^[0-9a-f]{64}$'
                   AND counterparty_basis IS NOT NULL)),
    CONSTRAINT tracker_transactions_category_known
        CHECK (category IS NULL
               OR category IN ('material_purchases', 'wages', 'machine_rental',
                               'customer_payment', 'other')),
    CONSTRAINT tracker_transactions_category_fits_direction
        CHECK (category IS NULL
               OR category = 'other'
               OR (direction = 'out' AND category IN ('material_purchases', 'wages', 'machine_rental'))
               OR (direction = 'in' AND category = 'customer_payment')),
    CONSTRAINT tracker_transactions_comment_length
        CHECK (comment IS NULL OR length(comment) BETWEEN 1 AND 500),
    CONSTRAINT tracker_transactions_parser_version_positive
        CHECK (parser_version > 0),
    CONSTRAINT tracker_transactions_app_version_positive
        CHECK (app_version > 0)
);

CREATE INDEX tracker_transactions_user_time_idx
    ON tracker_transactions (user_id, occurred_at);

-- "Everything paid to this person": across accounts, and against mpesa_transactions'
-- index of the same name, since the codes are the same.
CREATE INDEX tracker_transactions_counterparty_idx
    ON tracker_transactions (counterparty_code) WHERE counterparty_code IS NOT NULL;

-- How many mobile money messages PesaLog could not read, per network and day: one it could
-- not parse, or one whose notification was cut short or hidden. A count and nothing else.
CREATE TABLE tracker_unread_counts (
    user_id          uuid NOT NULL REFERENCES users(id),
    network          text NOT NULL,
    -- The day the messages arrived, in Nairobi time.
    day              date NOT NULL,
    unread           integer NOT NULL,
    parser_version   integer NOT NULL,
    app_version      integer NOT NULL,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, network, day),
    CONSTRAINT tracker_unread_counts_network_known
        CHECK (network IN ('mpesa', 'airtel')),
    CONSTRAINT tracker_unread_counts_unread_valid
        CHECK (unread >= 0),
    CONSTRAINT tracker_unread_counts_parser_version_positive
        CHECK (parser_version > 0),
    CONSTRAINT tracker_unread_counts_app_version_positive
        CHECK (app_version > 0)
);

-- One row per deletion the person asked for from PesaLog's Settings: the record that it
-- happened, holding none of what was deleted (terms section 8).
CREATE TABLE tracker_deletions (
    id                    bigserial PRIMARY KEY,
    user_id               uuid NOT NULL REFERENCES users(id),
    deleted_at            timestamptz NOT NULL DEFAULT now(),
    transactions_deleted  integer NOT NULL,
    unread_days_deleted   integer NOT NULL,
    app_version           integer,
    CONSTRAINT tracker_deletions_counts_valid
        CHECK (transactions_deleted >= 0 AND unread_days_deleted >= 0),
    CONSTRAINT tracker_deletions_app_version_positive
        CHECK (app_version IS NULL OR app_version > 0)
);

CREATE INDEX tracker_deletions_user_idx
    ON tracker_deletions (user_id, deleted_at);

CREATE TRIGGER tracker_deletions_append_only
    BEFORE UPDATE OR DELETE ON tracker_deletions
    FOR EACH ROW EXECUTE FUNCTION refuse_row_change();

-- Row triggers do not fire on TRUNCATE.
CREATE TRIGGER tracker_deletions_no_truncate
    BEFORE TRUNCATE ON tracker_deletions
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_row_change();

-- PesaLog's four kinds join the others. Same names, so nothing that refers to them moves.
ALTER TABLE consents DROP CONSTRAINT consents_kind_known;
ALTER TABLE consents ADD CONSTRAINT consents_kind_known
    CHECK (kind IN ('terms', 'transfer', 'mpesa_read', 'mpesa_send',
                    'tracker_terms', 'tracker_transfer', 'tracker_read', 'tracker_send'));

ALTER TABLE consents DROP CONSTRAINT consents_only_transfer_withdrawn;
ALTER TABLE consents ADD CONSTRAINT consents_only_transfer_withdrawn
    CHECK (action = 'agreed' OR kind IN ('transfer', 'mpesa_read', 'mpesa_send',
                                         'tracker_transfer', 'tracker_read', 'tracker_send'));

-- Self-test of the CHECKs and triggers, before anonymise_user is redefined below:
-- MigrationsTest reads every UPDATE and DELETE after that definition as part of the
-- function. One throwaway account; everything is rolled back by the known error at the end.
DO $$
DECLARE
    probe   uuid := '00000000-0000-4000-8000-000000000340';
    refused boolean;
BEGIN
    BEGIN
        -- A number needs a credential beside it (users_credentials_are_paired, V5).
        INSERT INTO users (id, audience, phone, password_hash) VALUES (probe, 'fundi', '+999000000033', 'self-test');

        INSERT INTO tracker_transactions (
            user_id, network, code, direction, tx_type, amount_kes, fee_kes, balance_after_kes,
            occurred_at, counterparty_code, counterparty_basis, parser_version, app_version
        ) VALUES (
            probe, 'mpesa', 'SKA1B2C3D4', 'out', 'send', 1500.00, 23.00, 4200.50,
            now(), repeat('a', 64), 'phone', 2, 1
        );

        -- The same code on the other network is another payment.
        INSERT INTO tracker_transactions (
            user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version
        ) VALUES (probe, 'airtel', 'SKA1B2C3D4', 'in', 'receive', 20, now(), 2, 1);

        -- Airtel Money ids in each shape the parser's tests hold.
        INSERT INTO tracker_transactions (
            user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version
        ) VALUES (probe, 'airtel', 'P3RAIQM1E4N', 'out', 'send', 20, now(), 2, 1),
                 (probe, 'airtel', '07912702081', 'out', 'bundle', 15, now(), 2, 1),
                 (probe, 'airtel', 'PP260909.1015.A12345', 'out', 'send', 500, now(), 2, 1);

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version
            ) VALUES (probe, 'mpesa', 'SKA1B2C3D4', 'out', 'send', 1500.00, now(), 2, 1);
        EXCEPTION WHEN unique_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_transactions accepted a second row with one network and code';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version
            ) VALUES (probe, 'tkash', 'SKA1B2C3D5', 'out', 'send', 10, now(), 2, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_transactions accepted the network tkash';
        END IF;

        -- An Airtel-shaped id with dots is not an M-Pesa code.
        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version
            ) VALUES (probe, 'mpesa', 'PP260909.1015', 'out', 'send', 10, now(), 2, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_transactions accepted a dotted M-Pesa code';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version
            ) VALUES (probe, 'airtel', 'ab12', 'out', 'send', 10, now(), 2, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_transactions accepted a short lowercase Airtel id';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version
            ) VALUES (probe, 'mpesa', 'SKA1B2C3D5', 'sideways', 'send', 10, now(), 2, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_transactions accepted the direction sideways';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version
            ) VALUES (probe, 'mpesa', 'SKA1B2C3D5', 'other', 'failed', 10, now(), 2, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_transactions accepted the type failed';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version
            ) VALUES (probe, 'mpesa', 'SKA1B2C3D5', 'out', 'send', -1, now(), 2, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_transactions accepted a negative amount';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at,
                counterparty_code, counterparty_basis, parser_version, app_version
            ) VALUES (probe, 'mpesa', 'SKA1B2C3D5', 'out', 'send', 10, now(), '+254712345678', 'phone', 2, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_transactions accepted a phone number as a counterparty code';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at,
                counterparty_code, parser_version, app_version
            ) VALUES (probe, 'mpesa', 'SKA1B2C3D5', 'out', 'send', 10, now(), repeat('a', 64), 2, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_transactions accepted a counterparty code without its basis';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at,
                counterparty_code, counterparty_basis, parser_version, app_version
            ) VALUES (probe, 'mpesa', 'SKA1B2C3D5', 'out', 'send', 10, now(), repeat('b', 64), 'email', 2, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_transactions accepted the basis email';
        END IF;

        INSERT INTO tracker_transactions (
            user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version,
            category, comment, annotated_at
        ) VALUES (probe, 'mpesa', 'SKA1B2C3D6', 'out', 'paybill', 800, now(), 2, 1, 'wages', 'Kevin on Tuesday', now());

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version, category
            ) VALUES (probe, 'mpesa', 'SKA1B2C3D7', 'out', 'send', 10, now(), 2, 1, 'customer_payment');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: money out accepted the category customer_payment';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version, category
            ) VALUES (probe, 'mpesa', 'SKA1B2C3D7', 'in', 'receive', 10, now(), 2, 1, 'wages');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: money in accepted the category wages';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version, category
            ) VALUES (probe, 'mpesa', 'SKA1B2C3D7', 'out', 'send', 10, now(), 2, 1, 'rent');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_transactions accepted the category rent';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version, comment
            ) VALUES (probe, 'mpesa', 'SKA1B2C3D7', 'out', 'send', 10, now(), 2, 1, '');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_transactions accepted an empty comment';
        END IF;

        INSERT INTO tracker_unread_counts (user_id, network, day, unread, parser_version, app_version)
        VALUES (probe, 'mpesa', current_date, 3, 2, 1),
               (probe, 'airtel', current_date, 1, 2, 1);

        refused := false;
        BEGIN
            INSERT INTO tracker_unread_counts (user_id, network, day, unread, parser_version, app_version)
            VALUES (probe, 'mpesa', current_date - 1, -1, 2, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_unread_counts accepted a negative count';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_unread_counts (user_id, network, day, unread, parser_version, app_version)
            VALUES (probe, 'tkash', current_date, 1, 2, 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_unread_counts accepted the network tkash';
        END IF;

        -- The deletion record: accepted, then never changed or removed.
        INSERT INTO tracker_deletions (user_id, transactions_deleted, unread_days_deleted, app_version)
        VALUES (probe, 6, 2, 1);

        refused := false;
        BEGIN
            INSERT INTO tracker_deletions (user_id, transactions_deleted, unread_days_deleted)
            VALUES (probe, -1, 0);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_deletions accepted a negative count';
        END IF;

        refused := false;
        BEGIN
            UPDATE tracker_deletions SET transactions_deleted = 0 WHERE user_id = probe;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_deletions accepted an UPDATE';
        END IF;

        refused := false;
        BEGIN
            DELETE FROM tracker_deletions WHERE user_id = probe;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_deletions accepted a DELETE';
        END IF;

        refused := false;
        BEGIN
            TRUNCATE tracker_deletions;
        EXCEPTION WHEN raise_exception THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: tracker_deletions accepted a TRUNCATE';
        END IF;

        -- PesaLog's four kinds are accepted; three can be withdrawn and its terms cannot.
        INSERT INTO consents (user_id, kind, action, version, language, text_sha256, audience, app_version)
        VALUES (probe, 'tracker_terms', 'agreed', '2026-11', 'en', repeat('c', 64), 'fundi', 1),
               (probe, 'tracker_transfer', 'agreed', '2026-11', 'en', repeat('c', 64), 'fundi', 1),
               (probe, 'tracker_read', 'agreed', '2026-11', 'en', repeat('d', 64), 'fundi', 1),
               (probe, 'tracker_send', 'agreed', '2026-11', 'en', repeat('e', 64), 'fundi', 1);
        INSERT INTO consents (user_id, kind, action, audience, app_version)
        VALUES (probe, 'tracker_send', 'withdrawn', 'fundi', 1),
               (probe, 'tracker_read', 'withdrawn', 'fundi', 1),
               (probe, 'tracker_transfer', 'withdrawn', 'fundi', 1);

        refused := false;
        BEGIN
            INSERT INTO consents (user_id, kind, action, audience, app_version)
            VALUES (probe, 'tracker_terms', 'withdrawn', 'fundi', 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: consents accepted a withdrawal of PesaLog''s terms';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO consents (user_id, kind, action, version, language, text_sha256, audience, app_version)
            VALUES (probe, 'tracker_upload', 'agreed', '2026-11', 'en', repeat('c', 64), 'fundi', 1);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V33 self-test: consents accepted the kind tracker_upload';
        END IF;

        -- Every earlier kind is still accepted.
        INSERT INTO consents (user_id, kind, action, version, language, text_sha256, audience, app_version)
        VALUES (probe, 'terms', 'agreed', '2026-11', 'en', repeat('f', 64), 'fundi', 1),
               (probe, 'transfer', 'agreed', '2026-11', 'en', repeat('f', 64), 'fundi', 1),
               (probe, 'mpesa_read', 'agreed', '2026-11', 'en', repeat('f', 64), 'fundi', 1),
               (probe, 'mpesa_send', 'agreed', '2026-11', 'en', repeat('f', 64), 'fundi', 1);

        RAISE EXCEPTION 'V33 self-test passed' USING ERRCODE = 'P0V33';
    EXCEPTION WHEN SQLSTATE 'P0V33' THEN
        -- Everything inside the block above, the probe account included, is rolled back.
        NULL;
    END;
END
$$;

-- As V32, plus the account's PesaLog transactions and unread counts, deleted whole.
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
-- DELETE, for the reason the first block gives. After anonymise_user the account holds no
-- PesaLog rows, and keeps its consent rows and its deletion record.
DO $$
DECLARE
    probe uuid := '00000000-0000-4000-8000-000000000341';
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (probe, 'fundi', '+999000000034', 'self-test');
        INSERT INTO tracker_transactions (
            user_id, network, code, direction, tx_type, amount_kes, occurred_at,
            counterparty_code, counterparty_basis, parser_version, app_version, category, comment, annotated_at
        ) VALUES (probe, 'mpesa', 'SKA1B2C3D4', 'out', 'send', 1500, now(), repeat('a', 64), 'phone', 2, 1,
                  'wages', 'Kamau, Friday', now()),
                 (probe, 'airtel', 'P3RAIQM1E4N', 'in', 'receive', 20, now(), NULL, NULL, 2, 1,
                  NULL, NULL, NULL);
        INSERT INTO tracker_unread_counts (user_id, network, day, unread, parser_version, app_version)
        VALUES (probe, 'airtel', current_date, 2, 2, 1);
        INSERT INTO tracker_deletions (user_id, transactions_deleted, unread_days_deleted, app_version)
        VALUES (probe, 4, 1, 1);
        INSERT INTO consents (user_id, kind, action, version, language, text_sha256, audience, app_version)
        VALUES (probe, 'tracker_terms', 'agreed', '2026-11', 'en', repeat('c', 64), 'fundi', 1),
               (probe, 'tracker_transfer', 'agreed', '2026-11', 'en', repeat('c', 64), 'fundi', 1);

        IF NOT anonymise_user(probe) THEN
            RAISE EXCEPTION 'V33 self-test: anonymise_user did not anonymise the probe account';
        END IF;
        IF EXISTS (SELECT 1 FROM tracker_transactions WHERE user_id = probe) THEN
            RAISE EXCEPTION 'V33 self-test: anonymise_user left PesaLog transactions behind';
        END IF;
        IF EXISTS (SELECT 1 FROM tracker_unread_counts WHERE user_id = probe) THEN
            RAISE EXCEPTION 'V33 self-test: anonymise_user left PesaLog unread counts behind';
        END IF;
        IF (SELECT count(*) FROM tracker_deletions WHERE user_id = probe) <> 1 THEN
            RAISE EXCEPTION 'V33 self-test: anonymise_user changed the deletion record';
        END IF;
        IF (SELECT count(*) FROM consents WHERE user_id = probe) <> 2 THEN
            RAISE EXCEPTION 'V33 self-test: anonymise_user changed the consent rows';
        END IF;

        RAISE EXCEPTION 'V33 deletion self-test passed' USING ERRCODE = 'P0V33';
    EXCEPTION WHEN SQLSTATE 'P0V33' THEN
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V33__tracker')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
