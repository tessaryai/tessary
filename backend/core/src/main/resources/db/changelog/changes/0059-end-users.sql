--liquibase formatted sql

--changeset evals:0059-end-users
-- The Users view keeps its own facts, because traces age out and the view reads up to the history horizon. A user ID is
-- stored only as user_key: the first 16 bytes of HMAC-SHA-256 over the trimmed ID with the project's salt; a trace with
-- no user ID counts under the all-zero key. The readable text is only in end_user.id_text. Days are UTC day numbers,
-- floor(epoch seconds / 86400). A route is a (trace name, call site) pair and a version a project_version_id, each given
-- a small number per project, and a NULL name or version is a route or version of its own. The tables reference each
-- other by key only: a day is replaced whole, the horizon sweep and an erasure delete by key, and none waits on another.
CREATE TABLE end_user_project (
    project_id             text PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    salt                   bytea NOT NULL,
    history_start_day      integer,
    hits_watermark         timestamptz,
    fold_event_watermark   timestamptz,
    fold_rollup_watermark  timestamptz,
    page_state_memory      jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_end_user_project_salt CHECK (octet_length(salt) = 32),
    CONSTRAINT ck_end_user_project_memory CHECK (jsonb_typeof(page_state_memory) = 'array')
);

CREATE TABLE end_user_route (
    project_id       text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    route_no         integer NOT NULL,
    trace_name       text,
    call_site_id     text NOT NULL,
    route_class      text,
    class_since_day  integer,
    statistics       jsonb NOT NULL DEFAULT '{}'::jsonb,
    near_cut         boolean NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, route_no),
    CONSTRAINT ux_end_user_route_name UNIQUE NULLS NOT DISTINCT (project_id, trace_name, call_site_id),
    CONSTRAINT ck_end_user_route_class CHECK (route_class IN ('A', 'A2', 'S', 'M', 'B', 'C'))
);

-- One row per user key, day, route and version. session_hashes keeps the 32 smallest session hashes and job_keys at
-- most 32 crc32 values of input_preview joined with the 15-minute slot. hour_mask holds 24 bits, slot_mask 96.
CREATE TABLE end_user_day (
    project_id          text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_key            bytea NOT NULL,
    day                 integer NOT NULL,
    route_no            integer NOT NULL,
    version_no          integer NOT NULL,
    traces              integer NOT NULL,
    session_hashes      integer[] NOT NULL DEFAULT '{}',
    hour_mask           integer NOT NULL,
    slot_mask           bit(96) NOT NULL,
    job_keys            bigint[] NOT NULL DEFAULT '{}',
    traces_with_input   integer NOT NULL DEFAULT 0,
    traces_with_parent  integer NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id, user_key, day, route_no, version_no),
    CONSTRAINT ck_end_user_day_key CHECK (octet_length(user_key) = 16),
    CONSTRAINT ck_end_user_day_hour_mask CHECK (hour_mask BETWEEN 0 AND 16777215),
    CONSTRAINT ck_end_user_day_session_hashes CHECK (cardinality(session_hashes) <= 32),
    CONSTRAINT ck_end_user_day_job_keys CHECK (cardinality(job_keys) <= 32)
);
CREATE INDEX ix_end_user_day_project_day ON end_user_day (project_id, day);

CREATE TABLE end_user_day_null (
    project_id         text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    day                integer NOT NULL,
    route_no           integer NOT NULL,
    version_no         integer NOT NULL,
    traces             integer NOT NULL,
    traces_no_user_id  integer NOT NULL,
    PRIMARY KEY (project_id, day, route_no, version_no),
    CONSTRAINT ck_end_user_day_null_traces CHECK (traces_no_user_id BETWEEN 0 AND traces)
);

-- project_version has no deploy time, so the first trace day of a version stands for its deploy day.
CREATE TABLE end_user_version (
    project_id          text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    version_no          integer NOT NULL,
    project_version_id  text,
    first_trace_day     integer NOT NULL,
    PRIMARY KEY (project_id, version_no),
    CONSTRAINT ux_end_user_version_id UNIQUE NULLS NOT DISTINCT (project_id, project_version_id)
);

