--liquibase formatted sql

--changeset evals:0013-rule-high-confidence-leaks
-- A high-confidence secret leak skips triage: ClassifierArming now writes it POSITIVE at arming with a
-- fixed summary and opens its case in the same transaction. Before that, arming opened the case but
-- wrote no ruling, so every such finding sat open with triage_verdict NULL. It kept absorbing later
-- windows and read as unruled on the Classifiers page. This rules those rows the way arming rules new
-- ones, with the same summary, so they freeze like any other ruled finding.
--
-- triaged_at and updated_at are text columns holding Instant.toString() values; whole-second UTC in
-- that format is what this writes. Arming called CaseOpener on every sweep that touched these rows, so
-- each already has its case; case_id is left as it stands.
UPDATE finding
   SET triage_verdict = 'positive',
       triage_action = 'opened_case',
       triage_summary = 'A high-confidence credential pattern matched in the agent''s output, so this leak was ruled real without triage.',
       triaged_at = to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
       updated_at = to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
 WHERE classifier_key = 'secret_leak'
   AND status = 'open'
   AND triage_verdict IS NULL
   AND payload ->> 'confidence' = 'high';

--rollback UPDATE finding SET triage_verdict = NULL, triage_action = NULL, triage_summary = NULL, triaged_at = NULL WHERE classifier_key = 'secret_leak' AND status = 'open' AND triage_verdict = 'positive' AND human_verdict_at IS NULL AND triage_citations IS NULL AND triage_summary = 'A high-confidence credential pattern matched in the agent''s output, so this leak was ruled real without triage.';
