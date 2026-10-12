-- Kagua server schema, version 19.
--
-- An assessment's notes, written by the model once it has given its answer.
--
-- WHY THIS EXISTS: follow-up questions were slow and grew slower. Every turn re-sent the
-- whole conversation, every photograph included, so a question asked after the verdict
-- paid for eight or nine images it almost never needed. Once a verdict or a diagnosis has
-- been given, the server now asks the model to write down what it saw — photo by photo,
-- test by test, and why it found what it found — and later turns carry those notes and
-- the last reply in place of everything before them. The photographs stay one tool call
-- away (view_photos) for the question that does turn on a detail.
--
-- One row per reply it summarises, keyed by that assistant message. The conversation
-- itself is untouched: messages stay the record, and this is a cache of a reading of it.
-- A later verdict — a correction — gets its own row, and the newest one present in the
-- conversation is the one used.
--
-- Deletion follows the conversation: cascades from the message, which cascades from the
-- session, so anything that removes a turn removes its notes.

BEGIN;

CREATE TABLE conversation_digests (
    -- The assistant turn these notes run up to and including.
    message_id  uuid PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
    session_id  uuid NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    text        text NOT NULL,
    -- How many photographs existed when the notes were written. The notes number them,
    -- and a later turn's photo list must agree with those numbers.
    photo_count int  NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT conversation_digests_text_present CHECK (length(text) > 0),
    CONSTRAINT conversation_digests_photo_count_sane CHECK (photo_count >= 0)
);

CREATE INDEX conversation_digests_session_idx ON conversation_digests (session_id, created_at DESC);

-- What each upstream call was for. Compaction is a second call per assessment, and the
-- bill should say so rather than looking like customers taking an extra turn.
ALTER TABLE usage_events
    ADD COLUMN IF NOT EXISTS call_kind text NOT NULL DEFAULT 'turn';

ALTER TABLE usage_events
    ADD CONSTRAINT usage_events_call_kind_known CHECK (call_kind IN ('turn', 'digest'));

INSERT INTO schema_migrations (version) VALUES ('V19__conversation_digests')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