-- A session or thread in which from_key's last trace is before to_key's first trace.
CREATE TABLE end_user_alias_edge (
    project_id  text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    from_key    bytea NOT NULL,
    to_key      bytea NOT NULL,
    via         text NOT NULL,
    day         integer NOT NULL,
    PRIMARY KEY (project_id, from_key, to_key, via, day),
    CONSTRAINT ck_end_user_alias_edge_via CHECK (via IN ('session', 'thread'))
);

-- Every evidence role is kept; which roles count as a hit is the detector's rule, applied when read.
CREATE TABLE end_user_hit (
    project_id  text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    finding_id  text NOT NULL,
    role        text NOT NULL,
    user_key    bytea NOT NULL,
    day         integer NOT NULL,
    route_no    integer NOT NULL,
    refs        integer NOT NULL,
    source      text NOT NULL,
    PRIMARY KEY (project_id, finding_id, role, user_key, day, route_no),
    CONSTRAINT ck_end_user_hit_role CHECK (role IN ('exemplar', 'member', 'baseline', 'witness', 'changepoint')),
    CONSTRAINT ck_end_user_hit_source CHECK (source IN ('evidence', 'detection')),
    CONSTRAINT ck_end_user_hit_refs CHECK (refs > 0)
);
CREATE INDEX ix_end_user_hit_project_key ON end_user_hit (project_id, user_key);

CREATE TABLE end_user_hit_summary (
    project_id         text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    finding_id         text NOT NULL,
    refs_total         integer NOT NULL,
    refs_no_user_id    integer NOT NULL,
    session_only_refs  integer NOT NULL,
    complete           boolean NOT NULL,
    hit_rule           text NOT NULL,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, finding_id)
);

-- shape_signature and cluster_hash are written with id_text, so they stay when the horizon sweep clears the text.
-- The judge columns are NULL until the first judge run; stage is NULL for held and set-aside IDs.
CREATE TABLE end_user (
    project_id         text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_key           bytea NOT NULL,
    id_text            text,
    shape_signature    text NOT NULL,
    cluster_hash       bytea,
    account_label      text,
    first_seen_day     integer NOT NULL,
    last_seen_day      integer NOT NULL,
    state              text,
    substate           text,
    grade              text,
    p_stop             double precision,
    p_nominal          double precision,
    days_silent        integer,
    set_aside_reason   text,
    stage              text,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_key),
    CONSTRAINT ck_end_user_key CHECK (octet_length(user_key) = 16),
    CONSTRAINT ck_end_user_days CHECK (first_seen_day <= last_seen_day),
    CONSTRAINT ck_end_user_stage CHECK (stage IN ('active', 'silent', 'stopped'))
);
CREATE INDEX ix_end_user_stage_silent ON end_user (project_id, stage, days_silent DESC, user_key);

CREATE TABLE end_user_silence_event (
    project_id  text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    scope       text NOT NULL,
    start_day   integer NOT NULL,
    end_day     integer,
    state       text NOT NULL,
    members     integer NOT NULL,
    i           double precision,
    i0          double precision,
    rho         double precision,
    PRIMARY KEY (project_id, scope, start_day)
);

-- One judge run per closed day. Codes and numbers only; the frontend words them. NULL means not computed.
CREATE TABLE end_user_snapshot (
    project_id          text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    day                 integer NOT NULL,
    logic_version       text NOT NULL,
    page_state          text NOT NULL,
    page_state_reason   text,
    reason_numbers      jsonb NOT NULL DEFAULT '{}'::jsonb,
    banners             jsonb NOT NULL DEFAULT '[]'::jsonb,
    limits              jsonb NOT NULL DEFAULT '[]'::jsonb,
    trust               jsonb,
    stage_counts        jsonb,
    set_aside           jsonb,
    stop_stage_state    text,
    stop_stage_numbers  jsonb,
    calibration         jsonb,
    h                   integer,
    f                   integer,
    created_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, day)
);

