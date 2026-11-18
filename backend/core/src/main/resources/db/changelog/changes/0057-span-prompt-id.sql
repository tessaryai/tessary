--liquibase formatted sql

--changeset evals:0057-span-prompt-id
-- The prompt row a linked span names, resolved at ingest from prompt_name, as another prompt tool does. Null when the
-- name matched no prompt of the project when the span arrived. No foreign key and no index: spans outlive prompts and
-- retention deletes spans by age, so a deleted prompt leaves its id behind, and prompt_name and prompt_version keep
-- the producer's text so the link still shows.
ALTER TABLE span ADD COLUMN prompt_id text;
--rollback ALTER TABLE span DROP COLUMN prompt_id;
