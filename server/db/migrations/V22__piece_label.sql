-- Piece names.
--
-- `pieces.label` has existed since V14 and nothing has ever written it. The app is about
-- to: the maker names a piece at the start of its first assessment and can rename it from
-- its card, so the home list can say "Dining table for the hotel" instead of "Table" three
-- times over. Two things follow for the schema.
--
-- The length is bounded here as well as on the phone, because the server's own rename
-- endpoint takes free text and a bound that lives only in one client is not a bound.
-- Eighty characters is a long name for a chair; the phone's field stops at the same
-- number.
--
-- A label can name a customer ("the stool for Mama Njeri"), so account deletion clears
-- it. As every change to anonymise_user since V11: replaced whole, because Postgres has
-- no way to amend a function body, and every clause of V21 is repeated.

BEGIN;

ALTER TABLE pieces
    ADD CONSTRAINT pieces_label_length
        CHECK (label IS NULL OR length(label) BETWEEN 1 AND 80);

-- As V21, plus pieces.label.
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

    -- What the maker called each piece. Often the customer's name.
    UPDATE pieces SET
        label = NULL
    WHERE user_id = target;

    -- Every device signed out. Otherwise a phone holding a live refresh token keeps
    -- working against an account that no longer has an owner.
    DELETE FROM refresh_tokens WHERE user_id = target;

    RETURN affected > 0;
END;
$$;

INSERT INTO schema_migrations (version) VALUES ('V22__piece_label')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
