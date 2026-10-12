-- Kagua server schema, version 20.
--
-- A fourth answer about a tool: "I don't know what this tool is."
--
-- WHY: the setup screen asked have it, can borrow, or do not have, and a maker who had
-- never heard of a marking gauge had to pick "do not have" — which records the tool as
-- absent and loses the more useful fact, that they do not know it. That is a measure of
-- what they have been taught, and it changes the coaching: a fix that names a tool they
-- do not know has to say what it is. So it is its own state, recorded and tracked like
-- the others; going from "unknown" to "owned" is the clearest learning there is.
--
-- The three CHECKs that list ownership states are replaced, not altered: Postgres cannot
-- change a CHECK in place. Same names, so nothing that refers to them moves.

BEGIN;

ALTER TABLE fundi_tools DROP CONSTRAINT fundi_tools_ownership_known;
ALTER TABLE fundi_tools ADD CONSTRAINT fundi_tools_ownership_known
    CHECK (ownership IN ('owned', 'borrowed', 'none', 'unknown'));

ALTER TABLE fundi_tool_changes DROP CONSTRAINT fundi_tool_changes_from_known;
ALTER TABLE fundi_tool_changes ADD CONSTRAINT fundi_tool_changes_from_known
    CHECK (from_ownership IS NULL
           OR from_ownership IN ('owned', 'borrowed', 'none', 'unknown'));

ALTER TABLE fundi_tool_changes DROP CONSTRAINT fundi_tool_changes_to_known;
ALTER TABLE fundi_tool_changes ADD CONSTRAINT fundi_tool_changes_to_known
    CHECK (to_ownership IN ('owned', 'borrowed', 'none', 'unknown'));

INSERT INTO schema_migrations (version) VALUES ('V20__tool_not_known')
    ON CONFLICT (version) DO NOTHING;

COMMIT;
