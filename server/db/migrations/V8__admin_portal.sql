-- Kagua server schema, version 8.
--
-- The admin portal: accounts for 2-3 staff, and a record of what they looked at.
--
-- Deliberately separate from `users`. An admin is not a customer with a flag set: the
-- credentials are different (password + TOTP, no invite code), the lockout policy is
-- different, and a bug that let one become the other would be the worst bug in the system.

BEGIN;

CREATE TABLE admins (
    id                uuid PRIMARY KEY,
    email             text NOT NULL,
    name              text NOT NULL,
    password_hash     text NOT NULL,
    -- Null until the admin has scanned the QR code. An account cannot sign in until it is
    -- set, so 2FA cannot be skipped by simply never finishing enrolment.
    totp_secret       text,
    totp_confirmed_at timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    -- Who let them in. Null once that admin is deleted, which is why the audit log keeps
    -- its own copy of the email.
    created_by        uuid REFERENCES admins(id) ON DELETE SET NULL,
    disabled_at       timestamptz,
    last_sign_in_at   timestamptz,
    failed_sign_ins   int NOT NULL DEFAULT 0,
    locked_until      timestamptz
);

-- Case-insensitive: somebody typing Grady@... at the login form is the same person, and
-- letting two rows differ only by case would be an account-takeover route rather than a
-- cosmetic problem.
CREATE UNIQUE INDEX admins_email_idx ON admins (lower(email));

-- Who read whose conversation.
--
-- Required rather than nice to have. This portal can open every customer's photographs;
-- without a trail, misuse is undetectable, and "we would have noticed" is not a control.
CREATE TABLE admin_audit (
    id          bigserial PRIMARY KEY,
    admin_id    uuid REFERENCES admins(id) ON DELETE SET NULL,
    -- Denormalised on purpose. The point of an audit log is that it outlives the account
    -- it describes; a SET NULL that erased who did something would defeat it.
    admin_email text NOT NULL,
    action      text NOT NULL,
    -- What was acted on: a session id, a user id, an export's filter.
    target      text,
    detail      text,
    ip          text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX admin_audit_created_idx ON admin_audit (created_at DESC);
CREATE INDEX admin_audit_admin_idx ON admin_audit (admin_id, created_at DESC);
-- Answering "who has looked at this customer's assessment" without scanning the table.
CREATE INDEX admin_audit_target_idx ON admin_audit (target, created_at DESC);

INSERT INTO schema_migrations (version) VALUES ('V8__admin_portal')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
