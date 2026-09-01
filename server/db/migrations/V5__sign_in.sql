-- Kagua server schema, version 5.
--
-- A credential a customer can present again, so the invite code goes back to being
-- what it should always have been: permission to create an account, once. Before
-- this, 60 days of not opening the app locked somebody out with no route back.
--
-- Phone number and password rather than SMS one-time codes: no provider, no
-- per-message cost, and a phone number is the identifier people in this market
-- actually have. The number is *not* verified, which is acceptable only because
-- creating an account still needs an invite code — nobody can register as somebody
-- else's number without one.

BEGIN;

ALTER TABLE users
    -- E.164, e.g. +256700123456. Stored as given after validation; normalisation is
    -- the client's job for now. libphonenumber would do this properly and is worth
    -- adding before anything wider than a pilot.
    ADD COLUMN phone              text,
    -- Argon2id, encoded with its own parameters so the cost can be raised later
    -- without invalidating existing hashes.
    ADD COLUMN password_hash      text,
    ADD COLUMN password_set_at    timestamptz,
    -- Brute-force state. On the user row rather than a separate table: the lock is
    -- read on every sign-in attempt, and a join to check it would be pure cost.
    ADD COLUMN failed_sign_ins    integer NOT NULL DEFAULT 0,
    ADD COLUMN locked_until       timestamptz;

-- Unique where present, so admin-created rows without a phone stay possible.
CREATE UNIQUE INDEX users_phone_key ON users (phone) WHERE phone IS NOT NULL;

-- Nullable at the database level even though the API requires both: an account
-- created by some other route later (an admin, a migration) should not be forced to
-- invent a password, and the API is the right place to insist.
ALTER TABLE users
    ADD CONSTRAINT users_credentials_are_paired
        CHECK ((phone IS NULL) = (password_hash IS NULL));

INSERT INTO schema_migrations (version) VALUES ('V5__sign_in')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
