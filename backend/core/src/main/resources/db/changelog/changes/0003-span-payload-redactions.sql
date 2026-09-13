--liquibase formatted sql

--changeset evals:0003-span-payload-redactions
-- The credentials redaction removed from a span on the way in: which gitleaks rule matched, in which
-- field, and whether that rule anchors on a literal the provider stamps into the credential. One JSON
-- array per payload, [{"rule": "aws-access-token", "field": "output", "anchored": true}], and never any
-- part of the credential itself.
--
-- Redaction rewrites the credential to a token before anything is stored, so this is the only record of
-- what the token replaced. The Secret Leak classifier reads it to name a leak and judge its confidence;
-- without it, all it had to go on was [REDACTED_SECRET], which says the same thing for an AWS key and for
-- the word after "password=".
--
-- On span_payload rather than span because it is a fact about the payload's content, written in the same
-- statement and under the same event_ts guard as the text it describes, so a newer version of the span
-- replaces both together. Nullable: a payload redaction found nothing in, and every row written before
-- this column, carries none.
ALTER TABLE span_payload ADD COLUMN redactions jsonb;

--rollback ALTER TABLE span_payload DROP COLUMN redactions;
