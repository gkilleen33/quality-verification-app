-- Kagua server schema, version 29.
--
-- Fundi Bora's M-Pesa records: the transactions the phone read from a fundi's M-Pesa
-- messages, what the fundi says each was for, and a daily count of messages the phone could
-- not read. Plus two new consent kinds for the two questions that allow it.
--
-- WHY: Fundi Bora v0.5.0 adds a Finances tab (Grady's description, Slack, 11 November
-- 2026). The phone reads M-Pesa confirmations received after the fundi agrees, totals the
-- money in and out, and lets the fundi assign each transaction to a piece and a category.
-- With the fundi's second agreement, all of it is sent here, for the research record. The
-- data goes to our own database, not Supabase (Nathan and Grady, 9 November 2026).
--
-- WHAT IS NEVER STORED. No message text, and no counterparty number or name. The phone
-- sends the counterparty's number or name over HTTPS; the server codes it with a keyed
-- HMAC (CounterpartyCoder.kt, key /kagua/mpesa/hmac-key) and stores only the code and
-- what it was computed from (phone or name). The same number gives the same code for every
-- fundi, so payments can be matched across workshops without holding numbers.
--
-- CONSENTS. V28's consents_kind_known is replaced, as V20 replaced the ownership CHECKs,
-- to add mpesa_read (may the app read M-Pesa messages and keep them on the phone) and
-- mpesa_send (may it send them here). Both can be withdrawn, so
-- consents_only_transfer_withdrawn is replaced too. Agreements carry the version, language
-- and hash of the question's exact text (MpesaConsentText.kt), as a terms agreement does.
--
-- RE-UPLOADS ARE HARMLESS. The M-Pesa transaction code is unique per account, and the
-- upload inserts with ON CONFLICT DO NOTHING, so a phone that retries a batch the server
-- already holds changes nothing. The unread counts are one row per account and day, and
-- an upload replaces the day's count with the phone's.
--
-- DELETION. anonymise_user deletes the account's rows in both tables. A transaction's
-- comment is free text that can name a customer or a worker, and its amounts and times are
-- the fundi's own finances; nothing in them is worth keeping without the account. The
-- consent rows stay, as V28 says why.
--
-- The server tests have no database, so the CHECKs and the deletion are proved by the
-- self-test at the end, which tools/deploy.sh runs in its rolled-back rehearsal.

BEGIN;

CREATE TABLE mpesa_transactions (
    id                   bigserial PRIMARY KEY,
    user_id              uuid NOT NULL REFERENCES users(id),
    -- The code M-Pesa prints at the start of the message ("SKA1B2C3D4 Confirmed.").
    code                 text NOT NULL,
    direction            text NOT NULL,
    -- The parser's name for what happened (MpesaType).
    tx_type              text NOT NULL,
    amount_kes           numeric(12, 2) NOT NULL,
    -- The transaction cost; for a Fuliza draw, its access fee. Null when the text prints none.
    fee_kes              numeric(12, 2),
    -- The M-Pesa balance the text prints after the transaction. Null when it prints none.
    balance_after_kes    numeric(14, 2),
    -- When the message arrived on the phone. The phone's clock, the only one there is.
    occurred_at          timestamptz NOT NULL,
    counterparty_code    text,
    counterparty_basis   text,
    -- MobileMoneyParser.BUILTIN_VERSION on the phone that read it.
    parser_version       integer NOT NULL,
    app_version          integer NOT NULL,
    -- The server's clock, at the first upload.
    received_at          timestamptz NOT NULL DEFAULT now(),
    -- What the fundi says it was for. Changeable; each change stamps annotated_at.
    category             text,
    -- SET NULL, as for sessions.piece_id (V14): removing a piece must not remove the money.
    piece_id             uuid REFERENCES pieces(id) ON DELETE SET NULL,
    comment              text,
    annotated_at         timestamptz,
    CONSTRAINT mpesa_transactions_code_unique
        UNIQUE (user_id, code),
    CONSTRAINT mpesa_transactions_code_shape
        CHECK (code ~ '^[A-Z0-9]{8,12}$'),
    CONSTRAINT mpesa_transactions_direction_known
        CHECK (direction IN ('in', 'out', 'other')),
    CONSTRAINT mpesa_transactions_type_known
        CHECK (tx_type IN ('send', 'receive', 'paybill', 'buy_goods', 'bundle', 'withdraw',
                           'deposit', 'airtime', 'fuliza_draw', 'fuliza_repay',
                           'savings_deposit', 'savings_withdraw', 'reversal')),
    CONSTRAINT mpesa_transactions_amount_valid
        CHECK (amount_kes >= 0),
    CONSTRAINT mpesa_transactions_fee_valid
        CHECK (fee_kes IS NULL OR fee_kes >= 0),
    CONSTRAINT mpesa_transactions_basis_known
        CHECK (counterparty_basis IS NULL OR counterparty_basis IN ('phone', 'name')),
    -- A code is always a 64-character HMAC, and never without its basis.
    CONSTRAINT mpesa_transactions_counterparty_complete
        CHECK ((counterparty_code IS NULL AND counterparty_basis IS NULL)
               OR (counterparty_code ~ '^[0-9a-f]{64}$' AND counterparty_basis IS NOT NULL)),
    CONSTRAINT mpesa_transactions_category_known
        CHECK (category IS NULL
               OR category IN ('material_purchases', 'wages', 'machine_rental',
                               'customer_payment', 'other')),
    -- Money out has four categories, money in two, and money that stayed the fundi's own
    -- only "other" (MpesaCategory.directions).
    CONSTRAINT mpesa_transactions_category_fits_direction
        CHECK (category IS NULL
               OR category = 'other'
               OR (direction = 'out' AND category IN ('material_purchases', 'wages', 'machine_rental'))
               OR (direction = 'in' AND category = 'customer_payment')),
    CONSTRAINT mpesa_transactions_comment_length
        CHECK (comment IS NULL OR length(comment) BETWEEN 1 AND 500),
    CONSTRAINT mpesa_transactions_parser_version_positive
        CHECK (parser_version > 0),
    CONSTRAINT mpesa_transactions_app_version_positive
        CHECK (app_version > 0)
);

