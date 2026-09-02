-- Kagua server schema, version 12.
--
-- Keys for the read-only data API.
--
-- The API exposes everything, identifiers and photographs included, because the research
-- needs to join assessments to who did them and to look at the pictures. That makes a key
-- here a higher-value credential than an admin password: an admin has to sign in with a
-- second factor and every page view is audited, whereas a key is a single string that reads
-- the whole corpus. So it is treated like one — hashed at rest, shown once, revocable,
-- audited on every request, and rate limited in nginx.

BEGIN;

CREATE TABLE api_keys (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- What it is for, so a key can be revoked without guessing which one it is.
    label        text NOT NULL,
    -- SHA-256 of the key, never the key. 32 bytes of CSPRNG output has no dictionary to
    -- attack, so a slow hash would buy nothing and cost a hash on every request — the same
    -- reasoning as refresh_tokens and admin_trusted_devices.
    key_hash     char(64) NOT NULL UNIQUE,
    -- The first few characters, kept in clear so a key can be recognised in a list or in a
    -- log line without being able to reconstruct it.
    key_prefix   text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid REFERENCES admins(id) ON DELETE SET NULL,
    last_used_at timestamptz,
    revoked_at   timestamptz
);

CREATE INDEX api_keys_active_idx ON api_keys (key_hash) WHERE revoked_at IS NULL;

INSERT INTO schema_migrations (version) VALUES ('V12__api_keys')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
