--liquibase formatted sql

--changeset evals:0050-job-queued-at
-- When a triage or RCA job was last asked for: set when it is enqueued and when a press runs it again, and left
-- alone by a claim or a release, so a run that bounces off a full launcher keeps its place on the queued clock.
-- NULL on older rows and other kinds; readers fall back to created_at.
ALTER TABLE job ADD COLUMN queued_at text;
--rollback ALTER TABLE job DROP COLUMN queued_at;
