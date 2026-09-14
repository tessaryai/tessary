--liquibase formatted sql

--changeset evals:0008-telemetry-instance
-- home.tessary.ai's ping contract names the identity instance_id. The table, its column and its three
-- constraints follow, so the row that answers the contract uses the contract's word. Renaming a primary-key
-- or unique constraint renames its index with it, and the stored UUID and ping_seq do not move.
ALTER TABLE telemetry_install RENAME TO telemetry_instance;
ALTER TABLE telemetry_instance RENAME COLUMN install_id TO instance_id;
ALTER TABLE telemetry_instance RENAME CONSTRAINT telemetry_install_pkey TO telemetry_instance_pkey;
ALTER TABLE telemetry_instance RENAME CONSTRAINT telemetry_install_install_id_key TO telemetry_instance_instance_id_key;
ALTER TABLE telemetry_instance RENAME CONSTRAINT telemetry_install_singleton_check TO telemetry_instance_singleton_check;

--rollback ALTER TABLE telemetry_instance RENAME CONSTRAINT telemetry_instance_singleton_check TO telemetry_install_singleton_check;
--rollback ALTER TABLE telemetry_instance RENAME CONSTRAINT telemetry_instance_instance_id_key TO telemetry_install_install_id_key;
--rollback ALTER TABLE telemetry_instance RENAME CONSTRAINT telemetry_instance_pkey TO telemetry_install_pkey;
--rollback ALTER TABLE telemetry_instance RENAME COLUMN instance_id TO install_id;
--rollback ALTER TABLE telemetry_instance RENAME TO telemetry_install;
