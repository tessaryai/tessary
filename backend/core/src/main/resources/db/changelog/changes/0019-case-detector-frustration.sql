--liquibase formatted sql

--changeset evals:0019-case-detector-frustration
-- Widens eval_case_detector_check for FrustrationCaseSource: a call site whose share of frustrated
-- conversations rose above its learned rate opens a case under detector 'frustration'. Dropped and
-- recreated under the same name, as 0007 did, since Postgres cannot alter a CHECK's expression.
ALTER TABLE eval_case DROP CONSTRAINT eval_case_detector_check;
ALTER TABLE eval_case ADD CONSTRAINT eval_case_detector_check
    CHECK (detector = ANY (ARRAY['behavior_drift'::text, 'classifier'::text, 'metric_drift'::text,
        'tool_error'::text, 'sop_conformance'::text, 'secret_leak'::text, 'malformed_output'::text,
        'frustration'::text]));

--rollback ALTER TABLE eval_case DROP CONSTRAINT eval_case_detector_check;
--rollback ALTER TABLE eval_case ADD CONSTRAINT eval_case_detector_check CHECK (detector = ANY (ARRAY['behavior_drift'::text, 'classifier'::text, 'metric_drift'::text, 'tool_error'::text, 'sop_conformance'::text, 'secret_leak'::text, 'malformed_output'::text]));
