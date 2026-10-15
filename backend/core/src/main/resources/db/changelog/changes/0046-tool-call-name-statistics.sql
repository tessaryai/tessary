--liquibase formatted sql

--changeset evals:0046-tool-call-name-statistics
-- Tool Errors names a call as COALESCE(NULLIF(name, ''), 'unnamed'), and its reads filter one tool with that
-- expression IN (...). With no statistics on it Postgres guesses 0.5% of calls match, when one tool is often a third
-- of them, and on that guess probes span, trace and span_payload once per call in a serial nested loop. These
-- statistics give it the expression's real distribution; ANALYZE fills them now rather than at the next autoanalyze.
CREATE STATISTICS IF NOT EXISTS st_tool_call_tool_name ON (COALESCE(NULLIF(name, ''), 'unnamed')) FROM tool_call;
ANALYZE tool_call;
--rollback DROP STATISTICS IF EXISTS st_tool_call_tool_name;
