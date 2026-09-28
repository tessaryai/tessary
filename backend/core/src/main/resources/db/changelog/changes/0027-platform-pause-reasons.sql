--liquibase formatted sql

--changeset evals:0027-platform-pause-reasons
-- Two more reasons a classifier that calls a decision provider can pause, both for a lane that runs on
-- the deployment's own provider rather than the org's key: no_credit (the org has used all of its
-- credit for that provider) and platform_unavailable (the provider refused the deployment's key, which
-- is not the org's to fix). Neither is written by an install that supplies no such provider.
ALTER TABLE classifier DROP CONSTRAINT classifier_paused_reason_check;
ALTER TABLE classifier ADD CONSTRAINT classifier_paused_reason_check
    CHECK (paused_reason IS NULL
        OR paused_reason IN ('provider_rejected', 'no_provider', 'no_credit', 'platform_unavailable'));

--rollback UPDATE classifier SET paused_reason = NULL, paused_at = NULL
--rollback  WHERE paused_reason IN ('no_credit', 'platform_unavailable');
--rollback ALTER TABLE classifier DROP CONSTRAINT classifier_paused_reason_check;
--rollback ALTER TABLE classifier ADD CONSTRAINT classifier_paused_reason_check
--rollback     CHECK (paused_reason IS NULL OR paused_reason IN ('provider_rejected', 'no_provider'));
