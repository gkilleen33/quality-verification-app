-- Kagua server schema, version 18.
--
-- Which turns the app wrote on the user's behalf.
--
-- Fundi Bora's intake turn carries the maker's purpose, workshop and every tool marked
-- owned, borrowed or none; a plan submission carries a shot-by-shot list of what was
-- photographed and skipped. The model needs all of it. Shown to the maker word for word it
-- reads as the app printing its own prompt, which is what the first build did and what was
-- reported. The phone now marks those turns and shows them as a caption and thumbnails.
--
-- The mark has to live here too, because the phone's copy does not survive on its own:
--   * a reinstall rebuilds history from the server, and
--   * sync rewrites a conversation whenever the server's copy is newer — which happens in
--     ordinary use, not just after a reinstall, because a phone clock a few minutes slow
--     makes every server timestamp look newer.
-- Either way the phone would get the turns back without the mark, and the prompt text
-- would reappear in the conversation.
--
-- Defaulted false and not null: every turn that exists today was typed, or was Kagua's
-- intake, whose short sentences read fine and were never marked.

BEGIN;

ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS composed boolean NOT NULL DEFAULT false;

INSERT INTO schema_migrations (version) VALUES ('V18__message_composed')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
