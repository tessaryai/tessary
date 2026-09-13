--liquibase formatted sql

--changeset evals:0005-telemetry-ping-seq
-- The heartbeat's ping_seq: home.tessary.ai's POST /v1/ping requires a counter that rises by one on
-- every ping an install sends, so home can tell a missed ping from a restart and difference two pings
-- over the interval between them.
--
-- On the singleton telemetry_install row rather than in memory, because it must keep rising across
-- restarts and across replicas sharing one database; InstallIdRepository#nextPingSeq increments it in a
-- single UPDATE, so two replicas pinging at once still get distinct values. Starts at 0, which the
-- first ping sends.
ALTER TABLE telemetry_install ADD COLUMN ping_seq bigint NOT NULL DEFAULT 0;

--rollback ALTER TABLE telemetry_install DROP COLUMN ping_seq;
