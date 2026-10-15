-- Populated-database fixture, loaded by scripts/check-migrations-populated.sh between the baseline and
-- the migrations under test, so CHECKs and UPDATEs run against rows. Holds a row for each persisted
-- vocabulary value; a new rename needs an assertion there and, if uncovered, a row here. Ids are `*_fix`
-- and `prj_fix`/`cls_fix`/`org_fix`/`trc_fix`/`ses_fix` anchor rows loaded elsewhere, so keep them stable.

-- |-- ---------------------------------------------------------------- the minimum graph
-- The span-grain CHECKs need a resolvable trace and span, hence the substrate rows.

INSERT INTO organization (id, slug, name, created_at)
VALUES ('org_fix', 'fixture-org', 'Fixture Org', '2026-01-01T00:00:00Z');

INSERT INTO project (id, org_id, slug, name, created_at)
VALUES ('prj_fix', 'org_fix', 'fixture', 'Fixture Project', '2026-01-01T00:00:00Z');

INSERT INTO call_site (project_id, id, use_case)
VALUES ('prj_fix', 'cs_fix', 'fixture call site');

INSERT INTO project_version (id, project_id, commit_sha, materialized_reason, created_at, updated_at)
VALUES ('pv_fix', 'prj_fix', '0000000000000000000000000000000000000000', 'fixture',
        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');

INSERT INTO session (project_id, id, started_at, last_activity_at, event_ts)
VALUES ('prj_fix', 'ses_fix', '2026-08-01T00:00:00Z', '2026-08-01T00:01:00Z', '2026-08-01T00:01:00Z');

INSERT INTO trace (project_id, id, session_id, started_at, event_ts)
VALUES ('prj_fix', 'trc_fix', 'ses_fix', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

INSERT INTO span (project_id, trace_id, id, session_id, call_site_id, kind, started_at, event_ts)
VALUES ('prj_fix', 'trc_fix', 'spn_fix', 'ses_fix', 'cs_fix', 'llm', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

-- ---------------------------------------------------------------- the classifier

INSERT INTO classifier (id, project_id, classifier_key, name, detector, config_json, mode, created_at, updated_at)
VALUES ('cls_fix', 'prj_fix', 'fixture_classifier', 'Fixture Classifier', 'user_classifier', '{}', 'discovery',
        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');

-- An enabled built-in Frustration row, which 0022 turns off.
INSERT INTO classifier (id, project_id, classifier_key, name, detector, config_json, built_in, version,
                        enabled, mode, created_at, updated_at)
VALUES ('cls_fix_frustration', 'prj_fix', 'frustration', 'Frustration', 'frustration', '{}', true, 8,
        true, 'tracking', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');

-- job.kind is a CHECK-backed enumeration, so a rename of one of its arms aborts on the row.
INSERT INTO job (id, project_id, kind, status, dedupe_key, payload, created_at, updated_at)
VALUES ('job_fix', 'prj_fix', 'classifier', 'done', 'fixture-classifier-job',
        '{"classifier_id":"cls_fix"}'::jsonb, '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

-- payload_json is untyped text, so only an assertion on its contents shows a rewrite ran.
INSERT INTO alert_rule (id, project_id, rule_type, name, classifier_id, created_at, updated_at)
VALUES ('ar_fix', 'prj_fix', 'threshold', 'Fixture Rule', 'cls_fix', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');

INSERT INTO alert_event (id, project_id, alert_rule_id, classifier_id, rule_type, state,
                         window_start, window_end, payload_json, occurred_at, created_at)
VALUES ('ae_fix', 'prj_fix', 'ar_fix', 'cls_fix', 'threshold', 'firing',
        '2026-08-01T00:00:00Z', '2026-08-01T01:00:00Z',
        '{"classifiers":[{"classifier_key":"fixture_classifier","count":3}],"classifier_key":"fixture_classifier"}',
        '2026-08-01T01:00:00Z', '2026-08-01T01:00:00Z');

-- |-- ---------------------------------------------------------------- the three detection tables
-- All span-grain, which the script's coverage block asserts.

INSERT INTO malformed_output_detection (id, project_id, classifier_id, classifier_key, subject_session_id,
                                        subject_trace_id, subject_span_id, severity, confidence, created_at)
VALUES ('det_mo_fix', 'prj_fix', 'cls_fix', 'fixture_classifier', 'ses_fix', 'trc_fix', 'spn_fix', 'warn', 'high', '2026-08-01T00:00:00Z');

INSERT INTO secret_leak_detection (id, project_id, classifier_id, classifier_key, subject_session_id,
                                   subject_trace_id, subject_span_id, severity, confidence, created_at)
VALUES ('det_sl_fix', 'prj_fix', 'cls_fix', 'fixture_classifier', 'ses_fix', 'trc_fix', 'spn_fix', 'warn', 'high', '2026-08-01T00:00:00Z');

INSERT INTO user_classifier_detection (id, project_id, classifier_id, classifier_key, subject_session_id,
                                       subject_trace_id, subject_span_id, severity, confidence, created_at)
VALUES ('det_uc_fix', 'prj_fix', 'cls_fix', 'fixture_classifier', 'ses_fix', 'trc_fix', 'spn_fix', 'warn', 'high', '2026-08-01T00:00:00Z');

-- |-- ---------------------------------------------------------------- the three subject grains
-- One annotation per grain, so a re-cut that forgets the session arm fails.

INSERT INTO annotation (id, project_id, subject_kind, session_id, trace_id, span_id,
                        key, annotator_kind, value_type, passed, created_at)
VALUES ('ann_fix_ses', 'prj_fix', 'session', 'ses_fix', NULL, NULL,
        'fixture.helpful', 'human', 'boolean', true, '2026-08-01T00:00:00Z'),
       ('ann_fix_trc', 'prj_fix', 'trace', 'ses_fix', 'trc_fix', NULL,
        'fixture.helpful', 'human', 'boolean', true, '2026-08-01T00:00:00Z'),
       ('ann_fix_spn', 'prj_fix', 'span', 'ses_fix', 'trc_fix', 'spn_fix',
        'fixture.helpful', 'human', 'boolean', false, '2026-08-01T00:00:00Z');

-- failure_mode_instance carries the same grains under its own `ck_fmi_subject`, and `source` is
-- a CHECK-backed vocabulary (clustered / human).
INSERT INTO failure_mode (id, project_id, key, name, status, created_at, updated_at)
VALUES ('fm_fix', 'prj_fix', 'fixture_mode', 'Fixture Mode', 'open', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

INSERT INTO failure_mode_instance (id, project_id, failure_mode_id, subject_kind,
                                   subject_session_id, subject_trace_id, subject_span_id,
                                   source, created_at)
VALUES ('fmi_fix_ses', 'prj_fix', 'fm_fix', 'session', 'ses_fix', NULL, NULL, 'clustered', '2026-08-01T00:00:00Z'),
       ('fmi_fix_spn', 'prj_fix', 'fm_fix', 'span', 'ses_fix', 'trc_fix', 'spn_fix', 'clustered', '2026-08-01T00:00:00Z');

-- |-- ---------------------------------------------------------------- findings and cases
-- A live case must have a finding (`ck_eval_case_finding_forward`), so the resolved finding-less case
-- covers the other arm.

INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, call_site_id,
                     status, onset_at, last_seen_at, created_at, updated_at)
VALUES ('fnd_fix', 'prj_fix', 'fixture_classifier', 'fixture_cause', 'classifier', 'cls_fix', 'cs_fix',
        'open', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

-- `unclear` under the pre-0010 vocabulary: 0010's UPDATE must rewrite it and its narrowed CHECK must accept it.
INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, call_site_id,
                     status, onset_at, last_seen_at, created_at, updated_at,
                     triage_verdict, triage_action, triage_summary, triaged_at)
VALUES ('fnd_fix_unclear', 'prj_fix', 'fixture_classifier', 'fixture_cause_unclear', 'classifier', 'cls_fix', 'cs_fix',
        'open', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z',
        'unclear', 'closed', 'the evidence did not settle it', '2026-08-02T00:00:00Z');

-- 0011's legacy shapes: `blocked`, `allowlisted` and `graduated` close; an open `positive` finding with a
-- live case below must stay open.
INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, call_site_id,
                     status, onset_at, last_seen_at, created_at, updated_at, human_verdict_at)
VALUES ('fnd_fix_blocked', 'prj_fix', 'fixture_classifier', 'fixture_cause_blocked', 'classifier', 'cls_fix', 'cs_fix',
        'blocked', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z',
        '2026-08-02T00:00:00Z');

INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, call_site_id,
                     status, onset_at, last_seen_at, created_at, updated_at, human_verdict_at)
VALUES ('fnd_fix_allowlisted', 'prj_fix', 'fixture_classifier', 'fixture_cause_allowlisted', 'classifier', 'cls_fix', 'cs_fix',
        'allowlisted', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z',
        '2026-08-02T00:00:00Z');

INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, call_site_id,
                     status, onset_at, last_seen_at, created_at, updated_at)
VALUES ('fnd_fix_graduated', 'prj_fix', 'fixture_classifier', 'fixture_cause_graduated', 'classifier', 'cls_fix', 'cs_fix',
        'graduated', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, call_site_id,
                     status, onset_at, last_seen_at, created_at, updated_at,
                     triage_verdict, triage_action, triage_summary, triaged_at)
VALUES ('fnd_fix_open_cased', 'prj_fix', 'fixture_classifier', 'fixture_cause_open_cased', 'classifier', 'cls_fix', 'cs_fix',
        'open', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z',
        'positive', 'opened_case', 'a triage run found the claim sound', '2026-08-02T00:00:00Z');

-- 0013: high confidence is ruled positive, low is left for triage. No cause_kind, so 0009 leaves both.
INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, call_site_id,
                     status, onset_at, last_seen_at, created_at, updated_at, payload)
VALUES ('fnd_fix_leak_high', 'prj_fix', 'secret_leak', 'fixture_cause_leak_high', 'classifier', 'cls_fix', 'cs_fix',
        'open', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z',
        '{"confidence": "high"}');

INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, call_site_id,
                     status, onset_at, last_seen_at, created_at, updated_at, payload)
VALUES ('fnd_fix_leak_low', 'prj_fix', 'secret_leak', 'fixture_cause_leak_low', 'classifier', 'cls_fix', 'cs_fix',
        'open', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z',
        '{"confidence": "low"}');

INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label, call_site_id,
                       metric, state, title, basis, severity, onset_at, opened_at, last_seen_at, updated_at, finding_id)
VALUES ('ec_fix_finding', 'prj_fix', 1, 'classifier', 'classifier', 'cls_fix', 'Fixture Classifier', 'cs_fix',
        'rate', 'open', 'Fixture case with a finding', 'fixture', 0.5,
        '2026-08-14T00:00:00Z', '2026-08-14T00:00:00Z', '2026-08-14T00:00:00Z', '2026-08-14T00:00:00Z', 'fnd_fix');

INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label, call_site_id,
                       metric, state, title, basis, severity, onset_at, opened_at, last_seen_at, resolved_at,
                       resolution, resolution_reason, updated_at, finding_id)