-- "This fundi's money in November": one account, in time order.
CREATE INDEX mpesa_transactions_user_time_idx
    ON mpesa_transactions (user_id, occurred_at);

-- "Everything paid to this person, across workshops": the reason the code is study-wide.
CREATE INDEX mpesa_transactions_counterparty_idx
    ON mpesa_transactions (counterparty_code) WHERE counterparty_code IS NOT NULL;

CREATE INDEX mpesa_transactions_piece_idx
    ON mpesa_transactions (piece_id) WHERE piece_id IS NOT NULL;

-- How many M-Pesa messages the phone could not read, per day, so the parser's coverage can
-- be measured. A count and nothing else: no text, no template, no code.
CREATE TABLE mpesa_unread_counts (
    user_id          uuid NOT NULL REFERENCES users(id),
    -- The day the messages arrived, in Nairobi time.
    day              date NOT NULL,
    unread           integer NOT NULL,
    -- The parser and app that last reported this day.
    parser_version   integer NOT NULL,
    app_version      integer NOT NULL,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, day),
    CONSTRAINT mpesa_unread_counts_unread_valid
        CHECK (unread >= 0),
    CONSTRAINT mpesa_unread_counts_parser_version_positive
        CHECK (parser_version > 0),
    CONSTRAINT mpesa_unread_counts_app_version_positive
        CHECK (app_version > 0)
);

-- The two M-Pesa questions join the two terms boxes. Same names, so nothing that refers to
-- them moves.
ALTER TABLE consents DROP CONSTRAINT consents_kind_known;
ALTER TABLE consents ADD CONSTRAINT consents_kind_known
    CHECK (kind IN ('terms', 'transfer', 'mpesa_read', 'mpesa_send'));

ALTER TABLE consents DROP CONSTRAINT consents_only_transfer_withdrawn;
ALTER TABLE consents ADD CONSTRAINT consents_only_transfer_withdrawn
    CHECK (action = 'agreed' OR kind IN ('transfer', 'mpesa_read', 'mpesa_send'));

-- As V26, plus the two M-Pesa deletes before the refresh tokens.
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

    -- The roster. A nickname identifies a person; the links to parts stay, nameless.
    UPDATE workers SET
        name = NULL
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

    -- Every device signed out. Otherwise a phone holding a live refresh token keeps
    -- working against an account that no longer has an owner.
    DELETE FROM refresh_tokens WHERE user_id = target;

    RETURN affected > 0;
END;
$$;

-- Self-test, against a throwaway account created and removed inside the test, as V28's.
-- Each refusal rolls back only its own block; a statement that should have been refused
-- and was not fails the migration. The block ends by raising a known error that its outer
-- handler catches, so the account and its rows never outlive the test.
DO $$
DECLARE
    probe   uuid := '00000000-0000-4000-8000-0000000000c9';
    refused boolean;
