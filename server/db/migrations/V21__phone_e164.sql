-- Kagua server schema, version 21.
--
-- Every stored phone number in one form, E.164 (+254712345678), and the database refusing
-- any other.
--
-- WHY: enrolment is about to match people by phone — a listed number against the number
-- somebody signs up with, and later a verification code sent to it. A match on text only
-- works if the same number is always the same text. Registration already insists on a
-- leading + (AuthDto.validatePhone), but that still lets through the commonest slip: Kagua
-- prefills +254, the person types their number as they say it, 0712…, and the account is
-- stored as +2540712…, which no listed number will ever equal. V5 left normalisation to
-- the client "for now"; this is the database half of ending that, and the server half
-- (libphonenumber at registration and sign-in) follows separately.
--
-- The rewrite only touches numbers that change, and keeps what they were in
-- phone_as_entered, so the backfill can be reversed with one UPDATE. That column is a phone
-- number too, so anonymise_user clears it along with phone.
--
-- Two accounts that turn out to hold the same number once normalised stop the migration
-- before anything is written. That is one person with two accounts, or a typo that now
-- collides with somebody else, and either way a person has to decide which row keeps it.

BEGIN;

-- What each number becomes. Spaces, hyphens, dots and brackets go first; then the four
-- ways a Kenyan number arrives in the wrong shape. Anything else is left as it is: it
-- either already matches the CHECK below or the migration fails on it, which is the
-- right outcome for a number nobody can read.
CREATE TEMPORARY TABLE v21_phones ON COMMIT DROP AS
SELECT id,
       phone AS before,
       CASE
           WHEN stripped ~ '^00[1-9]'          THEN '+' || substr(stripped, 3)    -- 00254…
           WHEN stripped ~ '^0[0-9]{9}$'       THEN '+254' || substr(stripped, 2) -- 0712…
           WHEN stripped ~ '^254[0-9]{9}$'     THEN '+' || stripped               -- 254712…
           WHEN stripped ~ '^\+2540[0-9]{9}$'  THEN '+254' || substr(stripped, 6) -- +2540712…
           ELSE stripped
       END AS after
FROM (
    SELECT id, phone, regexp_replace(phone, '[[:space:]().-]', '', 'g') AS stripped
    FROM users
    WHERE phone IS NOT NULL
) s;

DO $$
DECLARE
    clashes integer;
BEGIN
    SELECT count(*) INTO clashes
    FROM (SELECT after FROM v21_phones GROUP BY after HAVING count(*) > 1) d;
    IF clashes > 0 THEN
        RAISE EXCEPTION 'V21: % phone number(s) belong to more than one account once normalised; resolve them by hand, then apply again', clashes;
    END IF;
END;
$$;

ALTER TABLE users ADD COLUMN phone_as_entered text;

UPDATE users u
SET phone_as_entered = p.before,
    phone            = p.after
FROM v21_phones p
WHERE u.id = p.id AND p.before <> p.after;

ALTER TABLE users
    ADD CONSTRAINT users_phone_is_e164
        CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$');

-- As V13, plus phone_as_entered. Replaced whole because Postgres has no way to amend a
-- function body.
CREATE OR REPLACE FUNCTION anonymise_user(target uuid)
RETURNS boolean
LANGUAGE plpgsql
AS $$
DECLARE
    affected integer;
BEGIN
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

    -- Every device signed out. Otherwise a phone holding a live refresh token keeps
    -- working against an account that no longer has an owner.
    DELETE FROM refresh_tokens WHERE user_id = target;

    RETURN affected > 0;
END;
$$;

INSERT INTO schema_migrations (version) VALUES ('V21__phone_e164')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
