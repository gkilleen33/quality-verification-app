-- Kagua server schema, version 7.
--
-- Supports the per-user daily assessment count, which runs on every attempt to start an
-- assessment. sessions_user_updated_idx is on (user_id, updated_at) and cannot serve a
-- created_at range, so the count would fall back to filtering every session the user has
-- ever had — fine today, and quietly worse for every month a keen tester keeps using it.

BEGIN;

CREATE INDEX IF NOT EXISTS sessions_user_created_idx ON sessions (user_id, created_at DESC);

INSERT INTO schema_migrations (version) VALUES ('V7__quota_index')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
