--liquibase formatted sql

--changeset evals:0011-finding-open-closed
-- Findings are open or closed now [decision 1]: a finding used to carry six status words
-- (open/graduated/allowlisted/blocked/resolved/absorbed) with the ruling itself living in a SEPARATE
-- triage_verdict column that never touched status, so "closed" was a fact `triage_action` alone
-- recorded and the row sat in ux_finding_live forever. Now a ruling freezes the row: a POSITIVE
-- verdict keeps the finding open for as long as the case it opened or joined is unresolved, a
-- NEGATIVE verdict closes it outright, and a ruled row leaves ux_finding_live by construction, so the
-- next firing of the same cause opens a fresh row rather than being silently folded into a settled one.
--
-- Row mapping, evaluated before the CHECK swap: `blocked` (a person's "Real deviation") becomes
-- positive with a fixed human summary, unconditionally overriding any earlier machine verdict, since a
-- person outranks a machine on the same row; `allowlisted`/`absorbed` with no verdict yet becomes
-- negative with a fixed human summary. A row that has never been triaged (still `open`, no verdict)
-- is left untouched -- it already fits the new model as an unruled open finding. Every ruled row, and
-- every row in one of the four other legacy statuses, closes UNLESS it carries a positive verdict and
-- its (freshly backfilled) case is still unresolved, in which case it stays open. finding.case_id is
-- backfilled from eval_case.finding_id first, preferring a still-live case over a resolved one when
-- more than one case ever pointed at the same finding, so this decision can be made.
--
-- eval_case.finding_id is then dropped: readers take a case's findings from finding.case_id (the
-- reverse pointer) instead of a forward one the case row itself carried, which is what let a case
-- disagree with the finding it just refreshed. eval_case.locked_at is new [decision 1c]: pressing
-- RCA on a case locks it, so a cause whose case is mid-analysis opens a NEW case on its next positive
-- rather than joining one an agent is already reading, and ux_eval_case_live is rewritten to allow at
-- most one unresolved, unlocked case per key alongside any number of locked ones.
--
-- Lossy rollback, accepted: eval_case.finding_id is restored from the newest finding of each case
-- (the historical "which finding did this case look like at time T" trail is not recoverable), a case
-- whose backfill tiebreak dropped it reads as finding-less, and every row this migration closed stays
-- closed -- there is no way to tell a genuinely-resolved case's finding apart from one this migration
-- closed only because it was ruled before the case model existed.
ALTER TABLE finding ADD COLUMN case_id text;

UPDATE finding
   SET case_id = backfill.case_id
  FROM (
        SELECT DISTINCT ON (finding_id) finding_id, id AS case_id
          FROM eval_case
         WHERE finding_id IS NOT NULL
         ORDER BY finding_id, (state <> 'resolved') DESC, opened_at DESC
       ) AS backfill
 WHERE finding.id = backfill.finding_id;

UPDATE finding
   SET triage_verdict = 'positive',
       triage_action = 'opened_case',
       triage_summary = 'A person ruled this a real deviation.',
       triaged_at = COALESCE(triaged_at, human_verdict_at, updated_at)
 WHERE status = 'blocked';

UPDATE finding
   SET triage_verdict = 'negative',
       triage_action = 'closed',
       triage_summary = 'A person ruled this legitimate.',
       triaged_at = COALESCE(triaged_at, human_verdict_at, updated_at)
 WHERE status IN ('allowlisted', 'absorbed')
   AND triage_verdict IS NULL;

-- The CHECK is dropped before any row is set to a word it has never allowed: `closed` is not in
-- the six-word legacy vocabulary at all, so the status-setting UPDATEs below would fail against
-- the still-old constraint on a populated table (an empty one never exercises it, which is why
-- this ordering matters and scripts/check-migrations-populated.sh is what catches getting it
-- backwards). The narrowed CHECK goes back on once every row already reads open or closed.
ALTER TABLE finding DROP CONSTRAINT finding_status_check;

-- EXISTS, not `case_id IN (...)`: a caseless row's `case_id` is NULL, and `NULL IN (...)` is NULL
-- rather than false, which would make the whole WHERE clause NULL for it -- neither true nor
-- false, so the row would match NEITHER this UPDATE nor the fallback below, and (for a legacy
-- status) silently keep whatever literal word it already held.
UPDATE finding
   SET status = 'closed'
 WHERE NOT (status = 'open' AND triage_verdict IS NULL)
   AND NOT (
         triage_verdict = 'positive'
         AND EXISTS (SELECT 1 FROM eval_case WHERE id = finding.case_id AND state <> 'resolved')
       );

-- Whatever survived the close above without ever being normalized to the literal 'open' text --
-- a blocked-turned-positive row backing a still-live case -- reads as open under the new vocabulary.
UPDATE finding SET status = 'open' WHERE status NOT IN ('open', 'closed');

ALTER TABLE finding ADD CONSTRAINT finding_status_check
    CHECK ((status = ANY (ARRAY['open'::text, 'closed'::text])));

DROP INDEX ux_finding_live;
CREATE UNIQUE INDEX ux_finding_live ON public.finding USING btree (project_id, classifier_key, cause_key)
    WHERE ((status = 'open'::text) AND (triage_verdict IS NULL));

DROP INDEX ix_finding_triaged;
ALTER TABLE finding DROP COLUMN recurrences_since_verdict;

CREATE INDEX ix_finding_case ON public.finding USING btree (project_id, case_id) WHERE (case_id IS NOT NULL);
ALTER TABLE finding ADD CONSTRAINT finding_case_id_fkey
    FOREIGN KEY (case_id) REFERENCES public.eval_case(id) ON DELETE SET NULL;

