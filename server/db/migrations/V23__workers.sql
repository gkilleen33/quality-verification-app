-- A workshop's workers, and who worked on which part of a piece.
--
-- Only the workshop's manager uses Fundi Bora. Workers are names on the manager's roster,
-- recorded against each assessment so a report on one part of a piece can say who did
-- that part. Nothing here scores a worker: a row links a person to a part of a piece on
-- one assessment, and what the assessment found about that part is the verdict's.
--
-- Ids are made on the phone, as session and piece ids are, so the picker works offline
-- and an assessment's opening turn can name a worker the server has never seen.
--
-- Archive, never delete: a past piece keeps the name of the person who worked on it.
-- Account deletion clears names, since a nickname identifies a person.

BEGIN;

CREATE TABLE workers (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Nullable only so anonymise_user can clear it.
    name        text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- The server's receipt time of the last write, never the phone's clock (see V18).
    updated_at  timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz,
    CONSTRAINT workers_name_length
        CHECK (name IS NULL OR length(name) BETWEEN 1 AND 60)
);

-- No unique on (user_id, name): two people can share a nickname; the app warns instead.
CREATE INDEX workers_user_idx ON workers (user_id, archived_at);

-- Who worked on which part of the piece, on one assessment. A null worker is the
-- manager ("Me" in the picker). A null part means the worker was named for the piece
-- as a whole.
CREATE TABLE session_workers (
    id         bigserial PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    worker_id  uuid REFERENCES workers(id) ON DELETE CASCADE,
    part       text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT session_workers_part_known
        CHECK (part IS NULL OR part IN ('frame', 'joinery', 'upholstery', 'finishing', 'assembly', 'other'))
);

-- NULLS NOT DISTINCT so "the manager, no part" cannot be recorded twice. Postgres 15 and
-- later; the box runs 16.
CREATE UNIQUE INDEX session_workers_unique
    ON session_workers (session_id, worker_id, part) NULLS NOT DISTINCT;

CREATE INDEX session_workers_worker_idx ON session_workers (worker_id);

-- As V22, plus workers.name.
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

    -- Every device signed out. Otherwise a phone holding a live refresh token keeps
    -- working against an account that no longer has an owner.
    DELETE FROM refresh_tokens WHERE user_id = target;

    RETURN affected > 0;
END;
$$;

INSERT INTO schema_migrations (version) VALUES ('V23__workers')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
