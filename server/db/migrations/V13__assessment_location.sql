-- Kagua server schema, version 13.
--
-- Where an assessment was made.
--
-- WHY THIS EXISTS: the pilot wants to link assessments to the shops they happened in, and
-- nothing in the record does that today. A conversation names a piece of furniture; it
-- does not say which trading centre, which stall, or whether two assessments a week apart
-- were of stock from the same carpenter. One point per assessment answers all three.
--
-- WHAT THIS IS NOT. It is not tracking. One fix is taken at the start of an assessment,
-- while the app is in the foreground and the customer has just tapped an item type, and
-- nothing is read afterwards. The app holds no background location permission and must
-- not acquire one — see the note in AndroidManifest.xml, which is older than this file and
-- still governs.
--
-- CONSENT. Recording is chosen at sign-up, on by default, described as being for research,
-- and switchable in Settings for as long as the app is installed. Null is therefore the
-- expected value in this table and means only "not captured": the setting may be off, the
-- permission may never have been granted, and indoors a fix frequently never arrives.
-- Never read a null here as "no shop".
--
-- This widens what users.business_location established — one voluntary reading of one's own
-- premises — into a point per assessment, which taken together is a record of where
-- somebody has been. That is why anonymise_user is extended below rather than in a later
-- migration: an account deletion that left this behind would make the promise in V11 false.

BEGIN;

ALTER TABLE sessions
    -- Same three-column shape as users.business_location, for the same reason: a point
    -- without its accuracy is a false precision, and a fix good to 2km and one good to 5m
    -- are indistinguishable once it is dropped.
    ADD COLUMN location            geography(Point, 4326),
    ADD COLUMN location_accuracy_m real,
    -- When the fix was taken, which is not when the assessment started. A cached fix
    -- carries no hint of its age and a stale one is the more dangerous kind, because the
    -- accuracy figure makes it look trustworthy.
    ADD COLUMN location_at         timestamptz;

ALTER TABLE sessions
    ADD CONSTRAINT sessions_location_is_complete
        CHECK (location IS NOT NULL
               OR (location_accuracy_m IS NULL AND location_at IS NULL)),
    -- The app rejects anything coarser before sending, and the route drops it again. This
    -- is the backstop that keeps a district-sized "fix" out of the table for good.
    ADD CONSTRAINT sessions_location_accuracy_sane
        CHECK (location_accuracy_m IS NULL
               OR (location_accuracy_m > 0 AND location_accuracy_m <= 5000));

-- For "which assessments happened near here", which is the question this was collected
-- for. Same index type as the users equivalent.
CREATE INDEX sessions_location_idx ON sessions USING gist (location);

-- Extends V11. A trail of points is at least as identifying as the premises column that
-- function already clears — arguably more, since it records movement rather than one
-- address — so account deletion has to remove it too, or the wording in the app about
-- removing what we hold becomes untrue.
--
-- Deliberately does not delete the sessions themselves: V11 explains at length why the
-- conversations and photographs are kept, and this changes none of that reasoning.
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

INSERT INTO schema_migrations (version) VALUES ('V13__assessment_location')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
