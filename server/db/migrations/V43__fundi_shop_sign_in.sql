-- Kagua server schema, version 43.
--
-- A fundi signs in to Kagua with their Fundi Bora number and password, and Kagua opens in shop
-- mode (release scope row 53; Nathan, 24 November 2026). Merge after V42 (#118).
--
-- WHO: every Fundi Bora account. A fundi who types their Fundi Bora number and password into
-- Kagua v0.7.0 or later, on a number with no Kagua account that the password opens, is signed in
-- to a Kagua shop account linked to their Fundi Bora account. The tablet then behaves as row 12's
-- shop tablet (V39). The shop invite code (V39, row 12) stays, for shop owners without Fundi Bora.
--
-- THE LINKED SHOP ACCOUNT. A users row with audience 'buyer', shop_mode true, and
-- shop_of_user_id naming the Fundi Bora account. The shop's assessments are kept on it, apart
-- from the Fundi Bora account's own records, as any Kagua account's are. It has no number of
-- its own (phone NULL), so it never meets a personal Kagua account on the same number under
-- V38's unique index on (phone, audience), and no password of its own (password_hash NULL): the
-- password is always checked against the Fundi Bora account, at sign-in and at the shop owner's
-- password check. The server makes it the first time the fundi signs in to Kagua this way.
--
-- ONE LIVE SHOP PER FUNDI BORA ACCOUNT. users_shop_of_user_key is unique on shop_of_user_id
-- among accounts not deleted. The sign-in inserts with ON CONFLICT on that index, so two
-- tablets signing in at once make one account. A shop account deleted from Kagua keeps its link
-- for the record, and the next sign-in makes a new one.
--
-- WHAT IS CHECKED. users_shop_of_user_shape: a linked row is a Kagua shop-mode account with no
-- number and no password, and is not linked to itself. A CHECK cannot read the other row, so the
-- trigger users_shop_of_user_is_fundi refuses a link to anything but a Fundi Bora account.
--
-- DELETION. anonymise_user is redefined with every clearing from V41, plus one statement: it
-- anonymises the Fundi Bora account's linked shop account, the same way, with its tokens. The
-- shop account holds no name, number or password to clear; its assessments stay, as every
-- deleted account's do. The link itself stays: it joins two random ids and names nobody. Deleting
-- the shop account alone (from Kagua's settings) leaves the Fundi Bora account untouched.
--
-- ROLLBACK. Revert the code first: sign-in, findUser, the admin portal and the data API name the
-- column. Then restore V41's anonymise_user, drop the trigger and its function, the CHECK, the
-- index and the column, and delete 'V43__fundi_shop_sign_in' from schema_migrations. Linked shop
-- accounts stay as Kagua shop-mode rows with no number and no password, which nobody can sign in
-- to; their assessments stay. Reverting the code and keeping the column is safe: the old code
-- never reads it, and a Kagua sign-in with Fundi Bora details is refused again, as in #114.
--
-- The server tests have no database, so the CHECK, the index, the trigger and the deletion are
-- proved by the self-tests below, which tools/deploy.sh runs in its rolled-back rehearsal. Each
-- counts only rows it made. Country code 999 is assigned to nobody (V31).

BEGIN;

ALTER TABLE users ADD COLUMN shop_of_user_id uuid REFERENCES users(id);

ALTER TABLE users
    ADD CONSTRAINT users_shop_of_user_shape CHECK (
        shop_of_user_id IS NULL
        OR (audience = 'buyer' AND shop_mode AND phone IS NULL AND password_hash IS NULL
            AND shop_of_user_id <> id)
    );

CREATE UNIQUE INDEX users_shop_of_user_key ON users (shop_of_user_id)
    WHERE shop_of_user_id IS NOT NULL AND deleted_at IS NULL;

-- A CHECK sees only its own row, so the audience of the account linked to is checked here.
CREATE FUNCTION users_shop_of_user_is_fundi() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.shop_of_user_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM users f WHERE f.id = NEW.shop_of_user_id AND f.audience = 'fundi'
    ) THEN
        RAISE EXCEPTION 'users.shop_of_user_id must name a Fundi Bora account'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_shop_of_user_is_fundi
    BEFORE INSERT OR UPDATE OF shop_of_user_id ON users
    FOR EACH ROW EXECUTE FUNCTION users_shop_of_user_is_fundi();

-- Self-test of the CHECK, the index and the trigger, before anonymise_user is redefined below:
-- MigrationsTest reads every UPDATE and DELETE after that definition as part of the function.
-- Everything is rolled back by the known error at the end.
DO $$
DECLARE
    fundi     uuid := '00000000-0000-4000-8000-000000000430';
    personal  uuid := '00000000-0000-4000-8000-000000000431';
    shop      uuid := '00000000-0000-4000-8000-000000000432';
    made      integer;
    refused   boolean;
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (fundi, 'fundi', '+999000000430', 'self-test');
        -- A personal Kagua account on the same number (V38).
        INSERT INTO users (id, audience, phone, password_hash) VALUES (personal, 'buyer', '+999000000430', 'self-test');

        -- The shop account the sign-in makes, by the statement PostgresAuthStore.shopAccountFor
        -- runs. Run twice, as two tablets signing in at once would: one account.
        INSERT INTO users (audience, shop_mode, shop_of_user_id)
        SELECT 'buyer', true, f.id FROM users f
         WHERE f.id = fundi AND f.audience = 'fundi' AND f.deleted_at IS NULL AND f.disabled_at IS NULL
        ON CONFLICT (shop_of_user_id) WHERE shop_of_user_id IS NOT NULL AND deleted_at IS NULL DO NOTHING;
        INSERT INTO users (audience, shop_mode, shop_of_user_id)
        SELECT 'buyer', true, f.id FROM users f
         WHERE f.id = fundi AND f.audience = 'fundi' AND f.deleted_at IS NULL AND f.disabled_at IS NULL
        ON CONFLICT (shop_of_user_id) WHERE shop_of_user_id IS NOT NULL AND deleted_at IS NULL DO NOTHING;
        SELECT count(*) INTO made FROM users WHERE shop_of_user_id = fundi;
        IF made <> 1 THEN
            RAISE EXCEPTION 'V43 self-test: two sign-ins made % shop accounts, not one', made;
        END IF;

        -- A second live shop account for the same Fundi Bora account is refused.
        refused := false;
        BEGIN
            INSERT INTO users (id, audience, shop_mode, shop_of_user_id) VALUES (shop, 'buyer', true, fundi);
        EXCEPTION WHEN unique_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V43 self-test: a Fundi Bora account took two live shop accounts';
        END IF;

        -- Linked to a Kagua account, not a Fundi Bora one: the trigger refuses it.
        refused := false;
        BEGIN
            INSERT INTO users (id, audience, shop_mode, shop_of_user_id) VALUES (shop, 'buyer', true, personal);
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V43 self-test: a shop account was linked to a Kagua account';
        END IF;

        -- A linked row with a number, a password, without shop mode, or for Fundi Bora is refused.
        -- Each is tried as a deleted account, so the unique index on the live one cannot be what
        -- refuses it.
        refused := false;
        BEGIN
            INSERT INTO users (id, audience, shop_mode, shop_of_user_id, phone, deleted_at)
            VALUES (shop, 'buyer', true, fundi, '+999000000432', now());
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V43 self-test: a linked shop account took a number';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO users (id, audience, shop_mode, shop_of_user_id, password_hash, deleted_at)
            VALUES (shop, 'buyer', true, fundi, 'self-test', now());
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V43 self-test: a linked shop account took a password of its own';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO users (id, audience, shop_mode, shop_of_user_id, deleted_at)
            VALUES (shop, 'buyer', false, fundi, now());
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V43 self-test: a linked account was made without shop mode';
        END IF;

        refused := false;
        BEGIN
            INSERT INTO users (id, audience, shop_mode, shop_of_user_id, deleted_at)
            VALUES (shop, 'fundi', false, fundi, now());
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V43 self-test: a Fundi Bora account was linked as a shop';
        END IF;

        -- An existing personal Kagua account cannot be turned into a linked one.
        refused := false;
        BEGIN
            UPDATE users SET shop_of_user_id = fundi WHERE id = personal;
        EXCEPTION WHEN check_violation THEN
            refused := true;
        END;
        IF NOT refused THEN
            RAISE EXCEPTION 'V43 self-test: a personal Kagua account was linked as a shop';
        END IF;

        -- Once the shop account is deleted, the next sign-in makes a new one; the old keeps its link.
        UPDATE users SET deleted_at = now() WHERE shop_of_user_id = fundi;
        INSERT INTO users (audience, shop_mode, shop_of_user_id)
        SELECT 'buyer', true, f.id FROM users f
         WHERE f.id = fundi AND f.audience = 'fundi' AND f.deleted_at IS NULL AND f.disabled_at IS NULL
        ON CONFLICT (shop_of_user_id) WHERE shop_of_user_id IS NOT NULL AND deleted_at IS NULL DO NOTHING;
        SELECT count(*) INTO made FROM users WHERE shop_of_user_id = fundi AND deleted_at IS NULL;
        IF made <> 1 THEN
            RAISE EXCEPTION 'V43 self-test: no new shop account after the first was deleted';
        END IF;
        SELECT count(*) INTO made FROM users WHERE shop_of_user_id = fundi;
        IF made <> 2 THEN
            RAISE EXCEPTION 'V43 self-test: the deleted shop account lost its link';
        END IF;

        RAISE EXCEPTION 'V43 self-test passed' USING ERRCODE = 'P0V43';
    EXCEPTION WHEN SQLSTATE 'P0V43' THEN
        -- Everything inside the block above, every account included, is rolled back.
        NULL;
    END;
END
$$;

-- As V41, plus the Fundi Bora account's linked Kagua shop account (V43), anonymised the same way.
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

    -- The Kagua shop account this Fundi Bora account signs in to (V43, row 53), deleted the
    -- same way and with it: its tablets are signed out, and its assessments stay. A shop
    -- account links to nothing of its own, so this goes one level deep.
    PERFORM anonymise_user(u.id) FROM users u WHERE u.shop_of_user_id = target AND u.anonymised_at IS NULL;

    RETURN affected > 0;
END;
$$;

-- Self-test of the deletion, after the definition: INSERTs only, never an UPDATE or a DELETE,
-- for the reason the first block gives. Deleting a Fundi Bora account deletes its linked shop
-- account and signs its tablet out, keeps the link, and leaves a personal Kagua account on the
-- same number alone. Deleting a shop account alone leaves its Fundi Bora account alone.
DO $$
DECLARE
    fundi     uuid := '00000000-0000-4000-8000-000000000433';
    shop      uuid := '00000000-0000-4000-8000-000000000434';
    personal  uuid := '00000000-0000-4000-8000-000000000435';
    fundi_b   uuid := '00000000-0000-4000-8000-000000000436';
    shop_b    uuid := '00000000-0000-4000-8000-000000000437';
BEGIN
    BEGIN
        INSERT INTO users (id, audience, phone, password_hash) VALUES (fundi, 'fundi', '+999000000433', 'self-test');
        INSERT INTO users (id, audience, phone, password_hash) VALUES (personal, 'buyer', '+999000000433', 'self-test');
        INSERT INTO users (id, audience, shop_mode, shop_of_user_id) VALUES (shop, 'buyer', true, fundi);
        INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
        VALUES (shop, repeat('43', 32), now() + interval '1 day');

        IF NOT anonymise_user(fundi) THEN
            RAISE EXCEPTION 'V43 self-test: anonymise_user did not anonymise the Fundi Bora account';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM users WHERE id = shop AND deleted_at IS NOT NULL AND anonymised_at IS NOT NULL) THEN
            RAISE EXCEPTION 'V43 self-test: deleting the Fundi Bora account left its shop account';
        END IF;
        IF EXISTS (SELECT 1 FROM refresh_tokens WHERE user_id = shop) THEN
            RAISE EXCEPTION 'V43 self-test: the shop account''s tablet is still signed in';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM users WHERE id = shop AND shop_of_user_id = fundi) THEN
            RAISE EXCEPTION 'V43 self-test: deletion lost the shop account''s link';
        END IF;
        IF EXISTS (SELECT 1 FROM users WHERE id = personal AND (deleted_at IS NOT NULL OR phone IS NULL)) THEN
            RAISE EXCEPTION 'V43 self-test: deleting the Fundi Bora account touched the personal Kagua account';
        END IF;

        INSERT INTO users (id, audience, phone, password_hash) VALUES (fundi_b, 'fundi', '+999000000436', 'self-test');
        INSERT INTO users (id, audience, shop_mode, shop_of_user_id) VALUES (shop_b, 'buyer', true, fundi_b);
        IF NOT anonymise_user(shop_b) THEN
            RAISE EXCEPTION 'V43 self-test: anonymise_user did not anonymise the shop account';
        END IF;
        IF EXISTS (SELECT 1 FROM users WHERE id = fundi_b AND (deleted_at IS NOT NULL OR password_hash IS NULL)) THEN
            RAISE EXCEPTION 'V43 self-test: deleting the shop account touched its Fundi Bora account';
        END IF;

        RAISE EXCEPTION 'V43 deletion self-test passed' USING ERRCODE = 'P0V43';
    EXCEPTION WHEN SQLSTATE 'P0V43' THEN
        NULL;
    END;
END
$$;

INSERT INTO schema_migrations (version) VALUES ('V43__fundi_shop_sign_in')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
