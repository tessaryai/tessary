-- =============================================================================
-- Populated-database fixture: rows in every table a value rename would have to move
-- =============================================================================
-- Loaded by scripts/check-migrations-populated.sh between the old migration chain (today
-- just the baseline, the script's pinned boundary) and the new one. Its whole job is to
-- make the migrations under test run against rows instead of against nothing. It is
-- written against one schema, the baseline's, which is why that baseline is a pinned
-- filename there rather than whatever `git merge-base` happens to resolve to today.
--
-- Why this file exists at all: `task check` applies Liquibase to an empty Testcontainers
-- database, where `ADD CONSTRAINT ... CHECK` validates zero rows and an
-- `UPDATE ... WHERE col='old'` that matches zero rows is indistinguishable from one that
-- matches every row. Both failures are invisible until a populated database sees them.
--
-- What it holds: a loaded board, one row in each table that carries a persisted vocabulary
-- value (job.kind, the three annotation subject_kind grains, eval_case.detector,
-- retention_policy.data_class, metric_rollup.metric, the model lane, the alert payload keys,
-- the three open detection tables: secret_leak_detection, malformed_output_detection,
-- user_classifier_detection), plus the FK graph they all hang off. The day a migration renames
-- one of those values, the row it has to move is already here and only the assertion needs
-- writing. `prj_fix`/`cls_fix`/`org_fix` below are also used as FK anchors by fixture rows loaded
-- elsewhere in the pipeline, so keep their ids stable.
--
-- Consequence for anyone editing it: adding a rename to a release means adding an
-- assertion in the script, and a row here if the value is not covered above, or the gate
-- reports pass on an untested change. The script's fixture-coverage block fails if a value
-- it asserts on never got loaded, which is the guard against this file quietly drifting.
--
-- Ids are all `*_fix` so a human reading a failed assertion's output can tell fixture rows
-- from anything else, and so a stray fixture row in a real database is obvious.
-- =============================================================================

-- ---------------------------------------------------------------- the minimum graph
-- Nearly every table below is FK-anchored to project, and the substrate rows exist because
-- the span-grain arms of the grain CHECKs (`ck_annotation_grain` and friends) name a trace
-- and a span, so a subject that cannot resolve cannot be inserted, let alone migrated. There
-- is no environment row: a project is the only scope below an org.

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
-- Everything downstream keys on a classifier: the detection tables FK to it, alert
-- rules and events point at it, pre_deploy_check requires one.
-- `classifier_key` is the persisted vocabulary; `id` is opaque.

INSERT INTO classifier (id, project_id, classifier_key, name, detector, config_json, mode, created_at, updated_at)
VALUES ('cls_fix', 'prj_fix', 'fixture_classifier', 'Fixture Classifier', 'user_classifier', '{}', 'discovery',
        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');

-- job.kind is a CHECK-backed enumeration, so a rename of one of its arms aborts on the row.
INSERT INTO job (id, project_id, kind, status, dedupe_key, payload, created_at, updated_at)
VALUES ('job_fix', 'prj_fix', 'classifier', 'done', 'fixture-classifier-job',
        '{"classifier_id":"cls_fix"}'::jsonb, '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

-- payload_json is a text column with no JSON typing, so nothing but an assertion on its
-- contents can tell whether a history rewrite over alert payloads actually ran.
INSERT INTO alert_rule (id, project_id, rule_type, name, classifier_id, created_at, updated_at)
VALUES ('ar_fix', 'prj_fix', 'threshold', 'Fixture Rule', 'cls_fix', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');

INSERT INTO alert_event (id, project_id, alert_rule_id, classifier_id, rule_type, state,
                         window_start, window_end, payload_json, occurred_at, created_at)
VALUES ('ae_fix', 'prj_fix', 'ar_fix', 'cls_fix', 'threshold', 'firing',
        '2026-08-01T00:00:00Z', '2026-08-01T01:00:00Z',
        '{"classifiers":[{"classifier_key":"fixture_classifier","count":3}],"classifier_key":"fixture_classifier"}',
        '2026-08-01T01:00:00Z', '2026-08-01T01:00:00Z');

-- ---------------------------------------------------------------- the three detection tables
-- Detection reads a registry-owned union at the application layer; these are the tables this
-- fixture covers. One row each, all span-grain (subject_span_id set), which is the fact the
-- script's coverage block asserts rather than assumes.

INSERT INTO malformed_output_detection (id, project_id, classifier_id, classifier_key, subject_session_id,
                                        subject_trace_id, subject_span_id, severity, confidence, created_at)
VALUES ('det_mo_fix', 'prj_fix', 'cls_fix', 'fixture_classifier', 'ses_fix', 'trc_fix', 'spn_fix', 'warn', 'high', '2026-08-01T00:00:00Z');

INSERT INTO secret_leak_detection (id, project_id, classifier_id, classifier_key, subject_session_id,
                                   subject_trace_id, subject_span_id, severity, confidence, created_at)
VALUES ('det_sl_fix', 'prj_fix', 'cls_fix', 'fixture_classifier', 'ses_fix', 'trc_fix', 'spn_fix', 'warn', 'high', '2026-08-01T00:00:00Z');

INSERT INTO user_classifier_detection (id, project_id, classifier_id, classifier_key, subject_session_id,
                                       subject_trace_id, subject_span_id, severity, confidence, created_at)
VALUES ('det_uc_fix', 'prj_fix', 'cls_fix', 'fixture_classifier', 'ses_fix', 'trc_fix', 'spn_fix', 'warn', 'high', '2026-08-01T00:00:00Z');

-- ---------------------------------------------------------------- the three subject grains
-- annotation gets one row per grain (session, trace, span) because it carries a subject_kind
-- CHECK and a grain CHECK, and a re-cut that handles the span arm and forgets the session arm
-- passes on any fixture that only has spans. annotation is the classifier's training set as
-- well as the review store; the review half (its verdict pointer, the label and queue tables)
-- has since been removed.

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

-- ---------------------------------------------------------------- findings and cases
-- Two cases: one with a finding, one resolved without. `ck_eval_case_finding_forward` says a
-- live case must have a finding, so the second is the only shape a finding-less case may take,
-- and it is what proves a future narrowing of that constraint runs against both arms.
-- eval_case.detector and finding.subject_kind are both CHECK-backed vocabularies.

INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, call_site_id,
                     status, onset_at, last_seen_at, created_at, updated_at)
VALUES ('fnd_fix', 'prj_fix', 'fixture_classifier', 'fixture_cause', 'classifier', 'cls_fix', 'cs_fix',
        'open', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

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

-- ---------------------------------------------------------------- pre-deploy
INSERT INTO pre_deploy_check (id, project_id, classifier_id, surface, failure_mode_id, intensity, status,
                              created_at, updated_at)
VALUES ('pdc_fix', 'prj_fix', 'cls_fix', 'checkout', 'fm_fix', 'medium', 'active',
        '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

-- ---------------------------------------------------------------- metering
-- `metric` has no CHECK, which is exactly why it needs rows: a zero-row UPDATE here is silent,
-- and the consequence (CapabilityService reading month-to-date usage as 0) shows up as
-- unmetered work, not as an error. Only a count under the old value can tell the difference.
-- `org_fix` also anchors quota-override rows loaded elsewhere in the pipeline; keep it stable.
-- One row per metric: the unique key is (org, project, metric, bucket_start, granularity) and
-- nothing scopes it further.
INSERT INTO metric_rollup (id, org_id, project_id, metric, value, bucket_start, granularity, created_at)
VALUES ('mr_fix_l1', 'org_fix', 'prj_fix', 'l1_evals', 155, '2026-08-01T00:00:00Z', 'day', '2026-08-01T00:00:00Z'),
       ('mr_fix_l2', 'org_fix', 'prj_fix', 'l2_evals', 7, '2026-08-01T00:00:00Z', 'day', '2026-08-01T00:00:00Z');

-- metric_baseline's `state` is a CHECK-backed vocabulary; one window per
-- (project, classifier, measure, bucket_kind, bucket_key) under ux_metric_baseline_scope.
INSERT INTO metric_baseline (id, project_id, classifier_id, measure, bucket_kind, bucket_key,
                             state, current_count, created_at, updated_at)
VALUES ('mb_fix_a', 'prj_fix', 'cls_fix', 'turn_duration', 'call_site', 'cs_fix',
        'armed', 400, '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

-- ---------------------------------------------------------------- retention
-- All three classes the CHECK permits. A fixture with one class would prove a DELETE and
-- nothing about the survivors.
INSERT INTO retention_policy (id, project_id, data_class, ttl_days, cold_after_days, created_at)
VALUES ('rp_fix_traces', 'prj_fix', 'traces', 30, 7, '2026-08-01T00:00:00Z'),
       ('rp_fix_detections', 'prj_fix', 'detections', 14, NULL, '2026-08-01T00:00:00Z');

-- `prj_fix`/`trc_fix`/`ses_fix` also anchor fixture rows loaded elsewhere in the pipeline; keep
-- them stable.

-- ---------------------------------------------------------------- model lanes + LLM ledger
-- `triage` is one of the two lanes ck_project_model_setting_lane admits today (rca, triage);
-- the retired lanes' rows (grading, synthesis, assistant) went with the migrations that retired
-- them, all folded in.
INSERT INTO project_model_setting (project_id, lane, model_key, service_tier, reasoning_effort, created_at, updated_at)
VALUES ('prj_fix', 'triage', 'anthropic.claude-haiku-4-5', 'standard', NULL, '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

-- The ledger. `lane` carries no CHECK, so a rewrite that missed would be silent: the spend page
-- would simply stop attributing these rows rather than error.
INSERT INTO llm_call (id, project_id, lane, model, service_tier, funding, input_tokens, output_tokens,
                      cost_usd, created_at, subject_kind, subject_id)
VALUES ('llc_fix_trg', 'prj_fix', 'triage', 'anthropic.claude-haiku-4-5', 'standard', 'platform',
        800, 120, 0.0002400000, '2026-08-01T00:00:00Z', NULL, NULL);
