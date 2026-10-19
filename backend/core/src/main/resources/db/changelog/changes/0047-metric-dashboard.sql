--liquibase formatted sql

--changeset evals:0047-metric-dashboard
-- One row per project dashboard. Widgets live inline in `definition` (version 1); a dashboard is read
-- and written whole. ISO-8601 text clocks like every governance table.
CREATE TABLE metric_dashboard (
    id          text PRIMARY KEY,
    project_id  text NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name        text NOT NULL,
    description text NOT NULL DEFAULT '',
    definition  jsonb NOT NULL,
    created_by  text,
    created_at  text NOT NULL,
    updated_at  text NOT NULL
);
CREATE UNIQUE INDEX ux_metric_dashboard_name ON metric_dashboard (project_id, name);
COMMENT ON COLUMN metric_dashboard.definition IS 'MetricDashboardDefinition JSON, version 1: widgets inline, validated against the metrics catalog on every write';
--rollback DROP TABLE metric_dashboard;
