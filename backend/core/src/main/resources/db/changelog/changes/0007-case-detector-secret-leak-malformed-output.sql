--liquibase formatted sql

--changeset evals:0007-case-detector-secret-leak-malformed-output
-- Widens eval_case_detector_check for the two new case sources: SecretLeakCaseSource and
-- MalformedOutputCaseSource. Postgres has no ALTER CONSTRAINT for a CHECK's expression, so the old
-- one is dropped and the widened one recreated under the same name.
ALTER TABLE eval_case DROP CONSTRAINT eval_case_detector_check;
ALTER TABLE eval_case ADD CONSTRAINT eval_case_detector_check
    CHECK (detector = ANY (ARRAY['behavior_drift'::text, 'classifier'::text, 'metric_drift'::text,
        'tool_error'::text, 'sop_conformance'::text, 'secret_leak'::text, 'malformed_output'::text]));

--rollback ALTER TABLE eval_case DROP CONSTRAINT eval_case_detector_check;
--rollback ALTER TABLE eval_case ADD CONSTRAINT eval_case_detector_check CHECK (detector = ANY (ARRAY['behavior_drift'::text, 'classifier'::text, 'metric_drift'::text, 'tool_error'::text, 'sop_conformance'::text]));
