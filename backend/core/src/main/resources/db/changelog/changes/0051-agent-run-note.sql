--liquibase formatted sql

--changeset evals:0051-agent-run-note
-- What the agent last said in its own words between tool calls, so the live line has something to show while no
-- tool call runs: its newest whole sentence, at most 160 characters with markdown and token-shaped words removed, or
-- a fixed phrase while it writes its answer. note_ms is the offset from started_at when it was written. NULL until
-- the agent says something.
ALTER TABLE agent_run ADD COLUMN note text, ADD COLUMN note_ms bigint;
--rollback ALTER TABLE agent_run DROP COLUMN note_ms, DROP COLUMN note;
