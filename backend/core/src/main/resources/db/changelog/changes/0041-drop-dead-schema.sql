--liquibase formatted sql

--changeset evals:0041-drop-dead-schema
-- Drops schema no code reads or writes. The seven tables are leftovers of features this tree no longer
-- has (annotations, clustered failure modes, git webhooks, intelligence mode, shared priors). The
-- columns held one value end to end: service tier and reasoning effort were always standard and null,
-- metric_rollup's dimensions and attributes always '{}', principal.status always active and never
-- checked, and the other four were never written at all.
DROP TABLE failure_mode_instance;
DROP TABLE failure_mode;
DROP TABLE annotation;
DROP TABLE git_webhook_delivery;
DROP TABLE intelligence_mode_audit;
DROP TABLE prior_consent;
DROP TABLE prior_contribution;
ALTER TABLE git_integration DROP COLUMN observer_cursor_sha;
ALTER TABLE metric_baseline DROP COLUMN prev_tokens_json;
ALTER TABLE pipeline_meta DROP COLUMN knowledge_index_json;
ALTER TABLE project DROP COLUMN synth_default_tier;
ALTER TABLE metric_rollup DROP COLUMN dimensions;
ALTER TABLE metric_rollup DROP COLUMN attributes;
ALTER TABLE llm_call DROP COLUMN service_tier;
ALTER TABLE project_model_setting DROP CONSTRAINT ck_project_model_setting_tier;
ALTER TABLE project_model_setting DROP CONSTRAINT ck_project_model_setting_effort;
ALTER TABLE project_model_setting DROP COLUMN service_tier;
ALTER TABLE project_model_setting DROP COLUMN reasoning_effort;
ALTER TABLE principal DROP CONSTRAINT ck_principal_status;
ALTER TABLE principal DROP COLUMN status;

