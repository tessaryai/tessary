--liquibase formatted sql

--changeset evals:0021-rca-frustration-report
-- An RCA on a frustration finding answers a different question from one on a metric movement: not
-- "what changed" but "what did the agent do that frustrated these users". report_kind says which of
-- the two a report is, causes holds the ranked causes a frustration_causes report found (NULL on every
-- metric_movement report), and the verdict CHECK gains the two verdicts that kind can reach. Existing
-- reports take the default kind, which is what every one of them was. The verdict CHECK is dropped and
-- recreated under the same name, as 0019 does for eval_case, since Postgres cannot alter a CHECK's
-- expression. Rollback restores the five-verdict CHECK, which fails while any report holds one of the
-- two new verdicts; delete or rewrite those rows first.
ALTER TABLE rca_report ADD COLUMN report_kind text NOT NULL DEFAULT 'metric_movement';
ALTER TABLE rca_report ADD CONSTRAINT rca_report_report_kind_check
    CHECK (report_kind IN ('metric_movement', 'frustration_causes'));
ALTER TABLE rca_report ADD COLUMN causes jsonb;
ALTER TABLE rca_report DROP CONSTRAINT rca_report_verdict_check;
ALTER TABLE rca_report ADD CONSTRAINT rca_report_verdict_check
    CHECK (verdict IS NULL OR verdict = ANY (ARRAY['definition_change'::text, 'model_change'::text,
        'traffic_shift'::text, 'behavior_change'::text, 'inconclusive'::text, 'causes_identified'::text,
        'no_cause_found'::text]));

--rollback ALTER TABLE rca_report DROP CONSTRAINT rca_report_verdict_check;
--rollback ALTER TABLE rca_report ADD CONSTRAINT rca_report_verdict_check CHECK (verdict IS NULL OR verdict = ANY (ARRAY['definition_change'::text, 'model_change'::text, 'traffic_shift'::text, 'behavior_change'::text, 'inconclusive'::text]));
--rollback ALTER TABLE rca_report DROP COLUMN causes;
--rollback ALTER TABLE rca_report DROP CONSTRAINT rca_report_report_kind_check;
--rollback ALTER TABLE rca_report DROP COLUMN report_kind;
