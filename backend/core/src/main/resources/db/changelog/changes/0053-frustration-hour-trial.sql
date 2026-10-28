--liquibase formatted sql

--changeset evals:0053-frustration-hour-trial
-- A frustration evidence row is now a turn that also names its session, so the clearer, the retention pin and
-- RCA's session citations keep working with no session rows. The grain check gains that shape; the three old
-- shapes stay.
ALTER TABLE finding_evidence DROP CONSTRAINT finding_evidence_grain_check;
ALTER TABLE finding_evidence ADD CONSTRAINT finding_evidence_grain_check CHECK (
    (session_id IS NOT NULL AND trace_id IS NULL AND span_id IS NULL)
    OR (session_id IS NULL AND trace_id IS NOT NULL AND span_id IS NULL)
    OR (session_id IS NULL AND trace_id IS NOT NULL AND span_id IS NOT NULL)
    OR (session_id IS NOT NULL AND trace_id IS NOT NULL AND span_id IS NULL));

-- The frustration trial is now a conversation in one UTC hour (devdocs/concepts/frustration.md). A reference,
-- watermark, accumulator and learned_through built over conversations bucketed at their first scored turn mean
-- nothing under it, so they are cleared as 0051 cleared them, and learn_before is set again at the open finding's
-- onset so the rise is not learned as normal. A cleared reference is what lets the first sweep's retune keep
-- learn_before and reset_at: a row with no reference only takes the new epoch. reset_* and pending_pin_* are kept.
UPDATE frustration_state s
   SET baseline_calls = NULL, baseline_failures = NULL, watermark_bucket = NULL, learned_through = NULL,
       s_up = 0, s_down = 0, onset_up_at = NULL, onset_down_at = NULL,
       calls_since_onset_up = 0, calls_since_onset_down = 0,
       learn_before = (SELECT f.onset_at FROM finding f
                        WHERE f.project_id = s.project_id AND f.classifier_key = 'frustration'
                          AND f.call_site_id = s.call_site_id AND f.status = 'open'
                        ORDER BY CAST(f.onset_at AS timestamptz) LIMIT 1);

-- Open frustration findings hold session rows nothing writes any more, and their witness trace rows carry no
-- session. Converted in place: session rows go, each witness trace row takes its session from the detection that
-- flagged it and is mirrored as a member, and the counts are recomputed. The next sweep adds the calm member turns;
-- a sample project, which never sweeps, keeps its witnesses and has no comparison side. Closed findings keep the
-- rows they were closed with. None of this is reversible.
DELETE FROM finding_evidence e
 USING finding f
 WHERE f.id = e.finding_id AND f.project_id = e.project_id
   AND f.classifier_key = 'frustration' AND f.status = 'open'
   AND f.payload ->> 'cause_kind' = 'frustration_rate'
   AND e.trace_id IS NULL;
UPDATE finding_evidence e
   SET session_id = d.subject_session_id
  FROM finding f, frustration_detection d
 WHERE f.id = e.finding_id AND f.project_id = e.project_id
   AND f.classifier_key = 'frustration' AND f.status = 'open'
   AND f.payload ->> 'cause_kind' = 'frustration_rate'
   AND e.role = 'witness' AND e.trace_id IS NOT NULL AND e.span_id IS NULL AND e.session_id IS NULL
   AND d.project_id = e.project_id AND d.classifier_id = f.subject_id AND d.subject_trace_id = e.trace_id;
INSERT INTO finding_evidence (id, project_id, finding_id, session_id, trace_id, span_id, role, rank, created_at)
SELECT gen_random_uuid()::text, e.project_id, e.finding_id, e.session_id, e.trace_id, NULL, 'member',
       ROW_NUMBER() OVER (PARTITION BY e.finding_id ORDER BY e.rank) - 1, e.created_at
  FROM finding_evidence e
  JOIN finding f ON f.id = e.finding_id AND f.project_id = e.project_id
 WHERE f.classifier_key = 'frustration' AND f.status = 'open'
   AND f.payload ->> 'cause_kind' = 'frustration_rate'
   AND e.role = 'witness' AND e.trace_id IS NOT NULL AND e.span_id IS NULL
ON CONFLICT DO NOTHING;
UPDATE finding f
   SET evidence_counts = (SELECT COALESCE(jsonb_object_agg(c.role, c.n), '{}'::jsonb)
                            FROM (SELECT e.role, COUNT(*) AS n FROM finding_evidence e
                                   WHERE e.finding_id = f.id AND e.project_id = f.project_id
                                   GROUP BY e.role) c)
 WHERE f.classifier_key = 'frustration' AND f.status = 'open'
   AND f.payload ->> 'cause_kind' = 'frustration_rate';
--rollback ALTER TABLE finding_evidence DROP CONSTRAINT finding_evidence_grain_check;
--rollback ALTER TABLE finding_evidence ADD CONSTRAINT finding_evidence_grain_check CHECK ((session_id IS NOT NULL AND trace_id IS NULL AND span_id IS NULL) OR (session_id IS NULL AND trace_id IS NOT NULL AND span_id IS NULL) OR (session_id IS NULL AND trace_id IS NOT NULL AND span_id IS NOT NULL));