VALUES ('ec_fix_closed', 'prj_fix', 2, 'classifier', 'classifier', 'cls_fix', 'Fixture Classifier', 'cs_fix',
        'count', 'resolved', 'Fixture case already closed', 'fixture', 0.9,
        '2026-08-14T00:00:00Z', '2026-08-14T00:00:00Z', '2026-08-14T00:00:00Z', '2026-08-14T01:00:00Z',
        'recovered', NULL, '2026-08-14T01:00:00Z', NULL);

-- Backs `fnd_fix_open_cased`: 0011 finds it through `eval_case.finding_id` and keeps that finding open.
INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label, call_site_id,
                       metric, state, title, basis, severity, onset_at, opened_at, last_seen_at, updated_at, finding_id)
VALUES ('ec_fix_open_cased', 'prj_fix', 3, 'classifier', 'classifier', 'cls_fix_open_cased', 'Fixture Classifier', 'cs_fix',
        'rate', 'open', 'Fixture case backing an open-positive finding', 'fixture', 0.5,
        '2026-08-14T00:00:00Z', '2026-08-14T00:00:00Z', '2026-08-14T00:00:00Z', '2026-08-14T00:00:00Z', 'fnd_fix_open_cased');

-- ---------------------------------------------------------------- pre-deploy
INSERT INTO pre_deploy_check (id, project_id, classifier_id, surface, failure_mode_id, intensity, status,
                              created_at, updated_at)
