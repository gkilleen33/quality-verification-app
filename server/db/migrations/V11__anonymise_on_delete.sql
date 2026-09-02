-- Kagua server schema, version 11.
--
-- Account deletion anonymises rather than erases.
--
-- The reasoning, recorded here because it is a promise and not a preference: the pilot
-- exists to judge whether these assessments are any good, and that judgement is made
-- against the conversations and photographs. Deleting them on request destroys the record
-- the study is made of. Rather than promise full deletion now and retract it once that
-- becomes inconvenient, the app says plainly that data may be kept indefinitely and that
-- deleting an account removes the identifiers from it.
--
-- Reverting this is documented in docs/reverting-to-full-deletion.md.
-- purge_deleted_accounts() is deliberately left defined but no longer called, so the
-- revert is a one-line change to purge_expired() rather than rewriting a function.
--
-- WHAT THIS CANNOT DO, stated where somebody will read it before relying on it: the
-- photographs and the free text are themselves personal data. A picture shows a workshop's
-- signage and premises; a message may name a shop or a person. Clearing these columns
-- removes the profile, and that is honestly all it does — which is why the wording in the
-- app says we remove what we hold about them and asks them not to put personal details in
-- the conversation, rather than claiming the record is anonymous.

BEGIN;

-- Distinct from deleted_at: an account can be marked deleted by an admin without being
-- anonymised, and knowing which happened matters when somebody asks what we still hold.
ALTER TABLE users ADD COLUMN anonymised_at timestamptz;

-- A business must name itself, except once its name has been removed. Without this the
-- anonymisation of a business account would violate users_business_needs_name, and the
-- alternative — writing a placeholder into the column — puts a value there that could be
-- mistaken for a business actually called that.
ALTER TABLE users DROP CONSTRAINT users_business_needs_name;
ALTER TABLE users
    ADD CONSTRAINT users_business_needs_name
        CHECK (account_type IS DISTINCT FROM 'business'
               OR business_name IS NOT NULL
               OR anonymised_at IS NOT NULL);

/*
 * Strips everything identifying from an account, in place and irreversibly.
 *
 * No mapping table, by design. Keeping one anywhere would make this pseudonymisation
 * dressed up as anonymisation: the row could be re-linked, so the data would still be
 * personal data and every right attached to it would still attach.
 *
 * The row survives because s.user_id points at it, and that id — a random uuid that was
 * never derived from anything about the person — becomes the anonymous identifier the
 * research uses. Nothing needs to be re-keyed.
 *
 * phone and password_hash go together: users_phone_needs_password requires it, and either
 * one alone would leave an account that can still be signed into or still be found by
 * number.
 */
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

    -- Every device signed out. Otherwise a phone holding a live refresh token keeps
    -- working against an account that no longer has an owner.
    DELETE FROM refresh_tokens WHERE user_id = target;

    RETURN affected > 0;
END;
$$;

-- Accounts are no longer purged, so the nightly job stops calling it. The function stays
-- defined: see the revert doc.
CREATE OR REPLACE FUNCTION purge_expired()
RETURNS text
LANGUAGE sql
AS $$
    SELECT 'sessions=' || purge_deleted_sessions('7 days'::interval)
        || ' tokens='  || purge_expired_tokens('30 days'::interval)
        || ' devices=' || purge_expired_trusted_devices();
$$;

-- Anything already sitting in the old 30-day window is anonymised now rather than being
-- left to a purge that no longer runs. Without this those rows would keep their phone
-- numbers for ever, which is the opposite of what their owners asked for.
SELECT anonymise_user(id) FROM users WHERE deleted_at IS NOT NULL AND anonymised_at IS NULL;

CREATE INDEX users_anonymised_idx ON users (anonymised_at) WHERE anonymised_at IS NOT NULL;

INSERT INTO schema_migrations (version) VALUES ('V11__anonymise_on_delete')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