--rollback ALTER TABLE principal ADD COLUMN status text DEFAULT 'active'::text NOT NULL;
--rollback ALTER TABLE principal ADD CONSTRAINT ck_principal_status CHECK ((status = ANY (ARRAY['active'::text, 'suspended'::text])));
--rollback ALTER TABLE project_model_setting ADD COLUMN reasoning_effort text;
--rollback ALTER TABLE project_model_setting ADD COLUMN service_tier text DEFAULT 'standard'::text NOT NULL;
--rollback ALTER TABLE project_model_setting ADD CONSTRAINT ck_project_model_setting_effort CHECK (((reasoning_effort IS NULL) OR (reasoning_effort = ANY (ARRAY['none'::text, 'low'::text, 'medium'::text, 'high'::text, 'xhigh'::text, 'max'::text]))));
--rollback ALTER TABLE project_model_setting ADD CONSTRAINT ck_project_model_setting_tier CHECK ((service_tier = ANY (ARRAY['standard'::text, 'flex'::text, 'priority'::text])));
--rollback ALTER TABLE llm_call ADD COLUMN service_tier text;
--rollback ALTER TABLE metric_rollup ADD COLUMN attributes jsonb DEFAULT '{}'::jsonb;
--rollback ALTER TABLE metric_rollup ADD COLUMN dimensions jsonb DEFAULT '{}'::jsonb;
--rollback ALTER TABLE project ADD COLUMN synth_default_tier text;
--rollback ALTER TABLE pipeline_meta ADD COLUMN knowledge_index_json text;
--rollback ALTER TABLE metric_baseline ADD COLUMN prev_tokens_json text;
--rollback ALTER TABLE git_integration ADD COLUMN observer_cursor_sha text;
--rollback CREATE TABLE failure_mode (
--rollback     id text NOT NULL,
--rollback     project_id text NOT NULL,
--rollback     key text NOT NULL,
--rollback     name text NOT NULL,
--rollback     description text,
--rollback     status text DEFAULT 'proposed'::text NOT NULL,
--rollback     severity text,
--rollback     surface text,
--rollback     signature text,
--rollback     centroid_ref text,
--rollback     impact_count integer DEFAULT 0 NOT NULL,
--rollback     impact_rate double precision,
--rollback     first_seen_version_id text,
--rollback     regressed_version_id text,
--rollback     first_seen_at timestamp with time zone,
--rollback     last_seen_at timestamp with time zone,
--rollback     created_at timestamp with time zone NOT NULL,
--rollback     updated_at timestamp with time zone NOT NULL,
--rollback     attributes jsonb,
--rollback     CONSTRAINT failure_mode_severity_check CHECK ((severity = ANY (ARRAY['low'::text, 'med'::text, 'high'::text, 'critical'::text]))),
--rollback     CONSTRAINT failure_mode_status_check CHECK ((status = ANY (ARRAY['proposed'::text, 'open'::text, 'resolved'::text, 'regressed'::text, 'muted'::text])))
--rollback );
--rollback CREATE TABLE failure_mode_instance (
--rollback     id text NOT NULL,
--rollback     project_id text NOT NULL,
--rollback     failure_mode_id text NOT NULL,
--rollback     subject_kind text NOT NULL,
--rollback     subject_session_id text,
--rollback     subject_trace_id text,
--rollback     subject_span_id text,
--rollback     source text DEFAULT 'clustered'::text NOT NULL,
--rollback     confidence text,
--rollback     occurred_at timestamp with time zone,
--rollback     created_at timestamp with time zone NOT NULL,
--rollback     attributes jsonb,
--rollback     CONSTRAINT ck_fmi_subject CHECK ((((subject_kind = 'session'::text) AND (subject_session_id IS NOT NULL)) OR ((subject_kind = 'trace'::text) AND (subject_trace_id IS NOT NULL)) OR ((subject_kind = 'span'::text) AND (subject_trace_id IS NOT NULL) AND (subject_span_id IS NOT NULL)))),
--rollback     CONSTRAINT failure_mode_instance_source_check CHECK ((source = ANY (ARRAY['clustered'::text, 'human'::text]))),
--rollback     CONSTRAINT failure_mode_instance_subject_kind_check CHECK ((subject_kind = ANY (ARRAY['session'::text, 'trace'::text, 'span'::text])))
--rollback );
--rollback CREATE TABLE annotation (
--rollback     id text NOT NULL,
--rollback     project_id text NOT NULL,
--rollback     subject_kind text NOT NULL,
--rollback     session_id text NOT NULL,
--rollback     trace_id text,
--rollback     span_id text,
--rollback     key text NOT NULL,
--rollback     annotator_id text,
--rollback     annotator_kind text NOT NULL,
--rollback     value_type text DEFAULT 'boolean'::text NOT NULL,
--rollback     passed boolean,
--rollback     score double precision,
--rollback     label text,
--rollback     text_value text,
--rollback     agrees boolean,
--rollback     comment text,
--rollback     created_at timestamp with time zone NOT NULL,
--rollback     attributes jsonb,
--rollback     of_finding_id text,
--rollback     CONSTRAINT annotation_annotator_kind_check CHECK ((annotator_kind = ANY (ARRAY['human'::text, 'llm'::text, 'consensus'::text]))),
--rollback     CONSTRAINT annotation_subject_kind_check CHECK ((subject_kind = ANY (ARRAY['session'::text, 'trace'::text, 'span'::text]))),
--rollback     CONSTRAINT annotation_value_type_check CHECK ((value_type = ANY (ARRAY['boolean'::text, 'numeric'::text, 'categorical'::text, 'text'::text]))),
--rollback     CONSTRAINT ck_annotation_grain CHECK ((((subject_kind = 'session'::text) AND (trace_id IS NULL) AND (span_id IS NULL)) OR ((subject_kind = 'trace'::text) AND (trace_id IS NOT NULL) AND (span_id IS NULL)) OR ((subject_kind = 'span'::text) AND (trace_id IS NOT NULL) AND (span_id IS NOT NULL))))
--rollback );
--rollback CREATE TABLE git_webhook_delivery (
--rollback     delivery_id text NOT NULL,
--rollback     provider text NOT NULL,
--rollback     event_type text,
--rollback     received_at text NOT NULL,
--rollback     status text,
--rollback     processed_at text
--rollback );
--rollback CREATE TABLE intelligence_mode_audit (
--rollback     id text NOT NULL,
--rollback     single_tenant boolean NOT NULL,
--rollback     pooling_enabled boolean NOT NULL,
--rollback     recorded_at text NOT NULL
--rollback );
--rollback CREATE TABLE prior_consent (
--rollback     org_id text NOT NULL,
--rollback     opted_in_at text NOT NULL
--rollback );
--rollback CREATE TABLE prior_contribution (
--rollback     id text NOT NULL,
--rollback     org_id text NOT NULL,
--rollback     feature_key text NOT NULL,
--rollback     value double precision NOT NULL,
--rollback     sample_count bigint NOT NULL,
--rollback     content_ref text,
--rollback     created_at text NOT NULL,
--rollback     CONSTRAINT prior_contribution_sample_count_check CHECK ((sample_count > 0)),
--rollback     CONSTRAINT prior_contribution_value_check CHECK (((value >= (0)::double precision) AND (value <= (1)::double precision)))
--rollback );
--rollback ALTER TABLE ONLY annotation
--rollback     ADD CONSTRAINT annotation_pkey PRIMARY KEY (id);
--rollback ALTER TABLE ONLY failure_mode_instance
--rollback     ADD CONSTRAINT failure_mode_instance_pkey PRIMARY KEY (id);
--rollback ALTER TABLE ONLY failure_mode
--rollback     ADD CONSTRAINT failure_mode_pkey1 PRIMARY KEY (id);
--rollback ALTER TABLE ONLY failure_mode
--rollback     ADD CONSTRAINT failure_mode_project_id_key_key UNIQUE (project_id, key);
--rollback ALTER TABLE ONLY git_webhook_delivery
--rollback     ADD CONSTRAINT git_webhook_delivery_pkey PRIMARY KEY (delivery_id);
--rollback ALTER TABLE ONLY intelligence_mode_audit
--rollback     ADD CONSTRAINT intelligence_mode_audit_pkey PRIMARY KEY (id);
--rollback ALTER TABLE ONLY prior_consent
--rollback     ADD CONSTRAINT prior_consent_pkey PRIMARY KEY (org_id);
--rollback ALTER TABLE ONLY prior_contribution
--rollback     ADD CONSTRAINT prior_contribution_pkey PRIMARY KEY (id);
--rollback ALTER TABLE ONLY prior_contribution
--rollback     ADD CONSTRAINT ux_prior_contribution_org_feature UNIQUE (org_id, feature_key);
--rollback ALTER TABLE ONLY annotation
--rollback     ADD CONSTRAINT annotation_of_finding_id_fkey FOREIGN KEY (of_finding_id) REFERENCES finding(id) ON DELETE SET NULL;
--rollback ALTER TABLE ONLY annotation
--rollback     ADD CONSTRAINT annotation_project_id_fkey FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE;
--rollback ALTER TABLE ONLY failure_mode
--rollback     ADD CONSTRAINT failure_mode_first_seen_version_id_fkey FOREIGN KEY (first_seen_version_id) REFERENCES project_version(id) ON DELETE SET NULL;
--rollback ALTER TABLE ONLY failure_mode_instance
--rollback     ADD CONSTRAINT failure_mode_instance_failure_mode_id_fkey FOREIGN KEY (failure_mode_id) REFERENCES failure_mode(id) ON DELETE CASCADE;
--rollback ALTER TABLE ONLY failure_mode_instance
--rollback     ADD CONSTRAINT failure_mode_instance_project_id_fkey FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE;
--rollback ALTER TABLE ONLY failure_mode
--rollback     ADD CONSTRAINT failure_mode_project_id_fkey1 FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE;
--rollback ALTER TABLE ONLY failure_mode
--rollback     ADD CONSTRAINT failure_mode_regressed_version_id_fkey FOREIGN KEY (regressed_version_id) REFERENCES project_version(id) ON DELETE SET NULL;
--rollback ALTER TABLE ONLY prior_consent
--rollback     ADD CONSTRAINT prior_consent_org_id_fkey FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE;
--rollback ALTER TABLE ONLY prior_contribution
--rollback     ADD CONSTRAINT prior_contribution_org_id_fkey FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE;
--rollback CREATE INDEX ix_annotation_of_finding ON annotation USING btree (of_finding_id) WHERE (of_finding_id IS NOT NULL);
--rollback CREATE INDEX ix_annotation_project_key ON annotation USING btree (project_id, key);
--rollback CREATE INDEX ix_annotation_session ON annotation USING btree (session_id);
--rollback CREATE INDEX ix_annotation_span ON annotation USING btree (span_id) WHERE (span_id IS NOT NULL);
--rollback CREATE INDEX ix_failure_mode_project ON failure_mode USING btree (project_id);
--rollback CREATE INDEX ix_fmi_failure_mode ON failure_mode_instance USING btree (failure_mode_id);
--rollback CREATE INDEX ix_fmi_subject_session ON failure_mode_instance USING btree (subject_session_id) WHERE (subject_session_id IS NOT NULL);
--rollback CREATE INDEX ix_fmi_subject_span ON failure_mode_instance USING btree (subject_span_id) WHERE (subject_span_id IS NOT NULL);
--rollback CREATE INDEX ix_intelligence_mode_audit_recorded_at ON intelligence_mode_audit USING btree (recorded_at);
--rollback CREATE INDEX ix_prior_contribution_feature ON prior_contribution USING btree (feature_key);
--rollback CREATE INDEX ix_prior_contribution_org ON prior_contribution USING btree (org_id);
--rollback CREATE UNIQUE INDEX ux_annotation_current ON annotation USING btree (annotator_kind, annotator_id, key, subject_kind, session_id, trace_id, span_id) NULLS NOT DISTINCT;
--rollback CREATE UNIQUE INDEX ux_fmi_subject ON failure_mode_instance USING btree (failure_mode_id, subject_kind, subject_session_id, subject_trace_id, subject_span_id) NULLS NOT DISTINCT;
