--liquibase formatted sql

--changeset evals:0048-prompt-library
-- A prompt is an authored object: a name per project, immutable numbered versions, and deployment labels that each
-- point at one version. latest is derived from MAX(version), never stored, so deleting the top version moves it with
-- no write. last_version is the highest number ever handed out and only grows, so a deleted number is never reused.
CREATE TABLE prompt (
    id text NOT NULL,
    project_id text NOT NULL,
    name text NOT NULL,
    type text NOT NULL,
    tags jsonb NOT NULL DEFAULT '[]'::jsonb,
    last_version integer NOT NULL DEFAULT 0,
    created_by text NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT prompt_pkey PRIMARY KEY (id),
    CONSTRAINT prompt_project_id_fkey FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT ux_prompt_project_name UNIQUE (project_id, name),
    CONSTRAINT ck_prompt_type CHECK (type IN ('text', 'chat')),
    CONSTRAINT ck_prompt_name CHECK (
        length(name) BETWEEN 1 AND 255
        AND name !~ '[|[:space:]]' AND name !~ '^/' AND name !~ '/$' AND name !~ '//'),
    CONSTRAINT ck_prompt_tags CHECK (jsonb_typeof(tags) = 'array'),
    CONSTRAINT ck_prompt_last_version CHECK (last_version >= 0)
);
CREATE INDEX ix_prompt_project_updated ON prompt (project_id, updated_at DESC);

CREATE TABLE prompt_version (
    id text NOT NULL,
    project_id text NOT NULL,
    prompt_id text NOT NULL,
    version integer NOT NULL,
    body jsonb NOT NULL,
    config jsonb NOT NULL DEFAULT '{}'::jsonb,
    commit_message text,
    created_by text NOT NULL,
    created_via text NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT prompt_version_pkey PRIMARY KEY (id),
    CONSTRAINT prompt_version_project_id_fkey FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT prompt_version_prompt_id_fkey FOREIGN KEY (prompt_id) REFERENCES prompt(id) ON DELETE CASCADE,
    CONSTRAINT ux_prompt_version UNIQUE (prompt_id, version),
    CONSTRAINT ck_prompt_version_positive CHECK (version > 0),
    CONSTRAINT ck_prompt_version_commit CHECK (commit_message IS NULL OR length(commit_message) <= 500),
    CONSTRAINT ck_prompt_version_via CHECK (created_via IN ('ui', 'api')),
    CONSTRAINT ck_prompt_version_config CHECK (jsonb_typeof(config) = 'object')
);
CREATE INDEX ix_prompt_version_lookup ON prompt_version (project_id, prompt_id, version DESC);

CREATE TABLE prompt_label (
    project_id text NOT NULL,
    prompt_id text NOT NULL,
    label text NOT NULL,
    version_id text NOT NULL,
    updated_by text NOT NULL,
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT prompt_label_pkey PRIMARY KEY (prompt_id, label),
    CONSTRAINT prompt_label_project_id_fkey FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT prompt_label_prompt_id_fkey FOREIGN KEY (prompt_id) REFERENCES prompt(id) ON DELETE CASCADE,
    CONSTRAINT prompt_label_version_id_fkey FOREIGN KEY (version_id) REFERENCES prompt_version(id) ON DELETE CASCADE,
    CONSTRAINT ck_prompt_label_name CHECK (label ~ '^[a-z0-9_.-]{1,36}$' AND label <> 'latest')
);
CREATE INDEX ix_prompt_label_version ON prompt_label (version_id);
--rollback DROP TABLE prompt_label; DROP TABLE prompt_version; DROP TABLE prompt;
