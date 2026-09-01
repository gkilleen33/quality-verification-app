-- Kagua server schema, version 6.
--
-- Photos need an explicit order.
--
-- The customer takes them in plan order and the protocols refer to them by position —
-- "the front view", "where the stretcher enters the leg". Without an ordinal the only
-- stable ordering available is the primary key, which is a random UUID: deterministic
-- enough for prompt caching, but scrambled relative to what the customer actually did,
-- so the assistant would read the wrong photo under each instruction and nothing would
-- look wrong from here.

BEGIN;

ALTER TABLE attachments ADD COLUMN ordinal integer NOT NULL DEFAULT 0;

-- Ordering key for building the request. Included rather than just indexed on ordinal,
-- because the tie-break on id is part of what makes the sequence reproducible.
CREATE INDEX attachments_message_order_idx ON attachments (message_id, ordinal, id);

INSERT INTO schema_migrations (version) VALUES ('V6__attachment_order')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
