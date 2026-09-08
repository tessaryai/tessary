--liquibase formatted sql

--changeset evals:0000-baseline splitStatements:false stripComments:false
--comment: The squashed baseline schema for the OPEN lane, re-photographed 2026-09 for the public
--comment: repo cutover (#1144) so tessaryai/tessary starts life with one changeset instead of the
--comment: churn that had accumulated behind it. The 0017-0023 chain that followed the epic-3
--comment: partition is folded in: the vector/embedding substrate and the centroid classifier's
--comment: table are gone along with the `vector` extension itself (0017), the `assistant` model
--comment: lane is gone and ck_project_model_setting_lane is down to (rca, triage) (0018),
--comment: provider_credential carries auth_mode, custom_model_name and an org-scoped unique index
--comment: (0019, 0020), and `trace` carries the two partial indexes 0021 and 0023 added. Three of
--comment: those changesets were data-only (0018's lane DELETE, 0020's org_id backfill and dedup,
--comment: 0022's triage failed-to-dead sweep): they fold away with no successor object at all,
--comment: since a photograph of an empty database cannot carry a row nobody inserted.
--comment: This file seeds NO rows. embedding_space held the open lane's only platform seed and it
--comment: left with the embedding lane in 0017, so a database born from this baseline starts empty.
--comment: Paid-only tables are not here: they live in the OVERLAY lane, tessary-paid/db's own
--comment: master changelog, whose baseline is tessary-paid:P0000-paid-baseline and which applies
--comment: strictly after this one. That includes behavior_baseline_event_profile_id_fkey, an FK on
--comment: an OPEN table that references an overlay one and so cannot run until the overlay has
--comment: created it.
--comment: There is no conversion path and no straggler accommodation: every database either fold
--comment: ever touched was dropped and recreated on its reset day, so a database of this era has
--comment: run this one changeset (plus, where the paid overlay is present,
--comment: tessary-paid:P0000-paid-baseline) and nothing else. A database that predates the baseline
--comment: fails its first boot loudly on the precondition below and is recreated, never repaired.
--comment: New open migrations go in changes/NNNN-*.sql under author `evals`, numbering from the
--comment: next free number after this fold (0024); paid ones go in the overlay lane under author
--comment: `tessary-paid`, numbering from P0001.
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'project'


--
-- Name: ltree; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS ltree WITH SCHEMA public;


--
-- Name: EXTENSION ltree; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION ltree IS 'data type for hierarchical tree-like structures';


--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


--
-- Name: synth_span_id(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.synth_span_id(p_project_id text, p_old_id text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT
    AS $$
    SELECT lower(substr(encode(sha256(('s:' || p_project_id || ':' || p_old_id)::bytea), 'hex'), 1, 16));
$$;


--
-- Name: synth_trace_id(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.synth_trace_id(p_project_id text, p_old_id text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT
    AS $$
    SELECT lower(substr(encode(sha256(('t:' || p_project_id || ':' || p_old_id)::bytea), 'hex'), 1, 32));
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: alert_channel; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_channel (
    id text NOT NULL,
    project_id text NOT NULL,
    kind text NOT NULL,
    name text NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    config_enc text NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    attributes jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT alert_destination_type_check CHECK ((kind = ANY (ARRAY['slack'::text, 'webhook'::text, 'sentry'::text, 'linear'::text, 'pagerduty'::text])))
);


--
-- Name: alert_delivery_attempt; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_delivery_attempt (
    id text NOT NULL,
    alert_event_id text NOT NULL,
    channel_id text NOT NULL,
    project_id text NOT NULL,
    status text NOT NULL,
    http_status integer,
    error text,
    attempted_at text NOT NULL,
    completed_at text,
    CONSTRAINT alert_delivery_attempt_status_check CHECK ((status = ANY (ARRAY['pending'::text, 'delivered'::text, 'failed'::text])))
);


--
-- Name: alert_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_event (
    id text NOT NULL,
    project_id text NOT NULL,
    alert_rule_id text NOT NULL,
    classifier_id text,
    rule_type text NOT NULL,
    basis text,
    state text DEFAULT 'firing'::text NOT NULL,
    window_start text NOT NULL,
    window_end text NOT NULL,
    value integer,
    threshold integer,
    payload_json text,
    occurred_at text NOT NULL,
    created_at text NOT NULL,
    case_id text,
    CONSTRAINT alert_event_state_check CHECK ((state = ANY (ARRAY['firing'::text, 'resolved'::text])))
);


--
-- Name: alert_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_rule (
    id text NOT NULL,
    project_id text NOT NULL,
    rule_type text NOT NULL,
    name text NOT NULL,
    classifier_id text,
    target text,
    basis text,
    threshold integer,
    window_seconds integer,
    group_by text,
    min_samples integer,
    severity text,
    digest_cron text,
    brief_cron text,
    enabled boolean DEFAULT true NOT NULL,
    snoozed_until text,
    last_evaluated_at text,
    last_digest_at text,
    last_brief_at text,
    attributes jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    CONSTRAINT alert_rule_basis_check CHECK (((basis IS NULL) OR (basis = ANY (ARRAY['distinct_users'::text, 'event_count'::text, 'every_match'::text])))),
    CONSTRAINT alert_rule_rule_type_check CHECK ((rule_type = ANY (ARRAY['threshold'::text, 'digest'::text, 'brief'::text, 'case_opened'::text, 'anomaly'::text, 'trace'::text, 'log'::text, 'exception'::text])))
);


--
-- Name: annotation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.annotation (
    id text NOT NULL,
    project_id text NOT NULL,
    subject_kind text NOT NULL,
    session_id text NOT NULL,
    trace_id text,
    span_id text,
    key text NOT NULL,
    annotator_id text,
    annotator_kind text NOT NULL,
    value_type text DEFAULT 'boolean'::text NOT NULL,
    passed boolean,
    score double precision,
    label text,
    text_value text,
    agrees boolean,
    comment text,
    created_at timestamp with time zone NOT NULL,
    attributes jsonb,
    of_finding_id text,
    CONSTRAINT annotation_annotator_kind_check CHECK ((annotator_kind = ANY (ARRAY['human'::text, 'llm'::text, 'consensus'::text]))),
    CONSTRAINT annotation_subject_kind_check CHECK ((subject_kind = ANY (ARRAY['session'::text, 'trace'::text, 'span'::text]))),
    CONSTRAINT annotation_value_type_check CHECK ((value_type = ANY (ARRAY['boolean'::text, 'numeric'::text, 'categorical'::text, 'text'::text]))),
    CONSTRAINT ck_annotation_grain CHECK ((((subject_kind = 'session'::text) AND (trace_id IS NULL) AND (span_id IS NULL)) OR ((subject_kind = 'trace'::text) AND (trace_id IS NOT NULL) AND (span_id IS NULL)) OR ((subject_kind = 'span'::text) AND (trace_id IS NOT NULL) AND (span_id IS NOT NULL))))
);


--
-- Name: api_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_key (
    id text NOT NULL,
    project_id text NOT NULL,
    principal_id text NOT NULL,
    name text NOT NULL,
    token_prefix text NOT NULL,
    token_hash text NOT NULL,
    created_at text NOT NULL,
    last_used_at text,
    revoked_at text,
    scope text DEFAULT 'write'::text NOT NULL,
    scopes jsonb DEFAULT '{}'::jsonb,
    expires_at text,
    attributes jsonb DEFAULT '{}'::jsonb,
    CONSTRAINT ck_mcp_token_scope CHECK ((scope = ANY (ARRAY['write'::text, 'query'::text, 'admin'::text])))
);


--
-- Name: audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_log (
    id text NOT NULL,
    project_id text NOT NULL,
    principal_id text,
    action text NOT NULL,
    details text,
    occurred_at text NOT NULL,
    organization_id text,
    subject_kind text NOT NULL,
    subject_id text,
    changes jsonb,
    attributes jsonb DEFAULT '{}'::jsonb
);


--
-- Name: behavior_baseline_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.behavior_baseline_event (
    id text NOT NULL,
    profile_id text,
    project_id text NOT NULL,
    event text NOT NULL,
    workflow_key text,
    gram_key text,
    occurred_at text NOT NULL,
    detail jsonb,
    baseline_id text,
    CONSTRAINT behavior_baseline_event_scope_check CHECK (((profile_id IS NULL) <> (baseline_id IS NULL)))
);


--
-- Name: call_site; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.call_site (
    project_id text NOT NULL,
    id text NOT NULL,
    use_case text,
    provider text,
    model text,
    system_prompt text,
    shape text,
    shape_confidence text,
    intent text,
    constraints_json text,
    sample_count integer,
    file_hint text,
    line_hint integer,
    prompt_text text,
    surrounding_code text,
    observed_json text,
    source_spans_json text,
    dataset_path text,
    invocation text,
    expected_spans_json text,
    output_schema text,
    tools_json text
);


--
-- Name: chain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chain (
    project_id text NOT NULL,
    id text NOT NULL,
    name text,
    detection_method text,
    confidence text,
    rationale text,
    call_site_ids_json text,
    ensemble_span_ids_json text
);


--
-- Name: classifier; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.classifier (
    id text NOT NULL,
    project_id text NOT NULL,
    classifier_key text NOT NULL,
    name text NOT NULL,
    description text,
    detector text NOT NULL,
    config_json text,
    built_in boolean DEFAULT true NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    mode text DEFAULT 'discovery'::text NOT NULL,
    CONSTRAINT classifier_mode_check CHECK ((mode = ANY (ARRAY['discovery'::text, 'tracking'::text])))
);


--
-- Name: device_link; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.device_link (
    id text NOT NULL,
    device_code_prefix text NOT NULL,
    device_code_hash text NOT NULL,
    user_code text NOT NULL,
    status text NOT NULL,
    client_label text,
    org_id text,
    project_id text,
    user_id text,
    mcp_token_id text,
    created_at text NOT NULL,
    expires_at text NOT NULL,
    last_polled_at text,
    poll_count integer DEFAULT 0 NOT NULL
);


--
-- Name: eval_case; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.eval_case (
    id text NOT NULL,
    project_id text NOT NULL,
    seq bigint NOT NULL,
    detector text NOT NULL,
    subject_kind text NOT NULL,
    subject_id text NOT NULL,
    subject_label text NOT NULL,
    call_site_id text,
    metric text NOT NULL,
    state text DEFAULT 'open'::text NOT NULL,
    title text NOT NULL,
    basis text NOT NULL,
    severity double precision NOT NULL,
    onset_at text NOT NULL,
    current_value double precision,
    baseline_value double precision,
    delta double precision,
    opened_at text NOT NULL,
    last_seen_at text NOT NULL,
    resolved_at text,
    resolution text,
    resolution_reason text,
    resolved_by text,
    muted_at text,
    muted_by text,
    updated_at text NOT NULL,
    finding_id text,
    CONSTRAINT ck_eval_case_finding_forward CHECK (((finding_id IS NOT NULL) OR (state = 'resolved'::text))),
    CONSTRAINT ck_eval_case_resolution_reason CHECK (((resolution <> 'human'::text) OR (resolution_reason IS NOT NULL))),
    CONSTRAINT ck_eval_case_resolved_stamp CHECK (((state = 'resolved'::text) = (resolved_at IS NOT NULL))),
    CONSTRAINT eval_case_detector_check CHECK ((detector = ANY (ARRAY['behavior_drift'::text, 'classifier'::text, 'metric_drift'::text, 'tool_error'::text, 'sop_conformance'::text]))),
    CONSTRAINT eval_case_resolution_check CHECK (((resolution IS NULL) OR (resolution = ANY (ARRAY['recovered'::text, 'human'::text, 'absorbed'::text])))),
    CONSTRAINT eval_case_state_check CHECK ((state = ANY (ARRAY['open'::text, 'resolved'::text, 'muted'::text])))
);


--
-- Name: eval_case_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.eval_case_event (
    id text NOT NULL,
    case_id text NOT NULL,
    project_id text NOT NULL,
    kind text NOT NULL,
    actor text,
    summary text NOT NULL,
    detail jsonb,
    created_at text NOT NULL,
    CONSTRAINT eval_case_event_kind_check CHECK ((kind = ANY (ARRAY['opened'::text, 'reopened'::text, 'escalated'::text, 'rca_requested'::text, 'rca_completed'::text, 'recovered'::text, 'resolved'::text, 'muted'::text, 'unmuted'::text, 'absorbed'::text])))
);


--
-- Name: failure_mode; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.failure_mode (
    id text NOT NULL,
    project_id text NOT NULL,
    key text NOT NULL,
    name text NOT NULL,
    description text,
    status text DEFAULT 'proposed'::text NOT NULL,
    severity text,
    surface text,
    signature text,
    centroid_ref text,
    impact_count integer DEFAULT 0 NOT NULL,
    impact_rate double precision,
    first_seen_version_id text,
    regressed_version_id text,
    first_seen_at timestamp with time zone,
    last_seen_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    attributes jsonb,
    CONSTRAINT failure_mode_severity_check CHECK ((severity = ANY (ARRAY['low'::text, 'med'::text, 'high'::text, 'critical'::text]))),
    CONSTRAINT failure_mode_status_check CHECK ((status = ANY (ARRAY['proposed'::text, 'open'::text, 'resolved'::text, 'regressed'::text, 'muted'::text])))
);


--
-- Name: failure_mode_instance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.failure_mode_instance (
    id text NOT NULL,
    project_id text NOT NULL,
    failure_mode_id text NOT NULL,
    subject_kind text NOT NULL,
    subject_session_id text,
    subject_trace_id text,
    subject_span_id text,
    source text DEFAULT 'clustered'::text NOT NULL,
    confidence text,
    occurred_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    attributes jsonb,
    CONSTRAINT ck_fmi_subject CHECK ((((subject_kind = 'session'::text) AND (subject_session_id IS NOT NULL)) OR ((subject_kind = 'trace'::text) AND (subject_trace_id IS NOT NULL)) OR ((subject_kind = 'span'::text) AND (subject_trace_id IS NOT NULL) AND (subject_span_id IS NOT NULL)))),
    CONSTRAINT failure_mode_instance_source_check CHECK ((source = ANY (ARRAY['clustered'::text, 'human'::text]))),
    CONSTRAINT failure_mode_instance_subject_kind_check CHECK ((subject_kind = ANY (ARRAY['session'::text, 'trace'::text, 'span'::text])))
);


--
-- Name: finding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.finding (
    id text NOT NULL,
    project_id text NOT NULL,
    classifier_key text NOT NULL,
    cause_key text NOT NULL,
    subject_kind text NOT NULL,
    subject_id text NOT NULL,
    subject_label text,
    call_site_id text,
    status text DEFAULT 'open'::text NOT NULL,
    onset_at text NOT NULL,
    last_seen_at text NOT NULL,
    title text,
    basis text,
    severity double precision,
    sample_count bigint DEFAULT 0 NOT NULL,
    payload jsonb,
    since_version_id text,
    escalated_at text,
    human_verdict_at text,
    recurrences_since_verdict bigint DEFAULT 0 NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    evidence_counts jsonb,
    triage_verdict text,
    triage_action text,
    triage_summary text,
    triage_citations jsonb,
    triaged_at text,
    CONSTRAINT finding_status_check CHECK ((status = ANY (ARRAY['open'::text, 'graduated'::text, 'allowlisted'::text, 'blocked'::text, 'resolved'::text, 'absorbed'::text]))),
    CONSTRAINT finding_subject_kind_check CHECK ((subject_kind = ANY (ARRAY['behavior_profile'::text, 'metric_baseline'::text, 'tool'::text, 'conformance_rule'::text, 'classifier'::text]))),
    CONSTRAINT finding_triage_action_check CHECK (((triage_action IS NULL) OR (triage_action = ANY (ARRAY['opened_case'::text, 'closed'::text])))),
    CONSTRAINT finding_triage_paired_check CHECK (((triage_verdict IS NULL) = (triage_action IS NULL))),
    CONSTRAINT finding_triage_verdict_check CHECK (((triage_verdict IS NULL) OR (triage_verdict = ANY (ARRAY['positive'::text, 'negative'::text, 'unclear'::text]))))
);


--
-- Name: COLUMN finding.evidence_counts; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.finding.evidence_counts IS 'Per-role finding_evidence ref counts as written, {role -> count}. Bumped by FindingEvidenceRepository#record in the same call that inserts the rows, so a finding whose write was interrupted reads as short rather than as complete. Refs age out with their substrate, so a live count(*) may be lower; it is never higher.';


--
-- Name: COLUMN finding.triage_verdict; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.finding.triage_verdict IS 'The Layer-2 ruling on the CLAIM: sound (true, sampled, evidenced), artifact (the detector fired on something that is not there), unclear (the evidence does not settle it). NULL means no run has completed -- a run that did not happen leaves this NULL and lets its job retry, so a NULL is never a ruling.';


--
-- Name: COLUMN finding.triage_action; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.finding.triage_action IS 'What the ruling did, fixed by the verdict: sound -> opened_case, artifact -> closed, unclear -> closed. Written in the same statement as the verdict (finding_triage_paired_check) because a half-observed row would read as a ruling with no consequence.';


--
-- Name: COLUMN finding.triage_citations; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.finding.triage_citations IS 'What the ruling rests on, as [{path, reason}] -- now part prose pointer and part check script, since the agent writes and runs its own deterministic checks and cites their output.';


--
-- Name: finding_evidence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.finding_evidence (
    id text NOT NULL,
    project_id text NOT NULL,
    finding_id text NOT NULL,
    session_id text,
    trace_id text,
    span_id text,
    role text NOT NULL,
    rank integer,
    created_at text NOT NULL,
    CONSTRAINT finding_evidence_grain_check CHECK ((((session_id IS NOT NULL) AND (trace_id IS NULL) AND (span_id IS NULL)) OR ((session_id IS NULL) AND (trace_id IS NOT NULL) AND (span_id IS NULL)) OR ((session_id IS NULL) AND (trace_id IS NOT NULL) AND (span_id IS NOT NULL)))),
    CONSTRAINT finding_evidence_role_check CHECK ((role = ANY (ARRAY['exemplar'::text, 'member'::text, 'baseline'::text, 'witness'::text, 'changepoint'::text])))
);


--
-- Name: git_integration; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.git_integration (
    id text NOT NULL,
    project_id text NOT NULL,
    provider text NOT NULL,
    host text,
    repo_owner text NOT NULL,
    repo_name text NOT NULL,
    default_branch text DEFAULT 'main'::text NOT NULL,
    credentials_enc text,
    observer_cursor_sha text,
    created_at text NOT NULL,
    updated_at text NOT NULL
);


--
-- Name: git_webhook_delivery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.git_webhook_delivery (
    delivery_id text NOT NULL,
    provider text NOT NULL,
    event_type text,
    received_at text NOT NULL,
    status text,
    processed_at text
);


--
-- Name: github_app_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.github_app_config (
    id text DEFAULT 'default'::text NOT NULL,
    credentials_enc text NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    CONSTRAINT github_app_config_singleton_check CHECK ((id = 'default'::text))
);


--
-- Name: grader_failure_mode; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.grader_failure_mode (
    project_id text NOT NULL,
    id text NOT NULL,
    scope text,
    call_site_id text,
    chain_id text,
    name text,
    description text,
    severity text,
    layer text,
    taxonomy_node_id text,
    pack_ids_json text,
    compliance_tags_json text,
    grader_deferred boolean,
    grader_id text
);


--
-- Name: ingestion_source; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ingestion_source (
    id text NOT NULL,
    project_id text NOT NULL,
    provider text NOT NULL,
    name text NOT NULL,
    base_url text NOT NULL,
    credentials_enc text NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL
);


--
-- Name: intelligence_mode_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.intelligence_mode_audit (
    id text NOT NULL,
    single_tenant boolean NOT NULL,
    pooling_enabled boolean NOT NULL,
    recorded_at text NOT NULL
);


--
-- Name: job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.job (
    id text NOT NULL,
    project_id text,
    kind text NOT NULL,
    status text DEFAULT 'pending'::text NOT NULL,
    lease_owner text,
    lease_expires_at text,
    attempts integer DEFAULT 0 NOT NULL,
    last_error text,
    dedupe_key text,
    cursor_at text,
    cursor_id text,
    progress jsonb,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    CONSTRAINT job_kind_check CHECK ((kind = ANY (ARRAY['classifier'::text, 'pull'::text, 'usage_rollup'::text, 'rca'::text, 'behavior_profile'::text, 'triage'::text, 'sop_compile'::text, 'project_delete'::text])))
);


--
-- Name: llm_call; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.llm_call (
    id text NOT NULL,
    project_id text NOT NULL,
    lane text NOT NULL,
    model text,
    service_tier text,
    funding text NOT NULL,
    input_tokens integer,
    output_tokens integer,
    cache_read_tokens integer,
    cache_write_tokens integer,
    total_tokens integer GENERATED ALWAYS AS ((((COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0)) + COALESCE(cache_read_tokens, 0)) + COALESCE(cache_write_tokens, 0))) STORED,
    cost_usd numeric(18,10),
    latency_ms integer,
    created_at timestamp with time zone NOT NULL,
    subject_kind text,
    subject_id text,
    price_book_version text,
    CONSTRAINT ck_llm_call_funding CHECK ((funding = ANY (ARRAY['platform'::text, 'byo'::text])))
);


--
-- Name: malformed_output_detection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.malformed_output_detection (
    id text NOT NULL,
    project_id text NOT NULL,
    classifier_id text NOT NULL,
    classifier_key text NOT NULL,
    project_version_id text,
    subject_session_id text,
    subject_trace_id text NOT NULL,
    subject_span_id text NOT NULL,
    severity text,
    confidence text,
    evidence jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT malformed_output_detection_confidence_check CHECK (((confidence IS NULL) OR (confidence = ANY (ARRAY['high'::text, 'low'::text])))),
    CONSTRAINT malformed_output_detection_severity_check CHECK (((severity IS NULL) OR (severity = ANY (ARRAY['info'::text, 'warn'::text, 'critical'::text]))))
);


--
-- Name: media_object; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.media_object (
    id text NOT NULL,
    project_id text NOT NULL,
    digest text NOT NULL,
    media_type text NOT NULL,
    bytes bytea NOT NULL,
    size_bytes bigint NOT NULL,
    created_at text NOT NULL
);


--
-- Name: media_ref; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.media_ref (
    project_id text NOT NULL,
    media_id text NOT NULL,
    trace_id text NOT NULL,
    span_id text NOT NULL
);


--
-- Name: metric_baseline; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.metric_baseline (
    id text NOT NULL,
    project_id text NOT NULL,
    classifier_id text NOT NULL,
    measure text NOT NULL,
    bucket_kind text NOT NULL,
    bucket_key text NOT NULL,
    state text DEFAULT 'learning'::text NOT NULL,
    pinned_sketch_json text,
    pinned_at text,
    pinned_by_version_id text,
    current_sketch_json text,
    current_opened_at text,
    current_count bigint DEFAULT 0 NOT NULL,
    counted_through_at text,
    counted_through_id text,
    last_event_at text,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    pinned_workload_json text,
    current_workload_json text,
    pinned_tokens_json text,
    prev_tokens_json text,
    current_tokens_json text,
    control_json text,
    pinned_refs_json text,
    CONSTRAINT metric_baseline_state_check CHECK ((state = ANY (ARRAY['learning'::text, 'armed'::text, 'stale'::text])))
);


--
-- Name: COLUMN metric_baseline.control_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.metric_baseline.control_json IS 'Rolling control: {kind, half_life_days, days:[{d,m,w,t}]}, one entry per UTC day, oldest first. Written by MetricDriftSweep on every window close; resolved to a weighted reference on every comparison. Exclusion of confirmed-regression days happens at read time, so the ring itself stays an exact record of what closed when.';


--
-- Name: COLUMN metric_baseline.pinned_refs_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.metric_baseline.pinned_refs_json IS 'The substrate rows the pinned reference window was fitted over, as a JSON array of {t,s} (trace id, optional span id) at the grain that measure is scored at. Written and cleared by MetricDriftSweep together with pinned_sketch_json; read only at finding-open, to write the baseline half of a shifted window''s evidence.';


--
-- Name: metric_rollup; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.metric_rollup (
    id text NOT NULL,
    org_id text NOT NULL,
    project_id text,
    metric text NOT NULL,
    value numeric DEFAULT 0 NOT NULL,
    bucket_start text NOT NULL,
    granularity text NOT NULL,
    created_at text NOT NULL,
    dimensions jsonb DEFAULT '{}'::jsonb,
    attributes jsonb DEFAULT '{}'::jsonb,
    CONSTRAINT usage_rollup_bucket_unit_check CHECK ((granularity = ANY (ARRAY['hour'::text, 'day'::text])))
);


--
-- Name: model; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model (
    id text NOT NULL,
    provider text,
    display_name text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: model_price; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.model_price (
    price_book_version text NOT NULL,
    model_id text NOT NULL,
    input_per_mtok numeric(18,10),
    output_per_mtok numeric(18,10),
    cache_read_per_mtok numeric(18,10),
    cache_write_per_mtok numeric(18,10)
);


--
-- Name: org_feature_flag; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.org_feature_flag (
    org_id text NOT NULL,
    flag_key text NOT NULL,
    enabled boolean NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL
);


--
-- Name: org_invitation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.org_invitation (
    id text NOT NULL,
    org_id text NOT NULL,
    email text NOT NULL,
    role text NOT NULL,
    invited_by text,
    workos_invitation_id text,
    state text DEFAULT 'pending'::text NOT NULL,
    created_at text NOT NULL,
    accepted_at text,
    revoked_at text,
    CONSTRAINT org_invitation_role_check CHECK ((role = ANY (ARRAY['owner'::text, 'admin'::text, 'member'::text, 'viewer'::text, 'billing'::text]))),
    CONSTRAINT org_invitation_state_check CHECK ((state = ANY (ARRAY['pending'::text, 'accepted'::text, 'revoked'::text])))
);


--
-- Name: org_membership; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.org_membership (
    org_id text NOT NULL,
    principal_id text NOT NULL,
    role text NOT NULL,
    created_at text NOT NULL,
    scopes jsonb DEFAULT '{}'::jsonb,
    attributes jsonb DEFAULT '{}'::jsonb,
    CONSTRAINT org_membership_role_check CHECK ((role = ANY (ARRAY['owner'::text, 'admin'::text, 'member'::text, 'viewer'::text, 'billing'::text])))
);


--
-- Name: organization; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.organization (
    id text NOT NULL,
    workos_org_id text,
    slug text NOT NULL,
    name text NOT NULL,
    created_at text NOT NULL,
    archived_at text,
    settings text
);


--
-- Name: pii_redaction_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pii_redaction_rule (
    id text NOT NULL,
    project_id text NOT NULL,
    name text NOT NULL,
    pattern text NOT NULL,
    replacement text NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    built_in boolean DEFAULT false NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL
);


--
-- Name: pipeline_meta; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pipeline_meta (
    project_id text NOT NULL,
    version text NOT NULL,
    product_hint text,
    product_profile_json text,
    invariants_json text,
    invariant_coverage_json text,
    taxonomy_json text,
    updated_at text NOT NULL,
    packs_json text,
    runtime_json text,
    progress_json text,
    synced_commit_sha text,
    repo_owner text,
    repo_name text,
    knowledge_index_json text,
    capabilities_json text
);


--
-- Name: pre_deploy_check; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pre_deploy_check (
    id text NOT NULL,
    project_id text NOT NULL,
    classifier_id text NOT NULL,
    surface text NOT NULL,
    failure_mode_id text,
    intensity text DEFAULT 'medium'::text NOT NULL,
    status text DEFAULT 'active'::text NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    CONSTRAINT pre_deploy_check_status_check CHECK ((status = ANY (ARRAY['active'::text, 'dismissed'::text])))
);


--
-- Name: price_book; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.price_book (
    version text NOT NULL,
    source text NOT NULL,
    published_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: principal; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.principal (
    id text NOT NULL,
    workos_user_id text,
    email text,
    display_name text,
    avatar_url text,
    created_at text NOT NULL,
    last_seen_at text,
    kind text DEFAULT 'human'::text NOT NULL,
    parent_principal_id text,
    status text DEFAULT 'active'::text NOT NULL,
    attributes jsonb DEFAULT '{}'::jsonb,
    password_hash text,
    CONSTRAINT ck_principal_kind CHECK ((kind = ANY (ARRAY['human'::text, 'agent'::text, 'service'::text]))),
    CONSTRAINT ck_principal_status CHECK ((status = ANY (ARRAY['active'::text, 'suspended'::text])))
);


--
-- Name: prior_consent; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.prior_consent (
    org_id text NOT NULL,
    opted_in_at text NOT NULL
);


--
-- Name: prior_contribution; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.prior_contribution (
    id text NOT NULL,
    org_id text NOT NULL,
    feature_key text NOT NULL,
    value double precision NOT NULL,
    sample_count bigint NOT NULL,
    content_ref text,
    created_at text NOT NULL,
    CONSTRAINT prior_contribution_sample_count_check CHECK ((sample_count > 0)),
    CONSTRAINT prior_contribution_value_check CHECK (((value >= (0)::double precision) AND (value <= (1)::double precision)))
);


--
-- Name: project; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project (
    id text NOT NULL,
    org_id text NOT NULL,
    slug text NOT NULL,
    name text NOT NULL,
    description text,
    created_at text NOT NULL,
    synth_default_tier text,
    archived_at text,
    settings text,
    is_default boolean DEFAULT false NOT NULL,
    deleting_at text
);


--
-- Name: project_model_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_model_setting (
    project_id text NOT NULL,
    lane text NOT NULL,
    model_key text NOT NULL,
    service_tier text DEFAULT 'standard'::text NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    reasoning_effort text,
    CONSTRAINT ck_project_model_setting_effort CHECK (((reasoning_effort IS NULL) OR (reasoning_effort = ANY (ARRAY['none'::text, 'low'::text, 'medium'::text, 'high'::text, 'xhigh'::text, 'max'::text])))),
    CONSTRAINT ck_project_model_setting_lane CHECK ((lane = ANY (ARRAY['rca'::text, 'triage'::text]))),
    CONSTRAINT ck_project_model_setting_tier CHECK ((service_tier = ANY (ARRAY['standard'::text, 'flex'::text, 'priority'::text])))
);


--
-- Name: project_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_version (
    id text NOT NULL,
    project_id text NOT NULL,
    commit_sha text NOT NULL,
    parent_sha text,
    materialized_reason text NOT NULL,
    graders_status text DEFAULT 'unknown'::text NOT NULL,
    datasets_status text DEFAULT 'unknown'::text NOT NULL,
    benchmark_status text DEFAULT 'unknown'::text NOT NULL,
    summary text,
    created_at text NOT NULL,
    updated_at text NOT NULL
);


--
-- Name: provider_credential; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.provider_credential (
    id text NOT NULL,
    project_id text,
    provider text NOT NULL,
    base_url_override text,
    api_key_sealed text,
    aws_region text,
    aws_access_key_sealed text,
    aws_secret_key_sealed text,
    bedrock_model_arn text,
    created_at text NOT NULL,
    updated_at text NOT NULL,
    custom_model_name text,
    auth_mode text DEFAULT 'api_key'::text NOT NULL,
    org_id text NOT NULL,
    CONSTRAINT ck_provider_credential_auth_mode CHECK ((auth_mode = ANY (ARRAY['api_key'::text, 'iam_role'::text])))
);


--
-- Name: rca_report; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rca_report (
    id text NOT NULL,
    project_id text NOT NULL,
    job_id text NOT NULL,
    subject_kind text NOT NULL,
    subject_id text NOT NULL,
    subject_label text NOT NULL,
    call_site_id text,
    metric text NOT NULL,
    window_from text NOT NULL,
    window_split text NOT NULL,
    window_to text NOT NULL,
    current_value double precision NOT NULL,
    prior_value double precision NOT NULL,
    delta double precision NOT NULL,
    status text NOT NULL,
    verdict text,
    summary text,
    ruled_out jsonb,
    hypotheses jsonb,
    created_at text NOT NULL,
    completed_at text,
    detailed_report text,
    engine text DEFAULT 'synthesis'::text NOT NULL,
    finding_id text,
    CONSTRAINT rca_report_engine_check CHECK ((engine = ANY (ARRAY['synthesis'::text, 'agentic'::text]))),
    CONSTRAINT rca_report_status_check CHECK ((status = ANY (ARRAY['pending'::text, 'claimed'::text, 'done'::text, 'failed'::text]))),
    CONSTRAINT rca_report_verdict_check CHECK (((verdict IS NULL) OR (verdict = ANY (ARRAY['definition_change'::text, 'model_change'::text, 'traffic_shift'::text, 'behavior_change'::text, 'inconclusive'::text]))))
);


--
-- Name: retention_policy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.retention_policy (
    id text NOT NULL,
    project_id text NOT NULL,
    data_class text NOT NULL,
    ttl_days integer NOT NULL,
    cold_after_days integer,
    created_at text NOT NULL,
    attributes jsonb DEFAULT '{}'::jsonb,
    CONSTRAINT retention_policy_data_class_check CHECK ((data_class = ANY (ARRAY['traces'::text, 'detections'::text])))
);


--
-- Name: retrieved_doc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.retrieved_doc (
    id text NOT NULL,
    project_id text NOT NULL,
    seq integer,
    doc_id text,
    content text,
    score double precision,
    metadata jsonb,
    source_external_id text,
    created_at timestamp with time zone NOT NULL,
    list_role text,
    rank integer,
    title text,
    source_uri text,
    data_source_id text,
    event_ts timestamp with time zone,
    is_deleted boolean DEFAULT false,
    trace_id text,
    span_id text,
    CONSTRAINT retrieved_doc_list_role_check CHECK ((list_role = ANY (ARRAY['candidate'::text, 'result'::text])))
);


--
-- Name: secret_leak_detection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.secret_leak_detection (
    id text NOT NULL,
    project_id text NOT NULL,
    classifier_id text NOT NULL,
    classifier_key text NOT NULL,
    project_version_id text,
    subject_session_id text,
    subject_trace_id text NOT NULL,
    subject_span_id text NOT NULL,
    severity text,
    confidence text,
    evidence jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT secret_leak_detection_confidence_check CHECK (((confidence IS NULL) OR (confidence = ANY (ARRAY['high'::text, 'low'::text])))),
    CONSTRAINT secret_leak_detection_severity_check CHECK (((severity IS NULL) OR (severity = ANY (ARRAY['info'::text, 'warn'::text, 'critical'::text]))))
);


--
-- Name: session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.session (
    project_id text NOT NULL,
    id text NOT NULL,
    user_id text,
    started_at timestamp with time zone NOT NULL,
    last_activity_at timestamp with time zone NOT NULL,
    event_ts timestamp with time zone NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL
);


--
-- Name: span; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.span (
    project_id text NOT NULL,
    trace_id text NOT NULL,
    id text NOT NULL,
    parent_span_id text,
    path public.ltree,
    depth integer GENERATED ALWAYS AS (
CASE
    WHEN (path IS NULL) THEN NULL::integer
    ELSE (public.nlevel(path) - 1)
END) STORED,
    session_id text,
    user_id text,
    project_version_id text,
    call_site_id text,
    trace_name text,
    kind text NOT NULL,
    name text,
    is_logical_root boolean DEFAULT false NOT NULL,
    status text,
    level text,
    error_type text,
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    latency_ms bigint,
    ttft_ms bigint,
    provided_model_name text,
    model_id text,
    input_tokens bigint,
    output_tokens bigint,
    cache_read_tokens bigint,
    cache_write_tokens bigint,
    reasoning_tokens bigint,
    total_tokens bigint GENERATED ALWAYS AS (
CASE
    WHEN ((input_tokens IS NULL) AND (output_tokens IS NULL) AND (cache_read_tokens IS NULL) AND (cache_write_tokens IS NULL) AND (reasoning_tokens IS NULL)) THEN NULL::bigint
    ELSE ((((COALESCE(input_tokens, (0)::bigint) + COALESCE(output_tokens, (0)::bigint)) + COALESCE(cache_read_tokens, (0)::bigint)) + COALESCE(cache_write_tokens, (0)::bigint)) + COALESCE(reasoning_tokens, (0)::bigint))
END) STORED,
    input_cost numeric(18,12),
    output_cost numeric(18,12),
    cache_read_cost numeric(18,12),
    cache_write_cost numeric(18,12),
    total_cost numeric(18,12) GENERATED ALWAYS AS (
CASE
    WHEN ((input_cost IS NULL) AND (output_cost IS NULL) AND (cache_read_cost IS NULL) AND (cache_write_cost IS NULL)) THEN NULL::numeric
    ELSE (((COALESCE(input_cost, (0)::numeric) + COALESCE(output_cost, (0)::numeric)) + COALESCE(cache_read_cost, (0)::numeric)) + COALESCE(cache_write_cost, (0)::numeric))
END) STORED,
    cost_source text DEFAULT 'unpriced'::text NOT NULL,
    price_book_version text,
    input_preview text,
    output_preview text,
    correlation_state text DEFAULT 'pending'::text NOT NULL,
    path_state text DEFAULT 'pending'::text NOT NULL,
    event_ts timestamp with time zone NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    error_message text,
    CONSTRAINT ck_span_cost_source CHECK ((cost_source = ANY (ARRAY['provided'::text, 'inferred'::text, 'unpriced'::text])))
);


--
-- Name: span_payload; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.span_payload (
    project_id text NOT NULL,
    trace_id text NOT NULL,
    span_id text NOT NULL,
    input text,
    output text,
    attributes jsonb,
    provided_usage jsonb,
    event_ts timestamp with time zone NOT NULL
);


--
-- Name: telemetry_install; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.telemetry_install (
    singleton boolean DEFAULT true NOT NULL,
    install_id text NOT NULL,
    created_at text NOT NULL,
    CONSTRAINT telemetry_install_singleton_check CHECK (singleton)
);


--
-- Name: tool_call; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tool_call (
    id text NOT NULL,
    project_id text NOT NULL,
    name text,
    arguments jsonb,
    result jsonb,
    error_type text,
    retries integer,
    latency_ms bigint,
    source_external_id text,
    attributes jsonb,
    started_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    tool_call_id text,
    tool_type text,
    scope text,
    is_error boolean,
    arguments_raw text,
    event_ts timestamp with time zone,
    is_deleted boolean DEFAULT false,
    trace_id text,
    span_id text,
    error_message text,
    CONSTRAINT tool_call_scope_check CHECK ((scope = ANY (ARRAY['client'::text, 'server'::text])))
);


--
-- Name: tool_error_reference; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tool_error_reference (
    project_id text NOT NULL,
    tool_key text NOT NULL,
    calls bigint NOT NULL,
    failures bigint NOT NULL,
    accepted_by text,
    accepted_at text NOT NULL,
    CONSTRAINT tool_error_reference_counts_check CHECK (((calls > 0) AND (failures >= 0) AND (failures <= calls)))
);


--
-- Name: tool_error_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tool_error_state (
    project_id text NOT NULL,
    tool_key text NOT NULL,
    baseline_calls bigint,
    baseline_failures bigint,
    s_up double precision DEFAULT 0 NOT NULL,
    s_down double precision DEFAULT 0 NOT NULL,
    onset_up_at text,
    onset_down_at text,
    calls_since_onset_up bigint DEFAULT 0 NOT NULL,
    calls_since_onset_down bigint DEFAULT 0 NOT NULL,
    watermark_bucket text,
    state_epoch text NOT NULL,
    pending_pin_by text,
    pending_pin_at text,
    reset_at text,
    reset_by text,
    reset_note text,
    updated_at text NOT NULL,
    CONSTRAINT tool_error_state_baseline_check CHECK ((((baseline_calls IS NULL) AND (baseline_failures IS NULL)) OR ((baseline_calls > 0) AND (baseline_failures >= 0) AND (baseline_failures <= baseline_calls)))),
    CONSTRAINT tool_error_state_down_run_check CHECK (((onset_down_at IS NULL) = (calls_since_onset_down = 0))),
    CONSTRAINT tool_error_state_floor_check CHECK (((s_up >= (0)::double precision) AND (s_down >= (0)::double precision))),
    CONSTRAINT tool_error_state_reset_check CHECK (((reset_at IS NULL) OR ((reset_note IS NOT NULL) AND (length(TRIM(BOTH FROM reset_note)) > 0)))),
    CONSTRAINT tool_error_state_run_check CHECK (((calls_since_onset_up >= 0) AND (calls_since_onset_down >= 0))),
    CONSTRAINT tool_error_state_up_run_check CHECK (((onset_up_at IS NULL) = (calls_since_onset_up = 0)))
);


--
-- Name: trace; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trace (
    project_id text NOT NULL,
    id text NOT NULL,
    session_id text,
    parent_trace_id text,
    thread_id text,
    name text,
    user_id text,
    project_version_id text,
    status text,
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    latency_ms bigint GENERATED ALWAYS AS (
CASE
    WHEN (ended_at IS NULL) THEN NULL::bigint
    ELSE ((EXTRACT(epoch FROM (ended_at - started_at)) * (1000)::numeric))::bigint
END) STORED,
    span_count integer,
    error_count integer,
    input_tokens bigint,
    output_tokens bigint,
    cache_read_tokens bigint,
    cache_write_tokens bigint,
    reasoning_tokens bigint,
    total_tokens bigint,
    input_cost numeric(18,12),
    output_cost numeric(18,12),
    total_cost numeric(18,12),
    unpriced_spans integer,
    input_preview text,
    output_preview text,
    call_site_id text,
    rollup_due_at timestamp with time zone,
    rolled_up_at timestamp with time zone,
    rolled_up_through timestamp with time zone,
    is_settled boolean DEFAULT false NOT NULL,
    has_root_span boolean DEFAULT false NOT NULL,
    event_ts timestamp with time zone NOT NULL,
    is_deleted boolean DEFAULT false NOT NULL
);


--
-- Name: user_classifier_detection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_classifier_detection (
    id text NOT NULL,
    project_id text NOT NULL,
    classifier_id text NOT NULL,
    classifier_key text NOT NULL,
    project_version_id text,
    subject_session_id text,
    subject_trace_id text NOT NULL,
    subject_span_id text NOT NULL,
    severity text,
    confidence text,
    evidence jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_classifier_detection_confidence_check CHECK (((confidence IS NULL) OR (confidence = ANY (ARRAY['high'::text, 'low'::text])))),
    CONSTRAINT user_classifier_detection_severity_check CHECK (((severity IS NULL) OR (severity = ANY (ARRAY['info'::text, 'warn'::text, 'critical'::text]))))
);


--
-- Name: alert_delivery_attempt alert_delivery_attempt_alert_event_id_channel_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_delivery_attempt
    ADD CONSTRAINT alert_delivery_attempt_alert_event_id_channel_id_key UNIQUE (alert_event_id, channel_id);


--
-- Name: alert_delivery_attempt alert_delivery_attempt_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_delivery_attempt
    ADD CONSTRAINT alert_delivery_attempt_pkey PRIMARY KEY (id);


--
-- Name: alert_channel alert_destination_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_channel
    ADD CONSTRAINT alert_destination_pkey PRIMARY KEY (id);


--
-- Name: alert_event alert_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_event
    ADD CONSTRAINT alert_event_pkey PRIMARY KEY (id);


--
-- Name: alert_rule alert_rule_classifier_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT alert_rule_classifier_id_key UNIQUE (classifier_id);


--
-- Name: alert_rule alert_rule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT alert_rule_pkey PRIMARY KEY (id);


--
-- Name: annotation annotation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.annotation
    ADD CONSTRAINT annotation_pkey PRIMARY KEY (id);


--
-- Name: audit_log api_key_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT api_key_audit_pkey PRIMARY KEY (id);


--
-- Name: principal app_user_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.principal
    ADD CONSTRAINT app_user_email_key UNIQUE (email);


--
-- Name: principal app_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.principal
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);


--
-- Name: principal app_user_workos_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.principal
    ADD CONSTRAINT app_user_workos_user_id_key UNIQUE (workos_user_id);


--
-- Name: behavior_baseline_event behavior_baseline_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.behavior_baseline_event
    ADD CONSTRAINT behavior_baseline_event_pkey PRIMARY KEY (id);


--
-- Name: call_site call_site_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.call_site
    ADD CONSTRAINT call_site_pkey PRIMARY KEY (project_id, id);


--
-- Name: chain chain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain
    ADD CONSTRAINT chain_pkey PRIMARY KEY (project_id, id);


--
-- Name: classifier classifier_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.classifier
    ADD CONSTRAINT classifier_pkey PRIMARY KEY (id);


--
-- Name: classifier classifier_project_id_classifier_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.classifier
    ADD CONSTRAINT classifier_project_id_classifier_key_key UNIQUE (project_id, classifier_key);


--
-- Name: device_link device_link_device_code_prefix_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_link
    ADD CONSTRAINT device_link_device_code_prefix_key UNIQUE (device_code_prefix);


--
-- Name: device_link device_link_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_link
    ADD CONSTRAINT device_link_pkey PRIMARY KEY (id);


--
-- Name: device_link device_link_user_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_link
    ADD CONSTRAINT device_link_user_code_key UNIQUE (user_code);


--
-- Name: eval_case_event eval_case_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.eval_case_event
    ADD CONSTRAINT eval_case_event_pkey PRIMARY KEY (id);


--
-- Name: eval_case eval_case_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.eval_case
    ADD CONSTRAINT eval_case_pkey PRIMARY KEY (id);


--
-- Name: failure_mode_instance failure_mode_instance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.failure_mode_instance
    ADD CONSTRAINT failure_mode_instance_pkey PRIMARY KEY (id);


--
-- Name: grader_failure_mode failure_mode_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.grader_failure_mode
    ADD CONSTRAINT failure_mode_pkey PRIMARY KEY (project_id, id);


--
-- Name: failure_mode failure_mode_pkey1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.failure_mode
    ADD CONSTRAINT failure_mode_pkey1 PRIMARY KEY (id);


--
-- Name: failure_mode failure_mode_project_id_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.failure_mode
    ADD CONSTRAINT failure_mode_project_id_key_key UNIQUE (project_id, key);


--
-- Name: finding_evidence finding_evidence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_evidence
    ADD CONSTRAINT finding_evidence_pkey PRIMARY KEY (id);


--
-- Name: finding finding_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding
    ADD CONSTRAINT finding_pkey PRIMARY KEY (id);


--
-- Name: git_integration git_integration_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.git_integration
    ADD CONSTRAINT git_integration_pkey PRIMARY KEY (id);


--
-- Name: git_integration git_integration_project_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.git_integration
    ADD CONSTRAINT git_integration_project_id_key UNIQUE (project_id);


--
-- Name: git_webhook_delivery git_webhook_delivery_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.git_webhook_delivery
    ADD CONSTRAINT git_webhook_delivery_pkey PRIMARY KEY (delivery_id);


--
-- Name: github_app_config github_app_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.github_app_config
    ADD CONSTRAINT github_app_config_pkey PRIMARY KEY (id);


--
-- Name: ingestion_source ingestion_source_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ingestion_source
    ADD CONSTRAINT ingestion_source_pkey PRIMARY KEY (id);


--
-- Name: ingestion_source ingestion_source_project_id_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ingestion_source
    ADD CONSTRAINT ingestion_source_project_id_name_key UNIQUE (project_id, name);


--
-- Name: intelligence_mode_audit intelligence_mode_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.intelligence_mode_audit
    ADD CONSTRAINT intelligence_mode_audit_pkey PRIMARY KEY (id);


--
-- Name: job job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job
    ADD CONSTRAINT job_pkey PRIMARY KEY (id);


--
-- Name: llm_call llm_call_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.llm_call
    ADD CONSTRAINT llm_call_pkey PRIMARY KEY (id);


--
-- Name: malformed_output_detection malformed_output_detection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.malformed_output_detection
    ADD CONSTRAINT malformed_output_detection_pkey PRIMARY KEY (id);


--
-- Name: api_key mcp_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_key
    ADD CONSTRAINT mcp_token_pkey PRIMARY KEY (id);


--
-- Name: api_key mcp_token_token_prefix_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_key
    ADD CONSTRAINT mcp_token_token_prefix_key UNIQUE (token_prefix);


--
-- Name: media_object media_object_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.media_object
    ADD CONSTRAINT media_object_pkey PRIMARY KEY (id);


--
-- Name: media_object media_object_project_id_digest_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.media_object
    ADD CONSTRAINT media_object_project_id_digest_key UNIQUE (project_id, digest);


--
-- Name: metric_baseline metric_baseline_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.metric_baseline
    ADD CONSTRAINT metric_baseline_pkey PRIMARY KEY (id);


--
-- Name: model model_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model
    ADD CONSTRAINT model_pkey PRIMARY KEY (id);


--
-- Name: model_price model_price_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_price
    ADD CONSTRAINT model_price_pkey PRIMARY KEY (price_book_version, model_id);


--
-- Name: org_feature_flag org_feature_flag_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_feature_flag
    ADD CONSTRAINT org_feature_flag_pkey PRIMARY KEY (org_id, flag_key);


--
-- Name: org_invitation org_invitation_org_id_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_invitation
    ADD CONSTRAINT org_invitation_org_id_email_key UNIQUE (org_id, email);


--
-- Name: org_invitation org_invitation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_invitation
    ADD CONSTRAINT org_invitation_pkey PRIMARY KEY (id);


--
-- Name: org_membership org_membership_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_membership
    ADD CONSTRAINT org_membership_pkey PRIMARY KEY (org_id, principal_id);


--
-- Name: organization organization_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organization
    ADD CONSTRAINT organization_pkey PRIMARY KEY (id);


--
-- Name: organization organization_slug_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organization
    ADD CONSTRAINT organization_slug_key UNIQUE (slug);


--
-- Name: organization organization_workos_org_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organization
    ADD CONSTRAINT organization_workos_org_id_key UNIQUE (workos_org_id);


--
-- Name: pii_redaction_rule pii_redaction_rule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pii_redaction_rule
    ADD CONSTRAINT pii_redaction_rule_pkey PRIMARY KEY (id);


--
-- Name: pipeline_meta pipeline_meta_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pipeline_meta
    ADD CONSTRAINT pipeline_meta_pkey PRIMARY KEY (project_id);


--
-- Name: media_ref pk_media_ref; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.media_ref
    ADD CONSTRAINT pk_media_ref PRIMARY KEY (project_id, media_id, trace_id, span_id);


--
-- Name: session pk_session; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.session
    ADD CONSTRAINT pk_session PRIMARY KEY (project_id, id);


--
-- Name: span pk_span; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.span
    ADD CONSTRAINT pk_span PRIMARY KEY (project_id, trace_id, id);


--
-- Name: span_payload pk_span_payload; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.span_payload
    ADD CONSTRAINT pk_span_payload PRIMARY KEY (project_id, trace_id, span_id);


--
-- Name: trace pk_trace; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trace
    ADD CONSTRAINT pk_trace PRIMARY KEY (project_id, id);


--
-- Name: pre_deploy_check pre_deploy_check_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pre_deploy_check
    ADD CONSTRAINT pre_deploy_check_pkey PRIMARY KEY (id);


--
-- Name: price_book price_book_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_book
    ADD CONSTRAINT price_book_pkey PRIMARY KEY (version);


--
-- Name: prior_consent prior_consent_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prior_consent
    ADD CONSTRAINT prior_consent_pkey PRIMARY KEY (org_id);


--
-- Name: prior_contribution prior_contribution_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prior_contribution
    ADD CONSTRAINT prior_contribution_pkey PRIMARY KEY (id);


--
-- Name: project_model_setting project_model_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_model_setting
    ADD CONSTRAINT project_model_setting_pkey PRIMARY KEY (project_id, lane);


--
-- Name: project project_org_id_slug_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project
    ADD CONSTRAINT project_org_id_slug_key UNIQUE (org_id, slug);


--
-- Name: project project_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project
    ADD CONSTRAINT project_pkey PRIMARY KEY (id);


--
-- Name: project_version project_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_version
    ADD CONSTRAINT project_version_pkey PRIMARY KEY (id);


--
-- Name: project_version project_version_project_id_commit_sha_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_version
    ADD CONSTRAINT project_version_project_id_commit_sha_key UNIQUE (project_id, commit_sha);


--
-- Name: provider_credential provider_credential_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credential
    ADD CONSTRAINT provider_credential_pkey PRIMARY KEY (id);


--
-- Name: rca_report rca_report_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rca_report
    ADD CONSTRAINT rca_report_pkey PRIMARY KEY (id);


--
-- Name: retention_policy retention_policy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retention_policy
    ADD CONSTRAINT retention_policy_pkey PRIMARY KEY (id);


--
-- Name: retention_policy retention_policy_project_id_data_class_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retention_policy
    ADD CONSTRAINT retention_policy_project_id_data_class_key UNIQUE (project_id, data_class);


--
-- Name: retrieved_doc retrieval_document_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieved_doc
    ADD CONSTRAINT retrieval_document_pkey PRIMARY KEY (id);


--
-- Name: secret_leak_detection secret_leak_detection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.secret_leak_detection
    ADD CONSTRAINT secret_leak_detection_pkey PRIMARY KEY (id);


--
-- Name: telemetry_install telemetry_install_install_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telemetry_install
    ADD CONSTRAINT telemetry_install_install_id_key UNIQUE (install_id);


--
-- Name: telemetry_install telemetry_install_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telemetry_install
    ADD CONSTRAINT telemetry_install_pkey PRIMARY KEY (singleton);


--
-- Name: tool_call tool_call_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_call
    ADD CONSTRAINT tool_call_pkey PRIMARY KEY (id);


--
-- Name: tool_error_reference tool_error_reference_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_error_reference
    ADD CONSTRAINT tool_error_reference_pkey PRIMARY KEY (project_id, tool_key);


--
-- Name: tool_error_state tool_error_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_error_state
    ADD CONSTRAINT tool_error_state_pkey PRIMARY KEY (project_id, tool_key);


--
-- Name: metric_rollup usage_rollup_org_id_project_id_metric_bucket_start_gran_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.metric_rollup
    ADD CONSTRAINT usage_rollup_org_id_project_id_metric_bucket_start_gran_key UNIQUE NULLS NOT DISTINCT (org_id, project_id, metric, bucket_start, granularity);


--
-- Name: metric_rollup usage_rollup_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.metric_rollup
    ADD CONSTRAINT usage_rollup_pkey PRIMARY KEY (id);


--
-- Name: user_classifier_detection user_classifier_detection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_classifier_detection
    ADD CONSTRAINT user_classifier_detection_pkey PRIMARY KEY (id);


--
-- Name: prior_contribution ux_prior_contribution_org_feature; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prior_contribution
    ADD CONSTRAINT ux_prior_contribution_org_feature UNIQUE (org_id, feature_key);


--
-- Name: idx_media_ref_media_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_media_ref_media_id ON public.media_ref USING btree (media_id);


--
-- Name: ix_alert_channel_project_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_alert_channel_project_enabled ON public.alert_channel USING btree (project_id, enabled);


--
-- Name: ix_alert_delivery_attempt_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_alert_delivery_attempt_project ON public.alert_delivery_attempt USING btree (project_id, attempted_at);


--
-- Name: ix_alert_event_case; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_alert_event_case ON public.alert_event USING btree (case_id) WHERE (case_id IS NOT NULL);


--
-- Name: ix_alert_event_classifier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_alert_event_classifier ON public.alert_event USING btree (classifier_id);


--
-- Name: ix_alert_event_project_occurred; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_alert_event_project_occurred ON public.alert_event USING btree (project_id, occurred_at DESC);


--
-- Name: ix_alert_event_rule; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_alert_event_rule ON public.alert_event USING btree (alert_rule_id);


--
-- Name: ix_alert_rule_project_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_alert_rule_project_enabled ON public.alert_rule USING btree (project_id, enabled);


--
-- Name: ix_annotation_of_finding; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_annotation_of_finding ON public.annotation USING btree (of_finding_id) WHERE (of_finding_id IS NOT NULL);


--
-- Name: ix_annotation_project_key; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_annotation_project_key ON public.annotation USING btree (project_id, key);


--
-- Name: ix_annotation_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_annotation_session ON public.annotation USING btree (session_id);


--
-- Name: ix_annotation_span; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_annotation_span ON public.annotation USING btree (span_id) WHERE (span_id IS NOT NULL);


--
-- Name: ix_api_key_prefix; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_api_key_prefix ON public.api_key USING btree (token_prefix);


--
-- Name: ix_api_key_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_api_key_project ON public.api_key USING btree (project_id, revoked_at);


--
-- Name: ix_api_key_project_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_api_key_project_scope ON public.api_key USING btree (project_id, scope);


--
-- Name: ix_audit_log_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_audit_log_org ON public.audit_log USING btree (organization_id, occurred_at);


--
-- Name: ix_audit_log_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_audit_log_project ON public.audit_log USING btree (project_id, occurred_at);


--
-- Name: ix_audit_log_subject; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_audit_log_subject ON public.audit_log USING btree (subject_kind, subject_id);


--
-- Name: ix_behavior_baseline_event_profile; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_behavior_baseline_event_profile ON public.behavior_baseline_event USING btree (profile_id, event, occurred_at DESC);


--
-- Name: ix_behavior_baseline_event_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_behavior_baseline_event_project ON public.behavior_baseline_event USING btree (project_id, occurred_at DESC);


--
-- Name: ix_call_site_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_call_site_project ON public.call_site USING btree (project_id);


--
-- Name: ix_chain_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_chain_project ON public.chain USING btree (project_id);


--
-- Name: ix_classifier_project_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_classifier_project_enabled ON public.classifier USING btree (project_id, enabled);


--
-- Name: ix_device_link_prefix; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_device_link_prefix ON public.device_link USING btree (device_code_prefix);


--
-- Name: ix_device_link_user_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_device_link_user_code ON public.device_link USING btree (user_code);


--
-- Name: ix_eval_case_event_case; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_eval_case_event_case ON public.eval_case_event USING btree (case_id, created_at);


--
-- Name: ix_eval_case_finding_live; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_eval_case_finding_live ON public.eval_case USING btree (finding_id) WHERE (state <> 'resolved'::text);


--
-- Name: ix_eval_case_live_rank; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_eval_case_live_rank ON public.eval_case USING btree (project_id, severity DESC, opened_at DESC) WHERE (state <> 'resolved'::text);


--
-- Name: ix_eval_case_resolved; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_eval_case_resolved ON public.eval_case USING btree (project_id, detector, subject_kind, subject_id, metric, resolved_at DESC) WHERE (state = 'resolved'::text);


--
-- Name: ix_failure_mode_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_failure_mode_project ON public.failure_mode USING btree (project_id);


--
-- Name: ix_finding_call_site; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_finding_call_site ON public.finding USING btree (project_id, call_site_id, last_seen_at DESC);


--
-- Name: ix_finding_evidence_finding; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_finding_evidence_finding ON public.finding_evidence USING btree (finding_id, role, rank);


--
-- Name: ix_finding_evidence_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_finding_evidence_session ON public.finding_evidence USING btree (project_id, session_id) WHERE (session_id IS NOT NULL);


--
-- Name: ix_finding_evidence_trace; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_finding_evidence_trace ON public.finding_evidence USING btree (project_id, trace_id) WHERE (trace_id IS NOT NULL);


--
-- Name: ix_finding_project_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_finding_project_status ON public.finding USING btree (project_id, status, last_seen_at DESC);


--
-- Name: ix_finding_subject; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_finding_subject ON public.finding USING btree (project_id, subject_kind, subject_id);


--
-- Name: ix_finding_triaged; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_finding_triaged ON public.finding USING btree (project_id, triage_verdict) WHERE ((status = 'open'::text) AND (triage_verdict IS NOT NULL));


--
-- Name: ix_fmi_failure_mode; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_fmi_failure_mode ON public.failure_mode_instance USING btree (failure_mode_id);


--
-- Name: ix_fmi_subject_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_fmi_subject_session ON public.failure_mode_instance USING btree (subject_session_id) WHERE (subject_session_id IS NOT NULL);


--
-- Name: ix_fmi_subject_span; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_fmi_subject_span ON public.failure_mode_instance USING btree (subject_span_id) WHERE (subject_span_id IS NOT NULL);


--
-- Name: ix_git_integration_repo; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_git_integration_repo ON public.git_integration USING btree (provider, repo_owner, repo_name);


--
-- Name: ix_grader_failure_mode_call_site; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_grader_failure_mode_call_site ON public.grader_failure_mode USING btree (project_id, call_site_id);


--
-- Name: ix_grader_failure_mode_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_grader_failure_mode_project ON public.grader_failure_mode USING btree (project_id);


--
-- Name: ix_ingestion_source_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ingestion_source_project ON public.ingestion_source USING btree (project_id);


--
-- Name: ix_intelligence_mode_audit_recorded_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_intelligence_mode_audit_recorded_at ON public.intelligence_mode_audit USING btree (recorded_at);


--
-- Name: ix_job_kind_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_job_kind_status_created ON public.job USING btree (kind, status, created_at);


--
-- Name: ix_job_kind_status_updated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_job_kind_status_updated ON public.job USING btree (kind, status, updated_at);


--
-- Name: ix_job_project_kind_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_job_project_kind_status ON public.job USING btree (project_id, kind, status);


--
-- Name: ix_llm_call_project_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_llm_call_project_created ON public.llm_call USING btree (project_id, created_at DESC);


--
-- Name: ix_llm_call_subject; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_llm_call_subject ON public.llm_call USING btree (subject_kind, subject_id) WHERE (subject_kind IS NOT NULL);


--
-- Name: ix_malformed_output_detection_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_malformed_output_detection_recent ON public.malformed_output_detection USING btree (project_id, created_at DESC);


--
-- Name: ix_media_object_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_media_object_project ON public.media_object USING btree (project_id);


--
-- Name: ix_metric_baseline_classifier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_metric_baseline_classifier ON public.metric_baseline USING btree (project_id, classifier_id, current_count DESC);


--
-- Name: ix_metric_rollup_org_bucket; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_metric_rollup_org_bucket ON public.metric_rollup USING btree (org_id, bucket_start);


--
-- Name: ix_metric_rollup_project_metric_bucket; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_metric_rollup_project_metric_bucket ON public.metric_rollup USING btree (project_id, metric, bucket_start);


--
-- Name: ix_org_invitation_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_org_invitation_email ON public.org_invitation USING btree (email);


--
-- Name: ix_org_membership_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_org_membership_principal ON public.org_membership USING btree (principal_id);


--
-- Name: ix_pii_redaction_rule_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_pii_redaction_rule_project ON public.pii_redaction_rule USING btree (project_id);


--
-- Name: ix_pre_deploy_check_classifier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_pre_deploy_check_classifier ON public.pre_deploy_check USING btree (classifier_id);


--
-- Name: ix_pre_deploy_check_project_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_pre_deploy_check_project_status ON public.pre_deploy_check USING btree (project_id, status);


--
-- Name: ix_principal_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_principal_kind ON public.principal USING btree (kind);


--
-- Name: ix_prior_contribution_feature; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_prior_contribution_feature ON public.prior_contribution USING btree (feature_key);


--
-- Name: ix_prior_contribution_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_prior_contribution_org ON public.prior_contribution USING btree (org_id);


--
-- Name: ix_project_deleting; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_project_deleting ON public.project USING btree (deleting_at) WHERE (deleting_at IS NOT NULL);


--
-- Name: ix_project_org; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_project_org ON public.project USING btree (org_id);


--
-- Name: ix_project_version_timeline; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_project_version_timeline ON public.project_version USING btree (project_id, created_at DESC);


--
-- Name: ix_rca_report_finding; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_rca_report_finding ON public.rca_report USING btree (project_id, finding_id, created_at DESC) WHERE (finding_id IS NOT NULL);


--
-- Name: ix_rca_report_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_rca_report_project ON public.rca_report USING btree (project_id, created_at DESC);


--
-- Name: ix_retention_policy_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_retention_policy_project ON public.retention_policy USING btree (project_id);


--
-- Name: ix_retrieved_doc_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_retrieved_doc_project ON public.retrieved_doc USING btree (project_id);


--
-- Name: ix_retrieved_doc_trace; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_retrieved_doc_trace ON public.retrieved_doc USING btree (project_id, trace_id) WHERE (trace_id IS NOT NULL);


--
-- Name: ix_secret_leak_detection_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_secret_leak_detection_recent ON public.secret_leak_detection USING btree (project_id, created_at DESC);


--
-- Name: ix_session_project_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_session_project_active ON public.session USING btree (project_id, last_activity_at DESC);


--
-- Name: ix_span_call_site; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_span_call_site ON public.span USING btree (call_site_id, started_at DESC) WHERE (call_site_id IS NOT NULL);


--
-- Name: ix_span_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_span_name_trgm ON public.span USING gin (name public.gin_trgm_ops);


--
-- Name: ix_span_path; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_span_path ON public.span USING gist (path);


--
-- Name: ix_span_payload_fts; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_span_payload_fts ON public.span_payload USING gin (to_tsvector('simple'::regconfig, (("left"(COALESCE(input, ''::text), 100000) || ' '::text) || "left"(COALESCE(output, ''::text), 100000))));


--
-- Name: ix_span_project_started; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_span_project_started ON public.span USING btree (project_id, started_at DESC);


--
-- Name: ix_span_uncorrelated; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_span_uncorrelated ON public.span USING btree (project_id, trace_id) WHERE ((session_id IS NULL) AND (correlation_state = 'pending'::text));


--
-- Name: ix_span_unresolved_path; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_span_unresolved_path ON public.span USING btree (project_id, trace_id) WHERE ((path IS NULL) AND (path_state = 'pending'::text));


--
-- Name: ix_tool_call_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_tool_call_id ON public.tool_call USING btree (project_id, tool_call_id);


--
-- Name: ix_tool_call_project_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_tool_call_project_created ON public.tool_call USING btree (project_id, created_at DESC);


--
-- Name: ix_tool_call_project_error; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_tool_call_project_error ON public.tool_call USING btree (project_id) WHERE (error_type IS NOT NULL);


--
-- Name: ix_tool_call_project_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_tool_call_project_name ON public.tool_call USING btree (project_id, name);


--
-- Name: ix_tool_call_project_started; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_tool_call_project_started ON public.tool_call USING btree (project_id, started_at);


--
-- Name: ix_tool_call_trace; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_tool_call_trace ON public.tool_call USING btree (project_id, trace_id) WHERE (trace_id IS NOT NULL);


--
-- Name: ix_trace_project_live; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_trace_project_live ON public.trace USING btree (project_id) WHERE (is_deleted IS NOT TRUE);


--
-- Name: ix_trace_project_started; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_trace_project_started ON public.trace USING btree (project_id, started_at DESC);


--
-- Name: ix_trace_rollup_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_trace_rollup_due ON public.trace USING btree (rollup_due_at) WHERE (rollup_due_at IS NOT NULL);


--
-- Name: ix_trace_scope_settled_started; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_trace_scope_settled_started ON public.trace USING btree (project_id, COALESCE(call_site_id, '__unattributed__'::text), started_at, id) WHERE (is_settled AND (is_deleted IS NOT TRUE));


--
-- Name: ix_trace_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_trace_session ON public.trace USING btree (project_id, session_id, started_at) WHERE (session_id IS NOT NULL);


--
-- Name: ix_user_classifier_detection_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_user_classifier_detection_recent ON public.user_classifier_detection USING btree (project_id, created_at DESC);


--
-- Name: ux_alert_event_rule_case; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_alert_event_rule_case ON public.alert_event USING btree (alert_rule_id, case_id) WHERE (case_id IS NOT NULL);


--
-- Name: ux_alert_event_rule_window; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_alert_event_rule_window ON public.alert_event USING btree (alert_rule_id, window_start) WHERE (case_id IS NULL);


--
-- Name: ux_alert_rule_project_rolluptype; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_alert_rule_project_rolluptype ON public.alert_rule USING btree (project_id, rule_type) WHERE (rule_type = ANY (ARRAY['digest'::text, 'brief'::text, 'case_opened'::text]));


--
-- Name: ux_annotation_current; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_annotation_current ON public.annotation USING btree (annotator_kind, annotator_id, key, subject_kind, session_id, trace_id, span_id) NULLS NOT DISTINCT;


--
-- Name: ux_eval_case_live; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_eval_case_live ON public.eval_case USING btree (project_id, detector, subject_kind, subject_id, metric) WHERE (state <> 'resolved'::text);


--
-- Name: ux_eval_case_seq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_eval_case_seq ON public.eval_case USING btree (project_id, seq);


--
-- Name: ux_finding_evidence_ref; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_finding_evidence_ref ON public.finding_evidence USING btree (finding_id, role, COALESCE(session_id, ''::text), COALESCE(trace_id, ''::text), COALESCE(span_id, ''::text));


--
-- Name: ux_finding_live; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_finding_live ON public.finding USING btree (project_id, classifier_key, cause_key) WHERE (status = ANY (ARRAY['open'::text, 'blocked'::text]));


--
-- Name: ux_fmi_subject; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_fmi_subject ON public.failure_mode_instance USING btree (failure_mode_id, subject_kind, subject_session_id, subject_trace_id, subject_span_id) NULLS NOT DISTINCT;


--
-- Name: ux_job_behavior_profile; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_job_behavior_profile ON public.job USING btree (dedupe_key) WHERE (kind = 'behavior_profile'::text);


--
-- Name: ux_job_classifier; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_job_classifier ON public.job USING btree (dedupe_key) WHERE (kind = 'classifier'::text);


--
-- Name: ux_job_coalesce; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_job_coalesce ON public.job USING btree (kind, dedupe_key) WHERE ((dedupe_key IS NOT NULL) AND (status = 'pending'::text) AND (kind = ANY (ARRAY['observer'::text, 'synth'::text, 'usage_rollup'::text])));


--
-- Name: ux_job_project_delete; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_job_project_delete ON public.job USING btree (dedupe_key) WHERE ((kind = 'project_delete'::text) AND (status = ANY (ARRAY['pending'::text, 'claimed'::text])));


--
-- Name: ux_job_rca; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_job_rca ON public.job USING btree (dedupe_key) WHERE (kind = 'rca'::text);


--
-- Name: ux_job_sop_compile; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_job_sop_compile ON public.job USING btree (dedupe_key) WHERE (kind = 'sop_compile'::text);


--
-- Name: ux_job_triage; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_job_triage ON public.job USING btree (dedupe_key) WHERE (kind = 'triage'::text);


--
-- Name: ux_job_usage_rollup; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_job_usage_rollup ON public.job USING btree (dedupe_key) WHERE (kind = 'usage_rollup'::text);


--
-- Name: ux_malformed_output_detection_subject; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_malformed_output_detection_subject ON public.malformed_output_detection USING btree (project_id, classifier_id, subject_trace_id, subject_span_id);


--
-- Name: ux_metric_baseline_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_metric_baseline_scope ON public.metric_baseline USING btree (project_id, classifier_id, measure, bucket_kind, bucket_key);


--
-- Name: ux_pre_deploy_check_classifier; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_pre_deploy_check_classifier ON public.pre_deploy_check USING btree (project_id, classifier_id, surface);


--
-- Name: ux_project_one_default_per_org; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_project_one_default_per_org ON public.project USING btree (org_id) WHERE is_default;


--
-- Name: ux_provider_credential_org_provider; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_provider_credential_org_provider ON public.provider_credential USING btree (org_id, provider);


--
-- Name: ux_rca_report_job; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_rca_report_job ON public.rca_report USING btree (job_id);


--
-- Name: ux_secret_leak_detection_subject; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_secret_leak_detection_subject ON public.secret_leak_detection USING btree (project_id, classifier_id, subject_trace_id, subject_span_id);


--
-- Name: ux_user_classifier_detection_subject; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_user_classifier_detection_subject ON public.user_classifier_detection USING btree (project_id, classifier_id, subject_trace_id, subject_span_id);


--
-- Name: alert_delivery_attempt alert_delivery_attempt_alert_event_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_delivery_attempt
    ADD CONSTRAINT alert_delivery_attempt_alert_event_id_fkey FOREIGN KEY (alert_event_id) REFERENCES public.alert_event(id) ON DELETE CASCADE;


--
-- Name: alert_delivery_attempt alert_delivery_attempt_channel_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_delivery_attempt
    ADD CONSTRAINT alert_delivery_attempt_channel_id_fkey FOREIGN KEY (channel_id) REFERENCES public.alert_channel(id) ON DELETE CASCADE;


--
-- Name: alert_delivery_attempt alert_delivery_attempt_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_delivery_attempt
    ADD CONSTRAINT alert_delivery_attempt_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: alert_channel alert_destination_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_channel
    ADD CONSTRAINT alert_destination_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: alert_event alert_event_alert_rule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_event
    ADD CONSTRAINT alert_event_alert_rule_id_fkey FOREIGN KEY (alert_rule_id) REFERENCES public.alert_rule(id) ON DELETE CASCADE;


--
-- Name: alert_event alert_event_classifier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_event
    ADD CONSTRAINT alert_event_classifier_id_fkey FOREIGN KEY (classifier_id) REFERENCES public.classifier(id) ON DELETE SET NULL;


--
-- Name: alert_event alert_event_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_event
    ADD CONSTRAINT alert_event_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: alert_rule alert_rule_classifier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT alert_rule_classifier_id_fkey FOREIGN KEY (classifier_id) REFERENCES public.classifier(id) ON DELETE CASCADE;


--
-- Name: alert_rule alert_rule_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT alert_rule_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: annotation annotation_of_finding_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.annotation
    ADD CONSTRAINT annotation_of_finding_id_fkey FOREIGN KEY (of_finding_id) REFERENCES public.finding(id) ON DELETE SET NULL;


--
-- Name: annotation annotation_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.annotation
    ADD CONSTRAINT annotation_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: audit_log api_key_audit_actor_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT api_key_audit_actor_user_id_fkey FOREIGN KEY (principal_id) REFERENCES public.principal(id);


--
-- Name: audit_log api_key_audit_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT api_key_audit_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: audit_log audit_log_organization_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES public.organization(id) ON DELETE CASCADE;


--
-- Name: behavior_baseline_event behavior_baseline_event_baseline_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.behavior_baseline_event
    ADD CONSTRAINT behavior_baseline_event_baseline_id_fkey FOREIGN KEY (baseline_id) REFERENCES public.metric_baseline(id) ON DELETE CASCADE;


--
-- Name: call_site call_site_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.call_site
    ADD CONSTRAINT call_site_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: chain chain_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chain
    ADD CONSTRAINT chain_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: classifier classifier_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.classifier
    ADD CONSTRAINT classifier_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: device_link device_link_mcp_token_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_link
    ADD CONSTRAINT device_link_mcp_token_id_fkey FOREIGN KEY (mcp_token_id) REFERENCES public.api_key(id);


--
-- Name: device_link device_link_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_link
    ADD CONSTRAINT device_link_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organization(id) ON DELETE CASCADE;


--
-- Name: device_link device_link_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_link
    ADD CONSTRAINT device_link_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: device_link device_link_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.device_link
    ADD CONSTRAINT device_link_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.principal(id);


--
-- Name: eval_case_event eval_case_event_case_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.eval_case_event
    ADD CONSTRAINT eval_case_event_case_id_fkey FOREIGN KEY (case_id) REFERENCES public.eval_case(id) ON DELETE CASCADE;


--
-- Name: eval_case_event eval_case_event_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.eval_case_event
    ADD CONSTRAINT eval_case_event_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: eval_case eval_case_finding_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.eval_case
    ADD CONSTRAINT eval_case_finding_id_fkey FOREIGN KEY (finding_id) REFERENCES public.finding(id) ON DELETE RESTRICT;


--
-- Name: eval_case eval_case_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.eval_case
    ADD CONSTRAINT eval_case_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: failure_mode failure_mode_first_seen_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.failure_mode
    ADD CONSTRAINT failure_mode_first_seen_version_id_fkey FOREIGN KEY (first_seen_version_id) REFERENCES public.project_version(id) ON DELETE SET NULL;


--
-- Name: failure_mode_instance failure_mode_instance_failure_mode_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.failure_mode_instance
    ADD CONSTRAINT failure_mode_instance_failure_mode_id_fkey FOREIGN KEY (failure_mode_id) REFERENCES public.failure_mode(id) ON DELETE CASCADE;


--
-- Name: failure_mode_instance failure_mode_instance_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.failure_mode_instance
    ADD CONSTRAINT failure_mode_instance_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: grader_failure_mode failure_mode_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.grader_failure_mode
    ADD CONSTRAINT failure_mode_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: failure_mode failure_mode_project_id_fkey1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.failure_mode
    ADD CONSTRAINT failure_mode_project_id_fkey1 FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: failure_mode failure_mode_regressed_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.failure_mode
    ADD CONSTRAINT failure_mode_regressed_version_id_fkey FOREIGN KEY (regressed_version_id) REFERENCES public.project_version(id) ON DELETE SET NULL;


--
-- Name: finding_evidence finding_evidence_finding_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_evidence
    ADD CONSTRAINT finding_evidence_finding_id_fkey FOREIGN KEY (finding_id) REFERENCES public.finding(id) ON DELETE CASCADE;


--
-- Name: finding_evidence finding_evidence_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding_evidence
    ADD CONSTRAINT finding_evidence_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: finding finding_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.finding
    ADD CONSTRAINT finding_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: media_ref fk_media_ref_media; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.media_ref
    ADD CONSTRAINT fk_media_ref_media FOREIGN KEY (media_id) REFERENCES public.media_object(id) ON DELETE CASCADE;


--
-- Name: media_ref fk_media_ref_payload; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.media_ref
    ADD CONSTRAINT fk_media_ref_payload FOREIGN KEY (project_id, trace_id, span_id) REFERENCES public.span_payload(project_id, trace_id, span_id) ON DELETE CASCADE;


--
-- Name: span_payload fk_span_payload_span; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.span_payload
    ADD CONSTRAINT fk_span_payload_span FOREIGN KEY (project_id, trace_id, span_id) REFERENCES public.span(project_id, trace_id, id) ON DELETE CASCADE;


--
-- Name: span fk_span_trace; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.span
    ADD CONSTRAINT fk_span_trace FOREIGN KEY (project_id, trace_id) REFERENCES public.trace(project_id, id) ON DELETE CASCADE;


--
-- Name: trace fk_trace_parent; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trace
    ADD CONSTRAINT fk_trace_parent FOREIGN KEY (project_id, parent_trace_id) REFERENCES public.trace(project_id, id);


--
-- Name: trace fk_trace_project; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trace
    ADD CONSTRAINT fk_trace_project FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: trace fk_trace_session; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trace
    ADD CONSTRAINT fk_trace_session FOREIGN KEY (project_id, session_id) REFERENCES public.session(project_id, id);


--
-- Name: git_integration git_integration_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.git_integration
    ADD CONSTRAINT git_integration_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: ingestion_source ingestion_source_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ingestion_source
    ADD CONSTRAINT ingestion_source_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: job job_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job
    ADD CONSTRAINT job_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: llm_call llm_call_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.llm_call
    ADD CONSTRAINT llm_call_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: malformed_output_detection malformed_output_detection_classifier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.malformed_output_detection
    ADD CONSTRAINT malformed_output_detection_classifier_id_fkey FOREIGN KEY (classifier_id) REFERENCES public.classifier(id) ON DELETE CASCADE;


--
-- Name: malformed_output_detection malformed_output_detection_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.malformed_output_detection
    ADD CONSTRAINT malformed_output_detection_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: api_key mcp_token_created_by_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_key
    ADD CONSTRAINT mcp_token_created_by_user_id_fkey FOREIGN KEY (principal_id) REFERENCES public.principal(id);


--
-- Name: api_key mcp_token_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_key
    ADD CONSTRAINT mcp_token_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: media_object media_object_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.media_object
    ADD CONSTRAINT media_object_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: metric_baseline metric_baseline_classifier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.metric_baseline
    ADD CONSTRAINT metric_baseline_classifier_id_fkey FOREIGN KEY (classifier_id) REFERENCES public.classifier(id) ON DELETE CASCADE;


--
-- Name: metric_baseline metric_baseline_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.metric_baseline
    ADD CONSTRAINT metric_baseline_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: model_price model_price_model_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_price
    ADD CONSTRAINT model_price_model_id_fkey FOREIGN KEY (model_id) REFERENCES public.model(id);


--
-- Name: model_price model_price_price_book_version_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.model_price
    ADD CONSTRAINT model_price_price_book_version_fkey FOREIGN KEY (price_book_version) REFERENCES public.price_book(version) ON DELETE CASCADE;


--
-- Name: org_invitation org_invitation_invited_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_invitation
    ADD CONSTRAINT org_invitation_invited_by_fkey FOREIGN KEY (invited_by) REFERENCES public.principal(id) ON DELETE SET NULL;


--
-- Name: org_invitation org_invitation_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_invitation
    ADD CONSTRAINT org_invitation_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organization(id) ON DELETE CASCADE;


--
-- Name: org_membership org_membership_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_membership
    ADD CONSTRAINT org_membership_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organization(id) ON DELETE CASCADE;


--
-- Name: org_membership org_membership_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.org_membership
    ADD CONSTRAINT org_membership_user_id_fkey FOREIGN KEY (principal_id) REFERENCES public.principal(id) ON DELETE CASCADE;


--
-- Name: pii_redaction_rule pii_redaction_rule_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pii_redaction_rule
    ADD CONSTRAINT pii_redaction_rule_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: pipeline_meta pipeline_meta_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pipeline_meta
    ADD CONSTRAINT pipeline_meta_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: pre_deploy_check pre_deploy_check_classifier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pre_deploy_check
    ADD CONSTRAINT pre_deploy_check_classifier_id_fkey FOREIGN KEY (classifier_id) REFERENCES public.classifier(id) ON DELETE CASCADE;


--
-- Name: pre_deploy_check pre_deploy_check_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pre_deploy_check
    ADD CONSTRAINT pre_deploy_check_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: principal principal_parent_principal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.principal
    ADD CONSTRAINT principal_parent_principal_id_fkey FOREIGN KEY (parent_principal_id) REFERENCES public.principal(id) ON DELETE SET NULL;


--
-- Name: prior_consent prior_consent_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prior_consent
    ADD CONSTRAINT prior_consent_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organization(id) ON DELETE CASCADE;


--
-- Name: prior_contribution prior_contribution_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.prior_contribution
    ADD CONSTRAINT prior_contribution_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organization(id) ON DELETE CASCADE;


--
-- Name: project_model_setting project_model_setting_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_model_setting
    ADD CONSTRAINT project_model_setting_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: project project_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project
    ADD CONSTRAINT project_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organization(id) ON DELETE CASCADE;


--
-- Name: project_version project_version_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_version
    ADD CONSTRAINT project_version_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: provider_credential provider_credential_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credential
    ADD CONSTRAINT provider_credential_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organization(id) ON DELETE CASCADE;


--
-- Name: provider_credential provider_credential_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credential
    ADD CONSTRAINT provider_credential_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE SET NULL;


--
-- Name: rca_report rca_report_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rca_report
    ADD CONSTRAINT rca_report_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.job(id) ON DELETE CASCADE;


--
-- Name: rca_report rca_report_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rca_report
    ADD CONSTRAINT rca_report_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: retention_policy retention_policy_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retention_policy
    ADD CONSTRAINT retention_policy_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: retrieved_doc retrieval_document_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieved_doc
    ADD CONSTRAINT retrieval_document_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: secret_leak_detection secret_leak_detection_classifier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.secret_leak_detection
    ADD CONSTRAINT secret_leak_detection_classifier_id_fkey FOREIGN KEY (classifier_id) REFERENCES public.classifier(id) ON DELETE CASCADE;


--
-- Name: secret_leak_detection secret_leak_detection_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.secret_leak_detection
    ADD CONSTRAINT secret_leak_detection_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: session session_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.session
    ADD CONSTRAINT session_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: span span_model_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.span
    ADD CONSTRAINT span_model_id_fkey FOREIGN KEY (model_id) REFERENCES public.model(id);


--
-- Name: span span_price_book_version_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.span
    ADD CONSTRAINT span_price_book_version_fkey FOREIGN KEY (price_book_version) REFERENCES public.price_book(version);


--
-- Name: span span_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.span
    ADD CONSTRAINT span_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: tool_call tool_call_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_call
    ADD CONSTRAINT tool_call_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: tool_error_reference tool_error_reference_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_error_reference
    ADD CONSTRAINT tool_error_reference_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: tool_error_state tool_error_state_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tool_error_state
    ADD CONSTRAINT tool_error_state_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: metric_rollup usage_rollup_org_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.metric_rollup
    ADD CONSTRAINT usage_rollup_org_id_fkey FOREIGN KEY (org_id) REFERENCES public.organization(id) ON DELETE CASCADE;


--
-- Name: metric_rollup usage_rollup_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.metric_rollup
    ADD CONSTRAINT usage_rollup_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- Name: user_classifier_detection user_classifier_detection_classifier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_classifier_detection
    ADD CONSTRAINT user_classifier_detection_classifier_id_fkey FOREIGN KEY (classifier_id) REFERENCES public.classifier(id) ON DELETE CASCADE;


--
-- Name: user_classifier_detection user_classifier_detection_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_classifier_detection
    ADD CONSTRAINT user_classifier_detection_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.project(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--


