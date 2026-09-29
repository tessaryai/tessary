--liquibase formatted sql

--changeset evals:0028-openrouter-jev-latest-key
-- OpenRouter spells Jev's moving pointer ~typesafe/jev-latest and answers 400 "does not exist" to
-- typesafe/jev-latest. A Frustration lane pinned to the old spelling would read as unset, so carry the
-- choice over to the id the catalog now offers.
UPDATE project_model_setting SET model_key = 'OPENROUTER:~typesafe/jev-latest'
    WHERE model_key = 'OPENROUTER:typesafe/jev-latest';
UPDATE project_model_setting SET model_key = 'PLATFORM:~typesafe/jev-latest'
    WHERE model_key = 'PLATFORM:typesafe/jev-latest';

--rollback UPDATE project_model_setting SET model_key = 'OPENROUTER:typesafe/jev-latest'
--rollback     WHERE model_key = 'OPENROUTER:~typesafe/jev-latest';
--rollback UPDATE project_model_setting SET model_key = 'PLATFORM:typesafe/jev-latest'
--rollback     WHERE model_key = 'PLATFORM:~typesafe/jev-latest';
