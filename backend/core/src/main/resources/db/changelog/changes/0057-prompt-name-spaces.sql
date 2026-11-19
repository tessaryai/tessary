--liquibase formatted sql

--changeset evals:0057-prompt-name-spaces
-- A prompt name may hold whitespace inside it, as in another prompt tool, so "Support Agent" is a valid name. The Java
-- rule trims the edges before a write, and the CHECK still refuses whitespace at either edge as the backstop. This
-- widens the constraint, so every stored name already passes it.
ALTER TABLE prompt DROP CONSTRAINT ck_prompt_name;
ALTER TABLE prompt ADD CONSTRAINT ck_prompt_name CHECK (
    length(name) BETWEEN 1 AND 255
    AND name !~ '[|]' AND name !~ '^[[:space:]]' AND name !~ '[[:space:]]$'
    AND name !~ '^/' AND name !~ '/$' AND name !~ '//');
--rollback ALTER TABLE prompt DROP CONSTRAINT ck_prompt_name; ALTER TABLE prompt ADD CONSTRAINT ck_prompt_name CHECK (length(name) BETWEEN 1 AND 255 AND name !~ '[|[:space:]]' AND name !~ '^/' AND name !~ '/$' AND name !~ '//');
