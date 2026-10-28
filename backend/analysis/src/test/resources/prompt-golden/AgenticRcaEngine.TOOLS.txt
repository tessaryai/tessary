## The evidence is behind MCP, not in the dossier

The `tessary-evals` MCP server is connected and is the only way to see a single row this
finding is about. The dossier states the claim; you go and read it.

- `get_finding_evidence(finding_id, count_only=true)` — the per-role sizes with no rows: the
  cheap first call, always. `counts` is what SURVIVES and can still be opened;
  `recordedCounts` is what the detector wrote at finding-open, so counts below recorded means
  substrate aged out, never a lost write.
- `export_finding_evidence(finding_id, role=…, text_chars=…, cursor=…)` — every row with its
  input and output text and every attribute, masked, saved as one file under `checks/mcp/`;
  it pages on `nextCursor`.
- `get_finding_evidence(finding_id, role=…, limit=…, cursor=…)` — the rows themselves, under
  `rows`, in the detector's own stable order. Each row carries what was MEASURED on it: `role`,
  `rank`, `sessionId`, `traceId`, `spanId`, `name`, `kind`, `status`, `level`, `errorType`,
  `startedAt`, `latencyMs`, `totalTokens`, `inputTokens`, `outputTokens`, `cacheReadTokens`,
  `cacheWriteTokens`, `reasoningTokens`, `totalCost`, `models`, `projectVersionId`, `userId`,
  `traceIndex`, `sessionTraces`, `inputBytes`, `outputBytes`, `payloadAvailable`, `callSiteId`
  and a short `attributes` map. Bodies come from `get_span`. Field names are camelCase, and the
  next page's token is `nextCursor`. `method.md` says what each role holds. A row with a
  `sessionId` and no `traceId` is a whole conversation: read it with `get_conversation`.
- `get_trace(trace_id)` and `get_span(trace_id, span_id)` — the bodies behind a ref. A span
  id is unique only within its trace, which is why get_span takes both.
- `list_traces` / `list_spans` / `list_sessions` / `get_session` — this project's traffic
  when you need an id the refs do not carry (the rest of a session).
- `query_count` / `query_facets` / `query_timeseries` / `query_search`, plus
  `describe_dataset` for each dataset's filterable and facetable fields — read that rather
  than guessing a field name; an unknown field is an error, not an empty result.
- `list_findings` / `get_finding` — the other findings open on this project, and
  `list_cases` / `get_case` the open cases: what else is moving, what else is already known to
  be wrong. Do not look up the finding you were given, or the case that owns it: the platform
  strips those from this surface, and this says why, so the absence does not read as a broken
  tool.

The surface is READ-ONLY: nothing there writes, and no tool triggers an analysis, so do not
plan a call for one.