BEGIN
    BEGIN
        INSERT INTO users (id, audience) VALUES (probe, 'fundi');

        INSERT INTO mpesa_transactions (
            user_id, code, direction, tx_type, amount_kes, fee_kes, balance_after_kes,
            occurred_at, counterparty_code, counterparty_basis, parser_version, app_version
        ) VALUES (
            probe, 'SKA1B2C3D4', 'out', 'send', 1500.00, 23.00, 4200.50,
            now(), repeat('a', 64), 'phone', 2, 6
        );

        -- A re-upload of the same code is refused by the unique key, which the upload's
        -- ON CONFLICT DO NOTHING turns into no change.
        refused := false;
        BEGIN
            INSERT INTO mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version
            ) VALUES (probe, 'SKA1B2C3D4', 'out', 'send', 1500.00, now(), 2, 6);
        EXCEPTION WHEN unique_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: mpesa_transactions accepted a second row with one code';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version
            ) VALUES (probe, 'SKA1B2C3D5', 'sideways', 'send', 10, now(), 2, 6);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: mpesa_transactions accepted the direction sideways';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version
            ) VALUES (probe, 'SKA1B2C3D5', 'other', 'failed', 10, now(), 2, 6);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: mpesa_transactions accepted the type failed';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, occurred_at,
                counterparty_code, parser_version, app_version
            ) VALUES (probe, 'SKA1B2C3D5', 'out', 'send', 10, now(), repeat('a', 64), 2, 6);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: mpesa_transactions accepted a counterparty code without its basis';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, occurred_at,
                counterparty_code, counterparty_basis, parser_version, app_version
            ) VALUES (probe, 'SKA1B2C3D5', 'out', 'send', 10, now(), '+254712345678', 'phone', 2, 6);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: mpesa_transactions accepted a phone number as a counterparty code';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, occurred_at,
                counterparty_code, counterparty_basis, parser_version, app_version
            ) VALUES (probe, 'SKA1B2C3D5', 'out', 'send', 10, now(), repeat('b', 64), 'email', 2, 6);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: mpesa_transactions accepted the basis email';
        END IF;

        -- A category the direction allows is accepted; one it does not is refused. Tested
        -- with INSERTs, not UPDATEs: MigrationsTest reads every UPDATE after this file's
        -- anonymise_user as part of the function, and a later definition would then have
        -- to repeat them.
        INSERT INTO mpesa_transactions (
            user_id, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version,
            category, comment, annotated_at
        ) VALUES (probe, 'SKA1B2C3D6', 'out', 'paybill', 800, now(), 2, 6, 'wages', 'Kevin on Tuesday', now());

        refused := false;
        BEGIN
            INSERT INTO mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version, category
            ) VALUES (probe, 'SKA1B2C3D7', 'out', 'send', 10, now(), 2, 6, 'customer_payment');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: money out accepted the category customer_payment';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version, category
            ) VALUES (probe, 'SKA1B2C3D7', 'in', 'receive', 10, now(), 2, 6, 'wages');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: money in accepted the category wages';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version, category
            ) VALUES (probe, 'SKA1B2C3D7', 'out', 'send', 10, now(), 2, 6, 'rent');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: mpesa_transactions accepted the category rent';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, occurred_at, parser_version, app_version, comment
            ) VALUES (probe, 'SKA1B2C3D7', 'out', 'send', 10, now(), 2, 6, '');
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: mpesa_transactions accepted an empty comment';
        END IF;

        INSERT INTO mpesa_unread_counts (user_id, day, unread, parser_version, app_version)
        VALUES (probe, current_date, 3, 2, 6);

        refused := false;
        BEGIN
            INSERT INTO mpesa_unread_counts (user_id, day, unread, parser_version, app_version)
            VALUES (probe, current_date - 1, -1, 2, 6);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: mpesa_unread_counts accepted a negative count';
        END IF;

        -- The new consent kinds are accepted, and each can be withdrawn.
        INSERT INTO consents (user_id, kind, action, version, language, text_sha256, audience, app_version)
        VALUES (probe, 'mpesa_read', 'agreed', '2026-11', 'en', repeat('c', 64), 'fundi', 6),
               (probe, 'mpesa_send', 'agreed', '2026-11', 'en', repeat('d', 64), 'fundi', 6);
        INSERT INTO consents (user_id, kind, action, audience, app_version)
        VALUES (probe, 'mpesa_send', 'withdrawn', 'fundi', 6),
               (probe, 'mpesa_read', 'withdrawn', 'fundi', 6);

        refused := false;
        BEGIN
            INSERT INTO consents (user_id, kind, action, version, language, text_sha256, audience, app_version)
            VALUES (probe, 'mpesa_upload', 'agreed', '2026-11', 'en', repeat('c', 64), 'fundi', 6);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: consents accepted the kind mpesa_upload';
        END IF;

        -- The terms themselves still cannot be withdrawn.
        refused := false;
        BEGIN
            INSERT INTO consents (user_id, kind, action, audience, app_version)
            VALUES (probe, 'terms', 'withdrawn', 'fundi', 6);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V29 self-test: consents accepted a withdrawal of the terms';
        END IF;

        -- Deleting the account removes its M-Pesa rows and keeps its consent rows.
        IF NOT anonymise_user(probe) THEN
            RAISE EXCEPTION 'V29 self-test: anonymise_user did not anonymise the probe account';
        END IF;
        IF EXISTS (SELECT 1 FROM mpesa_transactions WHERE user_id = probe) THEN
            RAISE EXCEPTION 'V29 self-test: anonymise_user left M-Pesa transactions behind';
        END IF;
        IF EXISTS (SELECT 1 FROM mpesa_unread_counts WHERE user_id = probe) THEN
            RAISE EXCEPTION 'V29 self-test: anonymise_user left M-Pesa unread counts behind';
        END IF;
        IF (SELECT count(*) FROM consents WHERE user_id = probe) <> 4 THEN
            RAISE EXCEPTION 'V29 self-test: anonymise_user changed the consent rows';
        END IF;

        RAISE EXCEPTION 'V29 self-test passed' USING ERRCODE = 'P0V29';
    EXCEPTION WHEN SQLSTATE 'P0V29' THEN
        -- Everything inside the block above, the probe account included, is rolled back.
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V29__mpesa')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