VALUES ('pdc_fix', 'prj_fix', 'cls_fix', 'checkout', 'fm_fix', 'medium', 'active',
        '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

-- |-- ---------------------------------------------------------------- metering
-- `metric` has no CHECK, so a missed rename reads as zero usage, not an error. One row per metric (unique
-- on org, project, metric, bucket_start, granularity).
INSERT INTO metric_rollup (id, org_id, project_id, metric, value, bucket_start, granularity, created_at)
VALUES ('mr_fix_l1', 'org_fix', 'prj_fix', 'l1_evals', 155, '2026-08-01T00:00:00Z', 'day', '2026-08-01T00:00:00Z'),
       ('mr_fix_l2', 'org_fix', 'prj_fix', 'l2_evals', 7, '2026-08-01T00:00:00Z', 'day', '2026-08-01T00:00:00Z');

INSERT INTO metric_baseline (id, project_id, classifier_id, measure, bucket_kind, bucket_key,
                             state, current_count, created_at, updated_at)
VALUES ('mb_fix_a', 'prj_fix', 'cls_fix', 'turn_duration', 'call_site', 'cs_fix',
        'armed', 400, '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

-- |-- ---------------------------------------------------------------- retention
-- All three classes, so a DELETE proves something about the survivors.
INSERT INTO retention_policy (id, project_id, data_class, ttl_days, cold_after_days, created_at)
VALUES ('rp_fix_traces', 'prj_fix', 'traces', 30, 7, '2026-08-01T00:00:00Z'),
       ('rp_fix_detections', 'prj_fix', 'detections', 14, NULL, '2026-08-01T00:00:00Z');

-- ---------------------------------------------------------------- model lanes + LLM ledger
INSERT INTO project_model_setting (project_id, lane, model_key, service_tier, reasoning_effort, created_at, updated_at)
VALUES ('prj_fix', 'triage', 'anthropic.claude-haiku-4-5', 'standard', NULL, '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

-- `lane` has no CHECK, so a missed rewrite would silently drop attribution.
INSERT INTO llm_call (id, project_id, lane, model, service_tier, funding, input_tokens, output_tokens,
                      cost_usd, created_at, subject_kind, subject_id)
VALUES ('llc_fix_trg', 'prj_fix', 'triage', 'anthropic.claude-haiku-4-5', 'standard', 'platform',
        800, 120, 0.0002400000, '2026-08-01T00:00:00Z', NULL, NULL),
-- `authoring` (0033), which 0039 renames to agent_run and then re-CHECKs.
       ('llc_fix_agent', 'prj_fix', 'authoring', 'anthropic.claude-sonnet-4-5', 'standard', 'byo',
        1200, 300, 0.0081000000, '2026-08-01T00:00:00Z', NULL, NULL);

-- |-- ---------------------------------------------------------------- telemetry identity
-- Losing this row would give the instance a new identity.
INSERT INTO telemetry_install (singleton, install_id, created_at)
VALUES (true, '3f2504e0-4f89-41d3-9a0c-0305e82c3301', '2026-08-01T00:00:00Z');
