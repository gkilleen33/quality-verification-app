-- Kagua server schema, version 1.
--
-- Applied with psql as the kagua role. Plain SQL rather than Flyway: there is one
-- database on one box, and a migration tool would be a dependency to install and
-- keep current for no benefit at this size. The rule is that files here are
-- append-only — never edit a V-file that has been applied, add the next one.
--
-- The sessions/messages/attachments shape deliberately mirrors the phone's Room
-- schema, because the phone is where these rows are created. Ids are generated on
-- the device and accepted here as given, so a conversation started offline syncs
-- without a server round trip to allocate a key.

BEGIN;

CREATE TABLE IF NOT EXISTS schema_migrations (
    version    text PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

-- Location search is not built yet. The extension goes in now because the whole
-- reason for choosing a PostGIS-capable host was to avoid retrofitting it later.
CREATE EXTENSION IF NOT EXISTS postgis;

-- ---------------------------------------------------------------------------
-- Who is allowed to spend our Anthropic budget
-- ---------------------------------------------------------------------------

-- Invite codes, not phone numbers, for the pilot: no SMS provider, no per-message
-- cost, and every tester is somebody we know by name. Phone auth can be added
-- later without touching this table.
CREATE TABLE invite_codes (
    code       text PRIMARY KEY,
    label      text,                          -- who we handed it to
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz
);

CREATE TABLE users (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invite_code text REFERENCES invite_codes(code),
    label       text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- Set rather than deleting the row: their assessments stay readable, and the
    -- usage history stays attributable.
    disabled_at timestamptz
);

CREATE UNIQUE INDEX users_invite_code_key ON users (invite_code) WHERE invite_code IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Assessments
-- ---------------------------------------------------------------------------

CREATE TABLE sessions (
    id                  uuid PRIMARY KEY,          -- generated on the phone
    user_id             uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_type_id        text NOT NULL,             -- 'wooden-table', 'upholstered-chair', ...
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    preview_text        text NOT NULL DEFAULT '',
    verdict_level_id    text,                      -- sound | fair | serious_concerns
    verdict_language    text,                      -- language the verdict was written in
    -- The assessment this one was started from, for the comparison feature.
    -- SET NULL rather than CASCADE: deleting an earlier report must not delete the
    -- later one, and losing the link just means no comparison is offered.
    previous_session_id uuid REFERENCES sessions(id) ON DELETE SET NULL,
    intake_answers      text,                      -- encodeIntake() form, or null
    -- Which prompt produced this. The prompt files are versioned in git and change
    -- without an app release, so a stored verdict is uninterpretable a year from now
    -- without knowing which protocol was in force when it was written.
    prompt_sha          text,
    -- Where the piece was assessed. Nothing populates this yet; it is the hook the
    -- extension above exists for.
    location            geography(Point, 4326)
);

CREATE INDEX sessions_user_updated_idx ON sessions (user_id, updated_at DESC);
CREATE INDEX sessions_location_idx ON sessions USING gist (location);

CREATE TABLE messages (
    id         uuid PRIMARY KEY,                   -- generated on the phone
    session_id uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    role       text NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    text       text NOT NULL,
    ordinal    int  NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- The proxy writes both sides of every turn as it passes, so ordering is the
    -- server's own record rather than something the client can scramble.
    UNIQUE (session_id, ordinal)
);

CREATE INDEX messages_session_ordinal_idx ON messages (session_id, ordinal);

CREATE TABLE attachments (
    id          uuid PRIMARY KEY,
    message_id  uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    -- Content hash, not a filename. The app re-sends the whole conversation on every
    -- turn, so a naive write-through would store the same nine photos three or four
    -- times per assessment; storing by hash makes that idempotent.
    sha256      char(64) NOT NULL,
    byte_size   int,
    mime_type   text NOT NULL DEFAULT 'image/jpeg',
    -- Path on the EBS volume, relative to the photo root. Null is a valid state and
    -- means the bytes were never kept — see the retention decision, still open.
    stored_path text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX attachments_message_idx ON attachments (message_id);
CREATE INDEX attachments_sha_idx ON attachments (sha256);

-- ---------------------------------------------------------------------------
-- What each request cost
-- ---------------------------------------------------------------------------

-- One row per upstream call. This is what per-user quotas are enforced from, and
-- the only way to answer "why was the bill that size" after the fact. Written even
-- when the call fails, because failed calls can still be expensive.
CREATE TABLE usage_events (
    id                    bigserial PRIMARY KEY,
    user_id               uuid REFERENCES users(id) ON DELETE SET NULL,
    session_id            uuid REFERENCES sessions(id) ON DELETE SET NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    model                 text,
    input_tokens          int,
    output_tokens         int,
    cache_read_tokens     int,
    cache_creation_tokens int,
    http_status           int,
    latency_ms            int,
    error_kind            text
);

CREATE INDEX usage_user_created_idx ON usage_events (user_id, created_at DESC);

INSERT INTO schema_migrations (version) VALUES ('V1__init')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
