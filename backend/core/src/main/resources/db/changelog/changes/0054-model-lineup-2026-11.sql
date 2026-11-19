--liquibase formatted sql

--changeset evals:0054-model-lineup-2026-11
-- A lane pinned to a model the catalog no longer offers reads as unset and falls to the first provider the org
-- holds a key for, not to the same provider, so a cheap triage pin could land on a model several times its price.
-- Carry each pin to its successor on the same route instead.
UPDATE project_model_setting SET model_key = 'ANTHROPIC:claude-haiku-5-5' WHERE model_key = 'ANTHROPIC:claude-haiku-4-5';
UPDATE project_model_setting SET model_key = 'anthropic.claude-haiku-5-5' WHERE model_key = 'anthropic.claude-haiku-4-5';
UPDATE project_model_setting SET model_key = 'OPENAI:gpt-6.1-sol' WHERE model_key IN ('OPENAI:gpt-6-sol', 'OPENAI:gpt-5.6');
UPDATE project_model_setting SET model_key = 'openai.gpt-6.1-sol' WHERE model_key = 'openai.gpt-5.6-terra';
UPDATE project_model_setting SET model_key = 'openai.gpt-6-luna' WHERE model_key = 'openai.gpt-5.6-luna';
UPDATE project_model_setting SET model_key = 'OPENROUTER:openai/gpt-6.1-sol' WHERE model_key = 'OPENROUTER:openai/gpt-6-sol';
UPDATE project_model_setting SET model_key = 'GROK:grok-4.7' WHERE model_key = 'GROK:grok-4.6';
UPDATE project_model_setting SET model_key = 'GEMINI:gemini-4-hydrogen' WHERE model_key = 'GEMINI:gemini-3.1-pro-preview';
UPDATE project_model_setting SET model_key = 'GEMINI:gemini-3.8-flash' WHERE model_key = 'GEMINI:gemini-3.7-flash';
UPDATE project_model_setting SET model_key = 'MOONSHOT:kimi-k3.1-pro' WHERE model_key = 'MOONSHOT:kimi-k2.6';

--rollback UPDATE project_model_setting SET model_key = 'ANTHROPIC:claude-haiku-4-5' WHERE model_key = 'ANTHROPIC:claude-haiku-5-5';
--rollback UPDATE project_model_setting SET model_key = 'anthropic.claude-haiku-4-5' WHERE model_key = 'anthropic.claude-haiku-5-5';
--rollback UPDATE project_model_setting SET model_key = 'OPENAI:gpt-6-sol' WHERE model_key = 'OPENAI:gpt-6.1-sol';
--rollback UPDATE project_model_setting SET model_key = 'openai.gpt-5.6-terra' WHERE model_key = 'openai.gpt-6.1-sol';
--rollback UPDATE project_model_setting SET model_key = 'openai.gpt-5.6-luna' WHERE model_key = 'openai.gpt-6-luna';
--rollback UPDATE project_model_setting SET model_key = 'OPENROUTER:openai/gpt-6-sol' WHERE model_key = 'OPENROUTER:openai/gpt-6.1-sol';
--rollback UPDATE project_model_setting SET model_key = 'GROK:grok-4.6' WHERE model_key = 'GROK:grok-4.7';
--rollback UPDATE project_model_setting SET model_key = 'GEMINI:gemini-3.1-pro-preview' WHERE model_key = 'GEMINI:gemini-4-hydrogen';
--rollback UPDATE project_model_setting SET model_key = 'GEMINI:gemini-3.7-flash' WHERE model_key = 'GEMINI:gemini-3.8-flash';
--rollback UPDATE project_model_setting SET model_key = 'MOONSHOT:kimi-k2.6' WHERE model_key = 'MOONSHOT:kimi-k3.1-pro';
