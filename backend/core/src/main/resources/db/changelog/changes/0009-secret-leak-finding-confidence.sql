--liquibase formatted sql

--changeset evals:0009-secret-leak-finding-confidence
-- Records each secret-leak finding's confidence on the finding, as ClassifierArming now writes it.
-- SecretLeakCaseSource and the finding page read it from the payload instead of recomputing it from
-- secret_leak_detection, whose rows age out under retention while the finding stays live. Computed the
-- way the page computed it before: any high-band detection of the facet's pattern at its call site
-- since onset.
UPDATE finding f
   SET payload = COALESCE(f.payload, '{}'::jsonb) || jsonb_build_object('confidence',
       CASE WHEN EXISTS (
           SELECT 1
             FROM secret_leak_detection d
             JOIN span s ON s.project_id = d.project_id
                        AND s.trace_id = d.subject_trace_id
                        AND s.id = d.subject_span_id
            WHERE d.project_id = f.project_id
              AND d.classifier_id = f.subject_id
              AND d.evidence ->> 'pattern' = f.payload ->> 'native_cause_key'
              AND s.call_site_id IS NOT DISTINCT FROM f.call_site_id
              AND s.started_at >= CAST(f.onset_at AS timestamptz)
              AND d.confidence = 'high')
       THEN 'high' ELSE 'low' END)
 WHERE f.classifier_key = 'secret_leak'
   AND f.payload ->> 'cause_kind' = 'armed_window';

--rollback UPDATE finding SET payload = payload - 'confidence' WHERE classifier_key = 'secret_leak';
