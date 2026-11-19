--liquibase formatted sql

--changeset evals:0054-model-lineup-2026-11
-- A lane pinned to a model the catalog no longer offers reads as unset and falls to the first provider the org
-- holds a key for, not to the same provider, so a cheap triage pin could land on a model several times its price.
-- Carry each pin to its successor on the same route instead. A join rather than one literal per UPDATE, since a
-- quoted literal beside "model_key =" reads to the secret scanner as a leaked key.
UPDATE project_model_setting AS s SET model_key = m.successor
FROM (VALUES
    ('ANTHROPIC:claude-haiku-4-5', 'ANTHROPIC:claude-haiku-5-5'),
    ('anthropic.claude-haiku-4-5', 'anthropic.claude-haiku-5-5'),
    ('OPENAI:gpt-6-sol', 'OPENAI:gpt-6.1-sol'),
    ('OPENAI:gpt-5.6', 'OPENAI:gpt-6.1-sol'),
    ('openai.gpt-5.6-terra', 'openai.gpt-6.1-sol'),
    ('openai.gpt-5.6-luna', 'openai.gpt-6-luna'),
    ('OPENROUTER:openai/gpt-6-sol', 'OPENROUTER:openai/gpt-6.1-sol'),
    ('GROK:grok-4.6', 'GROK:grok-4.7'),
    ('GEMINI:gemini-3.1-pro-preview', 'GEMINI:gemini-4-hydrogen'),
    ('GEMINI:gemini-3.7-flash', 'GEMINI:gemini-3.8-flash'),
    ('MOONSHOT:kimi-k2.6', 'MOONSHOT:kimi-k3.1-pro')
) AS m(retired, successor)
WHERE s.model_key = m.retired;

--rollback UPDATE project_model_setting AS s SET model_key = m.retired
--rollback FROM (VALUES
--rollback     ('ANTHROPIC:claude-haiku-4-5', 'ANTHROPIC:claude-haiku-5-5'),
--rollback     ('anthropic.claude-haiku-4-5', 'anthropic.claude-haiku-5-5'),
--rollback     ('OPENAI:gpt-6-sol', 'OPENAI:gpt-6.1-sol'),
--rollback     ('openai.gpt-5.6-terra', 'openai.gpt-6.1-sol'),
--rollback     ('openai.gpt-5.6-luna', 'openai.gpt-6-luna'),
--rollback     ('OPENROUTER:openai/gpt-6-sol', 'OPENROUTER:openai/gpt-6.1-sol'),
--rollback     ('GROK:grok-4.6', 'GROK:grok-4.7'),
--rollback     ('GEMINI:gemini-3.1-pro-preview', 'GEMINI:gemini-4-hydrogen'),
--rollback     ('GEMINI:gemini-3.7-flash', 'GEMINI:gemini-3.8-flash'),
--rollback     ('MOONSHOT:kimi-k2.6', 'MOONSHOT:kimi-k3.1-pro')
--rollback ) AS m(retired, successor)
--rollback WHERE s.model_key = m.successor;
