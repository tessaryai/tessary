--liquibase formatted sql

--changeset evals:0010-triage-verdict-binary
-- Verdicts are positive and negative only now [decision 4]: `unclear` added a third outcome that
-- closed the finding exactly like `negative` while asserting nothing, and the only thing it bought
-- was a way for an agent that never read the evidence to still rule. Existing `unclear` rows become
-- `negative` -- the action was already `closed`, so nothing about what the finding shows changes,
-- only the word on it -- and the check constraint is narrowed to match.
UPDATE finding SET triage_verdict = 'negative' WHERE triage_verdict = 'unclear';

ALTER TABLE finding DROP CONSTRAINT finding_triage_verdict_check;
ALTER TABLE finding ADD CONSTRAINT finding_triage_verdict_check
    CHECK (((triage_verdict IS NULL) OR (triage_verdict = ANY (ARRAY['positive'::text, 'negative'::text]))));

COMMENT ON COLUMN public.finding.triage_verdict IS 'The Layer-2 ruling on the CLAIM: positive (it holds) or negative (it does not). NULL means no run has completed -- a run that did not happen leaves this NULL and lets its job retry, so a NULL is never a ruling.';
COMMENT ON COLUMN public.finding.triage_action IS 'What the ruling did, fixed by the verdict: positive -> opened_case, negative -> closed. Written in the same statement as the verdict (finding_triage_paired_check) because a half-observed row would read as a ruling with no consequence.';

--rollback ALTER TABLE finding DROP CONSTRAINT finding_triage_verdict_check;
--rollback ALTER TABLE finding ADD CONSTRAINT finding_triage_verdict_check CHECK (((triage_verdict IS NULL) OR (triage_verdict = ANY (ARRAY['positive'::text, 'negative'::text, 'unclear'::text]))));
--rollback COMMENT ON COLUMN public.finding.triage_verdict IS 'The Layer-2 ruling on the CLAIM: sound (true, sampled, evidenced), artifact (the detector fired on something that is not there), unclear (the evidence does not settle it). NULL means no run has completed -- a run that did not happen leaves this NULL and lets its job retry, so a NULL is never a ruling.';
--rollback COMMENT ON COLUMN public.finding.triage_action IS 'What the ruling did, fixed by the verdict: sound -> opened_case, artifact -> closed, unclear -> closed. Written in the same statement as the verdict (finding_triage_paired_check) because a half-observed row would read as a ruling with no consequence.';
