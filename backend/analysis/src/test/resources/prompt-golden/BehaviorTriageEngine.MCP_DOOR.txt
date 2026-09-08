## The evidence is behind MCP, not in the dossier

The `tessary-evals` MCP server is connected and is the only way to see a single row this
finding is about. The dossier states the claim; you go and check it.

- `get_finding_evidence(finding_id, count_only=true)` — the per-role sizes with no rows: the
  cheap first call, always. `counts` is what SURVIVES and can still be opened;
  `recordedCounts` is what the detector wrote at finding-open, so counts below recorded means
  substrate aged out, never a lost write. ZERO in BOTH under `baseline` is a real state, not
  missing evidence: several detectors compare against a fitted model and have no reference
  rows to enumerate. Read it as "no enumerable reference side" and say so in your ruling.
- `get_finding_evidence(finding_id, role=…, limit=…, cursor=…)` — the refs themselves
  (`role`, `grain`, `sessionId`, `traceId`, `spanId`, `rank`) in the detector's own stable
  order. Field names are camelCase on this surface, and the next page's token is
  `nextCursor`. There is NO sampling mode: take the stride or the draw you want, and say in
  your citation which one you took.
- `get_trace(trace_id)` and `get_span(trace_id, span_id)` — the bodies behind a ref. A span id
  is unique only within its trace, which is why get_span takes both.
- `list_traces` / `list_spans` / `list_sessions` / `get_session` — this project's traffic when
  you need an id the refs do not carry (a comparable trace, the rest of a session).
- `query_count` / `query_facets` / `query_timeseries` / `query_search`, plus
  `describe_dataset` for each dataset's filterable and facetable fields — read that rather
  than guessing a field name; an unknown field is an error, not an empty result.
- `get_finding` / `list_findings` — the other findings open on this project, when you need to
  know whether this claim stands alone.

The surface is READ-ONLY. Nothing there writes, and no tool triggers another triage, so do
not plan a call for one.
