-- Camera metadata kept with each photo (v0.4.0, D3).
--
-- From v0.4.0 the phone reads chosen EXIF fields from the original photo before it
-- re-encodes it, and sends them beside the photo in the turn's photo_meta. The uploaded
-- JPEG carries no metadata. The server checks them against the known keys
-- (shared PhotoMeta.FIELDS) and writes them here with the attachment row: when the photo
-- was taken, the camera and its settings, serial numbers and owner names, the size before
-- shrinking, and GPS only while the app's location switch is on.
--
-- Never sent to Claude. ChatStore.history selects id, message_id, sha256 and mime_type from
-- attachments and nothing else, and that query is the only way a photo reaches a request.
--
-- Nullable, and null for every existing row and for every photo from a v0.3.0 or older
-- app: null means "not sent", not "the camera wrote nothing". A photo uploaded in two
-- turns has two attachment rows, each with what its own turn carried.
--
-- No CHECK on the keys: the server drops unknown keys before the insert, and a CHECK that
-- disagreed with it would fail the turn's transaction. The type is checked because nothing
-- the server writes is anything but an object.

BEGIN;

ALTER TABLE attachments
    ADD COLUMN IF NOT EXISTS photo_meta jsonb;

ALTER TABLE attachments
    ADD CONSTRAINT attachments_photo_meta_object
        CHECK (photo_meta IS NULL OR jsonb_typeof(photo_meta) = 'object');

-- As V23, plus attachments.photo_meta. The photographs stay with a deleted account's
-- assessments; their metadata goes, since GPS points, serial numbers and owner names tie
-- them to a person.
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

    -- Every device signed out. Otherwise a phone holding a live refresh token keeps
    -- working against an account that no longer has an owner.
    DELETE FROM refresh_tokens WHERE user_id = target;

    RETURN affected > 0;
END;
$$;

INSERT INTO schema_migrations (version) VALUES ('V25__photo_meta')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
