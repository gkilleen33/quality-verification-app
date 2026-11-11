-- A deleted account's tester invite code is revoked (v0.4.0, D6).
--
-- An invite code is single use because of the unique index on users.invite_code (V1), not
-- because of anything on invite_codes. Deleting an account blanks users.invite_code, since
-- the code's label often names the person it was handed to, and that blanking frees the
-- index: until now the code of a deleted account would register a new account, with the
-- same tester flag and audience.
--
-- So anonymise_user now revokes the code first, while users.invite_code still names it.
-- register() already refuses a revoked code (AuthStore.REDEEMABLE_INVITE: revoked_at is
-- null), and answers it as it answers an unknown or used one. A date an admin set earlier
-- is kept.
--
-- Tester invite codes only. The study's enrolment codes (sign-up plan v3) are not on this
-- schema yet; they get their own table with a redeemed status.
--
-- Accounts deleted before this migration lost the link to their code when it was blanked,
-- so nothing here can find and revoke those codes. An admin can revoke one from the
-- portal's invites page by hand.

BEGIN;

-- As V25, plus the revocation of the account's invite code before the users statement.
CREATE OR REPLACE FUNCTION anonymise_user(target uuid)
RETURNS boolean
LANGUAGE plpgsql
AS $$
DECLARE
    affected integer;
BEGIN
    -- The code this account registered with. Revoked before the statement below blanks
    -- users.invite_code, which is the only link to it and the only thing keeping it single
    -- use. The unique index on that column means no other account holds the same code.
    UPDATE invite_codes SET
        revoked_at = now()
    WHERE revoked_at IS NULL
      AND code IN (
          SELECT u.invite_code FROM users u
           WHERE u.id = target
      );

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

    -- Each photo's camera metadata: GPS, serial numbers, owner and artist names.
    UPDATE attachments SET
        photo_meta = NULL
    WHERE photo_meta IS NOT NULL
      AND message_id IN (
          SELECT m.id FROM messages m JOIN sessions s ON s.id = m.session_id
           WHERE s.user_id = target
      );

    -- Every device signed out. Otherwise a phone holding a live refresh token keeps
    -- working against an account that no longer has an owner.
    DELETE FROM refresh_tokens WHERE user_id = target;

    RETURN affected > 0;
END;
$$;

INSERT INTO schema_migrations (version) VALUES ('V26__revoke_deleted_invite')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
