-- Which build of the app started each assessment.
--
-- From v0.4.0 both apps send X-App-Version (their versionCode) with every request, and the
-- server writes it here once, when the session is created, beside prompt_sha. Together the
-- two say what produced an assessment: the prompt the server assembled and the app that
-- took the photographs and asked the questions. A photo-processing change in one release
-- is invisible in the data without it.
--
-- Nullable, and null for every existing row: an app older than the header sends nothing,
-- and a v0.3.0 phone's assessment is recorded as "build not known" rather than guessed.
-- Not personal data, so anonymise_user is unchanged.

BEGIN;

ALTER TABLE sessions
    ADD COLUMN IF NOT EXISTS app_version_code integer;

ALTER TABLE sessions
    ADD CONSTRAINT sessions_app_version_code_positive
        CHECK (app_version_code IS NULL OR app_version_code > 0);

INSERT INTO schema_migrations (version) VALUES ('V24__session_app_version')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