-- An issue is a case lineage, as of the snapshot day in day.
CREATE TABLE end_user_issue (
    project_id    text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    lineage_key   text NOT NULL,
    day           integer NOT NULL,
    case_id       text,
    finding_ids   text[] NOT NULL DEFAULT '{}',
    rank          integer,
    user_ids_hit  integer NOT NULL,
    compared      integer,
    lost          double precision,
    low           double precision,
    high          double precision,
    verdict       text NOT NULL,
    shown         boolean NOT NULL,
    group_id      text,
    curve         jsonb,
    diagnostics   jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (project_id, lineage_key),
    CONSTRAINT ck_end_user_issue_verdict CHECK (verdict IN ('page_refused', 'hits_not_complete', 'history_too_short',
        'cannot_tell_yet', 'cannot_measure_yet', 'too_few_ids', 'reaches_nearly_all', 'not_comparable', 'always_present',
        'few_accounts', 'evidence_of_loss', 'moved_not_added', 'no_measurable_loss', 'not_enough_evidence',
        'not_applicable'))
);

CREATE TABLE end_user_issue_hit (
    project_id     text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    lineage_key    text NOT NULL,
    user_key       bytea NOT NULL,
    first_hit_day  integer NOT NULL,
    in_estimate    boolean NOT NULL,
    PRIMARY KEY (project_id, lineage_key, user_key)
);

CREATE TABLE end_user_fold_day (
    project_id  text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    day         integer NOT NULL,
    folded_at   timestamptz NOT NULL,
    traces      integer NOT NULL,
    gap         boolean NOT NULL DEFAULT false,
    outage      boolean NOT NULL DEFAULT false,
    PRIMARY KEY (project_id, day)
);
--rollback DROP TABLE end_user_fold_day;
--rollback DROP TABLE end_user_issue_hit;
--rollback DROP TABLE end_user_issue;
--rollback DROP TABLE end_user_snapshot;
--rollback DROP TABLE end_user_silence_event;
--rollback DROP TABLE end_user;
--rollback DROP TABLE end_user_hit_summary;
--rollback DROP TABLE end_user_hit;
--rollback DROP TABLE end_user_alias_edge;
--rollback DROP TABLE end_user_version;
--rollback DROP TABLE end_user_day_null;
--rollback DROP TABLE end_user_day;
--rollback DROP TABLE end_user_route;
--rollback DROP TABLE end_user_project;

--changeset evals:0059-end-user-job-kinds
-- end_user_hits sweeps a project's new evidence once an hour (dedupe_key <project>:<hour>); end_users runs the daily
-- stages for a closed UTC day (dedupe_key <project>:<day>). Both are scheduled with ON CONFLICT DO NOTHING, so each
-- index holds a key for good: a done day or hour is never queued again.
ALTER TABLE job DROP CONSTRAINT job_kind_check;
ALTER TABLE job ADD CONSTRAINT job_kind_check
    CHECK ((kind = ANY (ARRAY['classifier'::text, 'pull'::text, 'usage_rollup'::text, 'rca'::text,
        'behavior_profile'::text, 'triage'::text, 'sop_compile'::text, 'project_delete'::text, 'end_users'::text,
        'end_user_hits'::text])));
CREATE UNIQUE INDEX ux_job_end_users ON job (dedupe_key) WHERE kind = 'end_users';
CREATE UNIQUE INDEX ux_job_end_user_hits ON job (dedupe_key) WHERE kind = 'end_user_hits';
--rollback DELETE FROM job WHERE kind IN ('end_users', 'end_user_hits');
--rollback DROP INDEX ux_job_end_user_hits;
--rollback DROP INDEX ux_job_end_users;
--rollback ALTER TABLE job DROP CONSTRAINT job_kind_check;
--rollback ALTER TABLE job ADD CONSTRAINT job_kind_check CHECK ((kind = ANY (ARRAY['classifier'::text, 'pull'::text, 'usage_rollup'::text, 'rca'::text, 'behavior_profile'::text, 'triage'::text, 'sop_compile'::text, 'project_delete'::text])));

--changeset evals:0059-finding-evidence-project-created runInTransaction:false
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'ix_finding_evidence_project_created' AND NOT i.indisvalid
-- The hourly hit sweep reads a project's evidence from its watermark on, and no evidence index starts with
-- (project_id, created_at). CONCURRENTLY keeps the detectors writing while it builds; a build that failed leaves an
-- INVALID index that IF NOT EXISTS would skip, so the precondition halts on one: drop it and build again.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_finding_evidence_project_created ON finding_evidence (project_id, created_at, id);
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_finding_evidence_project_created;
