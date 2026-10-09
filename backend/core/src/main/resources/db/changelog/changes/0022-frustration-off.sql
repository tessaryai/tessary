--liquibase formatted sql

--changeset evals:0022-frustration-off
-- Frustration now scores each eligible user turn with a hosted decision model on the org's own
-- provider key, so it ships off and a person turns it on through the flow that asks for that key.
-- The catalog seeds new rows disabled; this turns off the rows that already exist.
--
-- It also empties frustration_detection. Every row there was written by the encoder scorer this
-- replaces, whose scores are on a different scale, and a leftover row would still read as the
-- conversation's flag: the sweep would skip that conversation and the rate test would count it.
--
-- On an open install both statements are no-ops: frustration was not available there, so no row was
-- enabled and the table is new. Only an install that ran the paid overlay has rows to change.
--
-- updated_at is a text column holding Instant.toString() values; whole-second UTC in that format is
-- what this writes.
--
-- The rollback reverses neither statement: which rows were enabled before, and the deleted encoder
-- rows, are not kept anywhere.
UPDATE classifier
   SET enabled = false,
       updated_at = to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
 WHERE classifier_key = 'frustration'
   AND built_in
   AND enabled;

DELETE FROM frustration_detection;

--rollback SELECT 1;
