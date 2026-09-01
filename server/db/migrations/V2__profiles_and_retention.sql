-- Kagua server schema, version 2.
--
-- Two unrelated changes that happen to land together:
--   1. Registration collects a profile, including an optional GPS point for a
--      business. This is the first thing in the schema that actually uses PostGIS.
--   2. A chat the customer deletes on their phone is retained here for 7 days.

BEGIN;

-- ---------------------------------------------------------------------------
-- Profiles, collected at registration
-- ---------------------------------------------------------------------------

ALTER TABLE users
    ADD COLUMN display_name                 text,
    -- 'individual' or 'business'. Nullable because rows created before this
    -- migration have no answer, and inventing one would be worse than null.
    ADD COLUMN account_type                 text,
    ADD COLUMN business_name                text,
    -- Where the business is, captured only when they register while standing in
    -- it. Null is the common case and means nothing more than "not captured" —
    -- never treat it as "no premises".
    ADD COLUMN business_location            geography(Point, 4326),
    -- Metres, straight from the Android location provider. A point without its
    -- accuracy is a false precision: a fix good to 2km and one good to 5m look
    -- identical in the geometry, and only one of them can support "workshops
    -- near me".
    ADD COLUMN business_location_accuracy_m real,
    ADD COLUMN business_location_at         timestamptz;

ALTER TABLE users
    ADD CONSTRAINT users_account_type_known
        CHECK (account_type IS NULL OR account_type IN ('individual', 'business')),
    -- A business with no name is a form that half-submitted. Cheap to enforce
    -- here, and it turns an app bug into an error instead of a silent gap in the
    -- data we are collecting this for.
    ADD CONSTRAINT users_business_needs_name
        CHECK (account_type IS DISTINCT FROM 'business' OR business_name IS NOT NULL),
    -- Accuracy and timestamp only mean something alongside a point.
    ADD CONSTRAINT users_location_is_complete
        CHECK (business_location IS NOT NULL
               OR (business_location_accuracy_m IS NULL AND business_location_at IS NULL));

CREATE INDEX users_business_location_idx ON users USING gist (business_location);

-- ---------------------------------------------------------------------------
-- Retention for chats deleted on the phone
-- ---------------------------------------------------------------------------

-- Set when the customer deletes the assessment on their device. The row stays
-- here; sync stops returning it. Retained 7 days, then really deleted.
--
-- This is a deliberate divergence between what the customer sees and what we
-- hold, so it has to be disclosed in the app rather than discovered. See the
-- retention note in docs/phase-2-backend.md.
ALTER TABLE sessions ADD COLUMN client_deleted_at timestamptz;

-- Partial: the overwhelming majority of rows are not deleted, and the purge job
-- only ever asks about the ones that are.
CREATE INDEX sessions_client_deleted_idx
    ON sessions (client_deleted_at) WHERE client_deleted_at IS NOT NULL;

-- Returns how many sessions it removed, so the timer can log a number rather
-- than "ran". Messages and attachments go with them by cascade.
--
-- NOTE: this deletes rows, not photo files. Once blobs are stored on disk, the
-- job needs a second pass to remove any file no longer referenced by an
-- attachment row. Until the blob store exists there is nothing to sweep, and
-- writing that pass now would be untested code guarding an empty directory.
CREATE OR REPLACE FUNCTION purge_deleted_sessions(retention interval DEFAULT '7 days')
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    removed integer;
BEGIN
    WITH gone AS (
        DELETE FROM sessions
        WHERE client_deleted_at IS NOT NULL
          AND client_deleted_at < now() - retention
        RETURNING 1
    )
    SELECT count(*) INTO removed FROM gone;
    RETURN removed;
END;
$$;

INSERT INTO schema_migrations (version) VALUES ('V2__profiles_and_retention')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