DROP INDEX ix_eval_case_finding_live;
ALTER TABLE eval_case DROP CONSTRAINT ck_eval_case_finding_forward;
ALTER TABLE eval_case DROP CONSTRAINT eval_case_finding_id_fkey;
ALTER TABLE eval_case DROP COLUMN finding_id;

ALTER TABLE eval_case ADD COLUMN locked_at text;

DROP INDEX ux_eval_case_live;
CREATE UNIQUE INDEX ux_eval_case_live ON public.eval_case
    USING btree (project_id, detector, subject_kind, subject_id, metric)
    WHERE ((state <> 'resolved'::text) AND (locked_at IS NULL));

ALTER TABLE eval_case_event DROP CONSTRAINT eval_case_event_kind_check;
ALTER TABLE eval_case_event ADD CONSTRAINT eval_case_event_kind_check
    CHECK ((kind = ANY (ARRAY['opened'::text, 'reopened'::text, 'recurred'::text, 'escalated'::text,
        'rca_requested'::text, 'rca_completed'::text, 'recovered'::text, 'resolved'::text, 'muted'::text,
        'unmuted'::text, 'absorbed'::text])));

COMMENT ON COLUMN public.finding.status IS 'open or closed, and nothing else. A ruling freezes the row: POSITIVE keeps it open for as long as the case it opened or joined stays unresolved, NEGATIVE closes it outright, and a ruled row leaves ux_finding_live so the next firing of the same cause opens a fresh row.';
COMMENT ON COLUMN public.finding.triage_verdict IS 'The Layer-2 ruling on the CLAIM: positive (it holds -- the finding stays open and backs a case) or negative (it does not -- the finding closes). NULL means no run has completed -- a run that did not happen leaves this NULL and lets its job retry, so a NULL is never a ruling.';
COMMENT ON COLUMN public.finding.case_id IS 'The case this finding opened or joined, or NULL while it has none -- set once a ruling (machine or human) is positive, cleared if the case is deleted. The reverse of the old eval_case.finding_id: a case reader takes its findings from here rather than carrying a forward pointer that only ever named the most recently refreshed one.';
COMMENT ON COLUMN public.eval_case.locked_at IS 'When a person pressed Run RCA on this case, or NULL if nobody has. Locks the case against new findings joining it -- the cause''s next positive opens a new case instead -- and against a second unlocked case existing for the same key at once (ux_eval_case_live).';

--rollback ALTER TABLE eval_case DROP COLUMN locked_at;
--rollback DROP INDEX ux_eval_case_live;
--rollback CREATE UNIQUE INDEX ux_eval_case_live ON public.eval_case USING btree (project_id, detector, subject_kind, subject_id, metric) WHERE (state <> 'resolved'::text);
--rollback ALTER TABLE eval_case_event DROP CONSTRAINT eval_case_event_kind_check;
--rollback ALTER TABLE eval_case_event ADD CONSTRAINT eval_case_event_kind_check CHECK ((kind = ANY (ARRAY['opened'::text, 'reopened'::text, 'escalated'::text, 'rca_requested'::text, 'rca_completed'::text, 'recovered'::text, 'resolved'::text, 'muted'::text, 'unmuted'::text, 'absorbed'::text])));
--rollback ALTER TABLE eval_case ADD COLUMN finding_id text;
--rollback UPDATE eval_case c SET finding_id = newest.id FROM (SELECT DISTINCT ON (case_id) case_id, id FROM finding WHERE case_id IS NOT NULL ORDER BY case_id, created_at DESC) AS newest WHERE c.id = newest.case_id;
--rollback ALTER TABLE eval_case ADD CONSTRAINT eval_case_finding_id_fkey FOREIGN KEY (finding_id) REFERENCES public.finding(id) ON DELETE RESTRICT;
--rollback ALTER TABLE eval_case ADD CONSTRAINT ck_eval_case_finding_forward CHECK (((finding_id IS NOT NULL) OR (state = 'resolved'::text)));
--rollback CREATE INDEX ix_eval_case_finding_live ON public.eval_case USING btree (finding_id) WHERE (state <> 'resolved'::text);
--rollback ALTER TABLE finding DROP CONSTRAINT finding_case_id_fkey;
--rollback DROP INDEX ix_finding_case;
--rollback ALTER TABLE finding ADD COLUMN recurrences_since_verdict bigint DEFAULT 0 NOT NULL;
--rollback CREATE INDEX ix_finding_triaged ON public.finding USING btree (project_id, triage_verdict) WHERE ((status = 'open'::text) AND (triage_verdict IS NOT NULL));
--rollback DROP INDEX ux_finding_live;
--rollback CREATE UNIQUE INDEX ux_finding_live ON public.finding USING btree (project_id, classifier_key, cause_key) WHERE (status = ANY (ARRAY['open'::text, 'blocked'::text]));
--rollback ALTER TABLE finding DROP CONSTRAINT finding_status_check;
--rollback ALTER TABLE finding ADD CONSTRAINT finding_status_check CHECK ((status = ANY (ARRAY['open'::text, 'graduated'::text, 'allowlisted'::text, 'blocked'::text, 'resolved'::text, 'absorbed'::text])));
--rollback UPDATE finding SET status = 'open' WHERE status = 'closed' AND triage_verdict = 'positive';
--rollback ALTER TABLE finding DROP COLUMN case_id;
--rollback COMMENT ON COLUMN public.finding.status IS 'One of open, graduated, allowlisted, blocked, resolved, absorbed.';
--rollback COMMENT ON COLUMN public.finding.triage_verdict IS 'The Layer-2 ruling on the CLAIM: positive (it holds) or negative (it does not). NULL means no run has completed -- a run that did not happen leaves this NULL and lets its job retry, so a NULL is never a ruling.';
