-- Kagua server schema, version 42.
--
-- Two more codes for the other person on every mobile money payment (release scope row 32;
-- Nathan, 17 November 2026). Merge after V41 (#117); the last migration of v0.7.0.
--
-- WHY. M-Pesa prints a full number on money sent ("JANE DOE 0720456123") and a masked one on
-- money received from a person ("JANE DOE 0720***123"). counterparty_code (V29, V33) is made
-- from the full number when there is one and from the name otherwise, so a send to Jane and a
-- receipt from Jane get two unrelated codes and cannot be matched.
--
-- WHAT. Two columns on tracker_transactions and on mpesa_transactions, beside
-- counterparty_code, which is unchanged so that every earlier row and the data API keep their
-- meaning:
--
-- - counterparty_name_code: the HMAC of the name, normalised as CounterpartyCoder.normaliseName
--   does (trimmed, case-folded, spaces collapsed), whenever the payment names somebody.
-- - counterparty_masked_code: the HMAC of the number cut to what M-Pesa shows on money
--   received, the first four and last three digits of the local form ("0720***123", as
--   MobileMoneyParser.maskedKey cuts it), whenever the payment carries a full or masked number.
--
-- Both use the study-wide key and CounterpartyCoder, with the inputs prefixed "name:" and
-- "masked:" so that the kinds never share a code, and so codes match across every account.
-- A name code therefore equals the counterparty_code of a row coded from the same name.
--
-- THE MATCHING RULE (docs/data-api.md). Two payments are with the same person when their
-- counterparty_code values are equal with basis 'phone', or when both their name codes and
-- their masked-number codes are equal. A masked number alone is shared by about a thousand
-- real numbers, and a name alone by namesakes; together they are the match. The server stores
-- the codes and computes no match.
--
-- WHAT IS NEVER STORED. As in V29 and V33: no name and no number, whole or masked. The phone
-- sends them over HTTPS and the server keeps only the codes.
--
-- ROWS ALREADY STORED CANNOT BE BACK-FILLED. The server never kept the plain name or number,
-- so it has nothing to make the new codes from. Rows uploaded before this migration keep null
-- in both columns. So do the rows that phones built before it upload afterwards wherever they
-- lack the input: PesaLog 0.1.0 and Fundi Bora v0.5.0 send the number and not the name on a
-- send, and no masked number on a receipt, so their sends get a masked-number code and no name
-- code, and their receipts a name code and no masked-number code.
--
-- DELETION. anonymise_user is not redefined. It already deletes the account's rows in both
-- tables whole (V29, V33), and the new columns go with them.
--
-- ROLLBACK. Revert the code first: the data API's /tracker-transactions and both upload
-- routes name these columns. Then drop the two indexes and the four columns and delete
-- 'V42__counterparty_codes' from schema_migrations. Rows keep counterparty_code either way;
-- the rollback loses only the new codes. Reverting the code and keeping the columns is safe:
-- the old code never reads them, and leaves them null.
--
-- The server tests have no database, so the CHECKs are proved by the self-test below, which
-- tools/deploy.sh runs in its rolled-back rehearsal. It counts and reads only the rows it
-- makes, under its own probe account: the live database already holds PesaLog's rows.

BEGIN;

ALTER TABLE tracker_transactions
    ADD COLUMN counterparty_name_code   text,
    ADD COLUMN counterparty_masked_code text;

ALTER TABLE tracker_transactions
    ADD CONSTRAINT tracker_transactions_name_code_shape
        CHECK (counterparty_name_code IS NULL OR counterparty_name_code ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT tracker_transactions_masked_code_shape
        CHECK (counterparty_masked_code IS NULL OR counterparty_masked_code ~ '^[0-9a-f]{64}$');

ALTER TABLE mpesa_transactions
    ADD COLUMN counterparty_name_code   text,
    ADD COLUMN counterparty_masked_code text;

ALTER TABLE mpesa_transactions
    ADD CONSTRAINT mpesa_transactions_name_code_shape
        CHECK (counterparty_name_code IS NULL OR counterparty_name_code ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT mpesa_transactions_masked_code_shape
        CHECK (counterparty_masked_code IS NULL OR counterparty_masked_code ~ '^[0-9a-f]{64}$');

-- "Every payment with this name and this masked number": the second half of the rule, across
-- accounts. The first half uses V29's and V33's counterparty_code indexes.
CREATE INDEX tracker_transactions_name_masked_idx
    ON tracker_transactions (counterparty_name_code, counterparty_masked_code)
    WHERE counterparty_name_code IS NOT NULL AND counterparty_masked_code IS NOT NULL;

CREATE INDEX mpesa_transactions_name_masked_idx
    ON mpesa_transactions (counterparty_name_code, counterparty_masked_code)
    WHERE counterparty_name_code IS NOT NULL AND counterparty_masked_code IS NOT NULL;

-- Self-test, against one throwaway account created and removed inside the test, as V33's.
-- Everything it reads is filtered on that account, so the live tables' own rows never count.
DO $$
DECLARE
    probe   uuid := '00000000-0000-4000-8000-000000000420';
    refused boolean;
    matched integer;
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (probe, 'fundi', '+999000000420', 'self-test');

        -- A send, coded from the full number, with a name code and a masked-number code; and a
        -- receipt from the same person, coded from the name, with the same two.
        INSERT INTO tracker_transactions (
            user_id, network, code, direction, tx_type, amount_kes, occurred_at,
            counterparty_code, counterparty_basis, counterparty_name_code, counterparty_masked_code,
            parser_version, app_version
        ) VALUES (probe, 'mpesa', 'SKA1B2C3D4', 'out', 'send', 1500, now(),
                  repeat('a', 64), 'phone', repeat('b', 64), repeat('c', 64), 2, 2),
                 (probe, 'mpesa', 'SKA1B2C3D5', 'in', 'receive', 700, now(),
                  repeat('b', 64), 'name', repeat('b', 64), repeat('c', 64), 2, 2),
                 -- A namesake whose number differs: same name code, another masked code.
                 (probe, 'mpesa', 'SKA1B2C3D6', 'in', 'receive', 50, now(),
                  repeat('b', 64), 'name', repeat('b', 64), repeat('d', 64), 2, 2),
                 -- A row from an older phone: neither new code.
                 (probe, 'airtel', 'P3RAIQM1E4N', 'out', 'send', 20, now(),
                  repeat('e', 64), 'phone', NULL, NULL, 2, 1);

        INSERT INTO mpesa_transactions (
            user_id, code, direction, tx_type, amount_kes, occurred_at,
            counterparty_code, counterparty_basis, counterparty_name_code, counterparty_masked_code,
            parser_version, app_version
        ) VALUES (probe, 'SKA1B2C3D7', 'in', 'receive', 700, now(),
                  repeat('b', 64), 'name', repeat('b', 64), NULL, 2, 6);

        -- The index's question, on the probe's rows only: the send and the first receipt
        -- match; the namesake does not.
        SELECT count(*) INTO matched
          FROM tracker_transactions s
          JOIN tracker_transactions r
            ON r.counterparty_name_code = s.counterparty_name_code
           AND r.counterparty_masked_code = s.counterparty_masked_code
         WHERE s.user_id = probe AND r.user_id = probe
           AND s.code = 'SKA1B2C3D4' AND r.direction = 'in';
        IF matched <> 1 THEN
            RAISE EXCEPTION 'V42 self-test: expected the send to match one receipt, matched %', matched;
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at,
                counterparty_name_code, parser_version, app_version
            ) VALUES (probe, 'mpesa', 'SKA1B2C3E1', 'in', 'receive', 10, now(), 'jane doe', 2, 2);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V42 self-test: tracker_transactions accepted a name as a name code';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at,
                counterparty_masked_code, parser_version, app_version
            ) VALUES (probe, 'mpesa', 'SKA1B2C3E2', 'in', 'receive', 10, now(), '0720***123', 2, 2);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V42 self-test: tracker_transactions accepted a masked number as a masked-number code';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO tracker_transactions (
                user_id, network, code, direction, tx_type, amount_kes, occurred_at,
                counterparty_masked_code, parser_version, app_version
            ) VALUES (probe, 'mpesa', 'SKA1B2C3E3', 'in', 'receive', 10, now(), upper(repeat('c', 64)), 2, 2);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V42 self-test: tracker_transactions accepted an upper-case code';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, occurred_at,
                counterparty_name_code, parser_version, app_version
            ) VALUES (probe, 'SKA1B2C3E4', 'in', 'receive', 10, now(), 'jane doe', 2, 6);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V42 self-test: mpesa_transactions accepted a name as a name code';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO mpesa_transactions (
                user_id, code, direction, tx_type, amount_kes, occurred_at,
                counterparty_masked_code, parser_version, app_version
            ) VALUES (probe, 'SKA1B2C3E5', 'in', 'receive', 10, now(), '+254720456123', 2, 6);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V42 self-test: mpesa_transactions accepted a number as a masked-number code';
        END IF;

        -- Deleting the account still removes its rows in both tables, new columns and all.
        IF NOT anonymise_user(probe) THEN
            RAISE EXCEPTION 'V42 self-test: anonymise_user did not anonymise the probe account';
        END IF;
        IF EXISTS (SELECT 1 FROM tracker_transactions WHERE user_id = probe) THEN
            RAISE EXCEPTION 'V42 self-test: anonymise_user left PesaLog transactions behind';
        END IF;
        IF EXISTS (SELECT 1 FROM mpesa_transactions WHERE user_id = probe) THEN
            RAISE EXCEPTION 'V42 self-test: anonymise_user left M-Pesa transactions behind';
        END IF;

        RAISE EXCEPTION 'V42 self-test passed' USING ERRCODE = 'P0V42';
    EXCEPTION WHEN SQLSTATE 'P0V42' THEN
        -- Everything inside the block above, the probe account included, is rolled back.
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V42__counterparty_codes')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
