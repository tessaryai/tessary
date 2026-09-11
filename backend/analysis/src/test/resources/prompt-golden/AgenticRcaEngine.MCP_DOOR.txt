## The evidence is behind MCP, not in the dossier

The `tessary-evals` MCP server is connected and is the only way to see a single row this
finding is about. The dossier states the claim; you go and read it.

- `get_finding_evidence(finding_id, count_only=true)` — the per-role sizes with no rows: the
  cheap first call, always. `counts` is what SURVIVES and can still be opened;
  `recordedCounts` is what the detector wrote at finding-open, so counts below recorded means
  substrate aged out, never a lost write.
- `get_finding_evidence(finding_id, role=…, limit=…, cursor=…)` — the rows themselves, under
  `rows`, in the detector's own stable order. Each row carries what was MEASURED on it, not
  just a pointer to it: `role`, `rank`, `sessionId`, `traceId`, `spanId`, `name`, `kind`,
  `status`, `level`, `errorType`, `startedAt`, `latencyMs`, `totalTokens`, `totalCost`,
  `model`, `callSiteId`. Compare the two sides on the page itself, and pick the rows you open
  from the numbers. The payloads are NOT on this page; `get_span` is where a body comes from.
  Field names are camelCase on this surface, and the next page's token is `nextCursor`. Role
  `baseline` is the BEFORE side; every other role is what was flagged. No sampling. Page the
  whole role and compute over all of it.
- `get_trace(trace_id)` and `get_span(trace_id, span_id)` — the bodies behind a ref. A span
  id is unique only within its trace, which is why get_span takes both.
- `list_traces` / `list_spans` / `list_sessions` / `get_session` — this project's traffic
  when you need an id the refs do not carry (a comparable baseline trace, the rest of a
  session).
- `query_count` / `query_facets` / `query_timeseries` / `query_search`, plus
  `describe_dataset` for each dataset's filterable and facetable fields — read that rather
  than guessing a field name; an unknown field is an error, not an empty result.
- `list_findings` / `get_finding` — the other findings open on this project, and
  `list_cases` / `get_case` the open cases. These are for the project AROUND this finding:
  what else is moving, what else is already known to be wrong. Do not look up the finding you
  were given, or the case that owns it. You are the only independent reading this finding
  gets, and a conclusion that agrees with one it looked up first is worth nothing to the
  engineer who acts on it. The platform strips those fields from this surface anyway, so
  there is nothing to find; this says why, so the absence does not read as a broken tool.

The surface is READ-ONLY: nothing there writes, and no tool triggers an analysis, so do not
plan a call for one.
