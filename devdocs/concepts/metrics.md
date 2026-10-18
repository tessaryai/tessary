# Metrics: counts over traces, LLM calls, tool calls and cases

Metrics is the page that counts what a project's agents did over a time window: spend, latency,
traces, LLM calls, tool calls and cases. Every figure on it is a saved query against one endpoint,
`POST .../metrics/query`, which serves the page, the Triage pulse strip and query-scoped API keys
with the same JSON, so a number on the page and a number a script fetches cannot disagree.

Metrics replaced the Vitals page and `GET .../vitals` on 2026-10-18. What changed for a reader is in
[What changed from Vitals](#what-changed-from-vitals).

Code: `backend/surfaces/src/main/java/ai/tessary/metrics/` (query engine and catalog) and
`metrics/dashboard/` (dashboards); `frontend/src/views/metrics/`. Schema:
[`data-model.md`](../reference/data-model.md) § *Operational envelope (governance)*,
`metric_dashboard`. Config keys: [`config-keys.md`](../reference/config-keys.md) §
`tessary.metrics.*`. Chart tokens: [`frontend/DESIGN_SYSTEM.md`](../../frontend/DESIGN_SYSTEM.md) §
*Charts*.

## What Metrics is not

- **Not a detector.** Metrics counts and judges nothing. A delta on a number tile is printed
  without color, and no figure on the page creates a finding. Deciding that a number moved is the
  job of the classifiers: [metric drift](./metric-drift.md) for duration and cost,
  [tool error](./tool-error.md) for failure rates.
- **Not a trace explorer.** A row that names a call site opens Traces filtered to it and to the
  widget's window. Metrics never lists individual traces.
- **Not a free-form query language.** A measure carries its aggregation in its name
  (`latency_p95_ms`, `cost_usd`). There is no aggregation picker, because a picker lets a reader
  sum a percentile or average a count, and each measure owns the gate and the companions that
  keep it honest.

## A widget is a saved query

All routes sit under `/api/orgs/{orgSlug}/projects/{projectSlug}/metrics` and answer the
`ApiResponse` envelope.

| Method and path | What it does | Who may call it |
|---|---|---|
| `GET /catalog` | Views, their dimensions and measures (with unit, gap fill and companions), granularities, limits | `ORG_VIEW`; an API key scoped `QUERY` or `ADMIN` |
| `POST /query` | One aggregate query | same |
| `GET /dashboards` | `default` first, then the project's dashboards by name, with `can_manage` | same |
| `GET /dashboards/{id}` | One dashboard; `default` comes from code | same |
| `POST /dashboards` | Create; `clone_of` copies a dashboard, `default` included | `ORG_MANAGE`, signed-in session only |
| `PUT /dashboards/{id}` | Replace name, description and definition; `409 BUILT_IN_READ_ONLY` on `default` | same |
| `DELETE /dashboards/{id}` | Delete; `409 BUILT_IN_READ_ONLY` on `default` | same |

A `WRITE` key gets `403 METRICS.WRONG_KEY_SCOPE` on a read. Every key, `ADMIN` included, gets
`403 METRICS.SESSION_REQUIRED` on a write. The session check comes before the permission check on
purpose: a key context carries the `member` role, and members hold `ORG_MANAGE`, so the permission
alone would let a query key edit dashboards. `can_manage` on the dashboard reads is true only for
a signed-in session that holds `ORG_MANAGE`.

A query names a view, one to four of its measures, at most one dimension, up to three
`in`/`not_in` filters on its dimensions, a window and a granularity:

```json
{
  "view": "llm_calls",
  "measures": ["cost_usd"],
  "dimension": "call_site",
  "filters": [{ "field": "model", "op": "in", "values": ["gpt-4o"] }],
  "from": "2026-10-11T00:00:00Z",
  "to": "2026-10-18T00:00:00Z",
  "granularity": "auto",
  "top_n": 5,
  "compare": null
}
```

The answer holds one row per group and bucket. `values` carries the asked measures and, appended
by the server, their companions. `n` is the count of base rows in the group. `bucket` is null
without a time axis and `key` is null without a dimension. With `compare: "previous_window"` (only
without a time axis) the server runs the same statement over the window of the same length that
ends at `from` and returns it as `previous_rows`. View, measure, dimension and granularity names
resolve to catalog constants before any SQL is written; values, instants and limits are bound
parameters, so nothing from the request is concatenated into a statement.

A dashboard widget stores exactly this request without the window, and the page supplies the
window. The default dashboard's Spend tile sends the same bytes as the Triage pulse strip's spend
query.

## The view catalog

Four views ship. Each fixes its base table, its gate, its clock and the measures that are honest
over it. The gate (`project_id`, the soft-delete flag, and `kind = 'llm'` on `llm_calls`) is part
of the view, never a filter, so no request can produce a double-counted total.

| View | One row is | Reads | Clock |
|---|---|---|---|
| `traces` | a trace | `trace`, plus the root span through `ix_span_root` (`0045`) only when a root-reading measure is asked (the latency family, `latency_n`, `unterminated_traces`, `rootless_traces`) | `trace.started_at` |
| `llm_calls` | an LLM span | `span` through `ix_span_llm_usage` (`0042`), index only; joins `trace` only for a settled measure | `span.started_at` |
| `tool_calls` | a tool call | `tool_call` | `tool_call.started_at` |
| `cases` | a case | `eval_case` | `eval_case.opened_at` |

Dimensions:

| View | Dimensions |
|---|---|
| `traces` | `call_site`, `name`, `user`, `session` (the last three high cardinality), `outcome` (`error` when the trace has any error, else `ok`) |
| `llm_calls` | `call_site`, `model`, `cost_source` |
| `tool_calls` | `tool`, `error_type`, `scope`, `tool_type` |
| `cases` | `detector`, `call_site`, `state`, `resolution`, `disposition` |

Measures, with the companions the server appends whenever the measure is asked:

| View | Measure | Unit | Companions |
|---|---|---|---|
| `traces` | `traces`, `errored_traces` | count | |
| `traces` | `cost_usd`, `usd_per_trace` | usd | `unpriced_traces`, `unsettled_traces`, `priced_traces` |
| `traces` | `latency_p50_ms`, `latency_p95_ms`, `latency_p99_ms` | ms | `latency_n`, `unterminated_traces`, `rootless_traces` |
| `llm_calls` | `calls`, `spending_traces` | count | |
| `llm_calls` | `tokens` | tokens | `unsettled_calls` |
| `llm_calls` | `cost_usd`, `usd_per_trace` | usd | `unpriced_calls`, `unsettled_calls`, `priced_traces` |
| `tool_calls` | `calls`, `errored_calls`, `retries` | count | |
| `tool_calls` | `error_rate` | ratio | `errored_calls` |
| `tool_calls` | `latency_p50_ms`, `latency_p95_ms`, `latency_p99_ms` | ms | `latency_n` |
| `cases` | `cases`, `open_cases`, `resolved_cases` | count | |

Every companion is itself a measure of its view and can be asked on its own. The catalog endpoint
returns each view's description, which states its caveat in one sentence.

Not in v1: `detections`, `findings` and `spans` by kind as views; token splits, LLM latency and time
to first token on `llm_calls` (they need `ix_span_llm_usage` widened); an average latency; time to
resolve a case. Each would come back as a catalog entry, with its own companions.

## The data-honesty rules

These are the rules a reader can rely on for every figure. Each one is a property of the catalog,
so a new widget gets it without asking.

1. **A dollar figure says what it leaves out.** A span on a model with no price on file is unpriced,
   not free. `sum()` never sees a zero for it, and `unpriced_calls` (or `unpriced_traces`) travels
   beside every dollar measure. The page prints "14 unpriced calls not in this figure" under the
   number and a footer that names the total.
2. **Sums wait for the trace to settle.** Cost and token sums count settled traces only, through
   `FILTER (WHERE t.is_settled)` on the aggregate rather than a statement `WHERE`, so one scan
   gives the gated sum and the ungated count. `unsettled_calls` and `unsettled_traces` say how many
   are still arriving. A bucket whose traces are all unsettled reads 0, as an empty bucket does,
   and the companion says why. The settle protocol is in
   [substrate-model.md](./substrate-model.md).
3. **Tokens are counted on LLM spans only.** The `traces` view has no token measure, because a
   trace's `total_tokens` sums every span kind and a container span carries its subtree's total,
   which would count an agentic trace's tokens more than once. `llm_calls` excludes container
   spans by its gate.
4. **Latency is the root span's own interval.** A trace's latency is its first live parentless
   span's `ended_at - started_at`, the definition the detectors use, never the min/max envelope
   over every span (which read about 10 percent high at p95; [metric-drift.md](./metric-drift.md)
   §3.0). A trace whose root has not ended counts in `unterminated_traces` and stays out of the
   percentile; a trace with no root span counts in `rootless_traces`. The root is joined `LEFT`, so
   neither drops out of the `traces` count.
5. **A percentile comes with its family.** Asking for any of p50, p95 or p99 returns all three from
   one `percentile_cont(ARRAY[0.5, 0.95, 0.99])`: one sort per group, and a p95 is never shown
   without its median and its sample size (`latency_n`).
6. **Spend per trace divides by priced traces.** `usd_per_trace` is the settled sum over the number
   of settled traces that carry a price, so a fully unpriced trace cannot dilute it.
7. **A missing bucket is never a fake zero for a ratio.** Gaps are filled in Java from the window
   and the unit: 0 for counts and sums, null for percentiles and ratios, which the chart draws as a
   gap.
8. **The top N plus everything else adds up.** With a dimension, keys past `top_n` fold into one
   `__other__` group in SQL, so every measure of the fold, percentiles included, is exact, and
   `top_n.total_keys` says how many keys there were. The fold is labelled "Other (N more)".
9. **NULL group keys have a name.** Every column-backed dimension is
   `COALESCE(<column>, '__unattributed__') COLLATE "C"` (or its own fallback, such as `none` for
   `error_type` and `unresolved` for `resolution`), so a filter on `__unattributed__` matches the
   NULL rows and the key is labelled "Unattributed". `__unattributed__` is the sentinel the Triage
   page and `ix_trace_scope_settled_started` already share. A call site that a producer names
   `__other__` or `__unattributed__` folds into the reserved group.
10. **Every figure uses event time.** Windows and buckets read `started_at` (`opened_at` for cases)
    in UTC, never the time a row was written, following the two-clocks rule in
    [data-model.md](../reference/data-model.md).

Three caveats follow from the sources and are not bugs:

- **The `traces` view and the Traces filter count call sites differently.** The view groups a trace
  by its own `call_site_id`; the Traces list's call-site filter matches a trace when any of its spans
  carries the call site. The Traces count can be higher, by the traces whose only tagged span is not
  the trace's own.
- **A tool call with no start time falls outside every window.** `tool_call.started_at` is the
  clock, and the view's description says so.
- **The `tool_calls` error count is the narrow one.** `errored_calls` and `error_rate` read
  `tool_call.is_error`, the span-status rule. The Tool error classifier also counts a declared error
  in the result, an `error.type` attribute and a recorded exception, so its rate reads higher. The
  classifier is the judgment; see [tool-error.md](./tool-error.md) §8.4.

The `cases` view reads a text clock. `eval_case.opened_at` is ISO-8601 text, so the window bounds
are bound as text with nine fractional digits, which sorts exactly against the values
`Instant.toString()` writes, and the bucket casts to `timestamptz` in the `SELECT` only.

## Limits

Each limit answers a named error rather than clamping silently, so an API caller learns it.

| Limit | Value | Error |
|---|---|---|
| Window | 90 days, `from` before `to` | `422 METRICS.WINDOW_TOO_LONG`, `400 METRICS.INVALID_RANGE` |
| Hour buckets | windows of 14 days or less | `422 METRICS.WINDOW_TOO_FINE` ("Hour buckets cover at most 14 days; pick day or week") |
| `auto` granularity | hour up to 3 days, else day; `week` only when asked | |
| `top_n` | 1 to 5 with a time axis (default 5), 1 to 100 without (default 20) | `400 METRICS.TOP_N_TOO_LARGE` |
| Measures per query | 1 to 4 | `400 METRICS.TOO_MANY_MEASURES` |
| Filters | at most 3, each with 1 to 50 values, `in` or `not_in` | `TOO_MANY_FILTERS`, `TOO_MANY_VALUES`, `BAD_OPERATOR` (400) |
| `compare` | only without a time axis | `400 METRICS.COMPARE_NEEDS_NO_AXIS` |
| Statement time | 8000 ms, `tessary.metrics.statement-timeout-ms` | `422 METRICS.TIMED_OUT` ("Query took too long. Narrow the window or pick a coarser granularity.") |
| Concurrent queries | 3, `tessary.metrics.max-concurrent-reads`; a request waits 2 s for a permit | `503 METRICS.BUSY` |
| Widgets per dashboard | 12 | `400 METRICS.TOO_MANY_WIDGETS` |
| Dashboards per project | 20, besides `default` | `400 METRICS.TOO_MANY_DASHBOARDS` |
| Dashboard names, widget titles | 80 characters | `400 COMMON.VALIDATION_FAILED` for a name, `400 METRICS.INVALID_WIDGET` for a title |

With these rules a time series has at most 336 buckets (14 days of hours) and 2016 cells (336
buckets times five keys plus `__other__`), so there is no separate bucket or cell cap.

The timeout and the query run in one read-only transaction (`set_config('statement_timeout', ...,
true)`), so the timeout ends with the transaction. The three-permit gate exists because Metrics
shares one connection pool with ingest; the page keeps at most four widget reads in flight and
retries a `BUSY` once. Each query logs one line with its view, window, granularity, row count,
duration and whether it timed out; no filter value or key goes in it.

## Dashboards

A dashboard is a name, a description and a definition. The definition is version 1: a default
range (`24h`, `7d`, `30d` or `90d`) and up to 12 widgets, each with an id, an optional title, a
chart type (`number`, `timeseries` or `table`), its saved query and its grid placement. One row of
`metric_dashboard` holds it whole, so a page renders from one read and there are no shared widgets.

Every write is checked against the catalog, and a refusal is `400 METRICS.INVALID_WIDGET` naming
the widget:

| Chart | Measures | Dimension | `top_n` | `compare` |
|---|---|---|---|---|
| `number` | 1 or 2 of one view; the second prints as a note under the number | none | none | allowed |
| `timeseries` | 1 | optional | 1 to 5 | not allowed |
| `table` | 1 to 4 | required | 1 to 100 | not allowed |

Each widget must also pass the query validator over a one-week window, so a dashboard cannot store
a widget the engine would refuse.

**The default dashboard is code, not a row.** `DefaultDashboard` holds it with id `default`; no
seed job writes it, and it cannot be edited or deleted. Its range is 7 days and it holds nine
widgets:

| Widget | View | Measures | Chart | Placement (x, y, w, h) |
|---|---|---|---|---|
| Spend | `llm_calls` | `cost_usd`, note `usd_per_trace`, compare | number | 0, 0, 4, 3 |
| Traces | `traces` | `traces`, note `unterminated_traces` | number | 4, 0, 4, 3 |
| p95 latency | `traces` | `latency_p95_ms`, note `latency_p50_ms`, compare | number | 8, 0, 4, 3 |
| Tokens | `llm_calls` | `tokens`, note `calls` | number | 0, 3, 6, 2 |
| Cases opened | `cases` | `cases`, note `open_cases`, compare | number | 6, 3, 6, 2 |
| Spend by call site | `llm_calls` | `cost_usd` by `call_site`, top 5 | timeseries | 0, 5, 6, 5 |
| p95 latency over time | `traces` | `latency_p95_ms` | timeseries | 6, 5, 6, 5 |
| By call site | `traces` | `cost_usd`, `usd_per_trace`, `latency_p95_ms`, `traces` by `call_site`, top 20 | table | 0, 10, 12, 5 |
| By model | `llm_calls` | `cost_usd`, `calls`, `tokens` by `model`, top 20 | table | 0, 15, 12, 4 |

**Editing starts with a copy.** The first edit on `default` opens "Make an editable copy", which
creates "Default (copy)" (then "Default (copy 2)" on a name clash) through `POST /dashboards` with
`clone_of: "default"` and the edit already applied. A dashboard is written whole: edit mode keeps a
draft, and "Done" sends one `PUT`. `/metrics` always opens `default`; there is no home dashboard
setting. `default_range` is stored but only the API sets it in v1; the page takes its window from
the URL.

## The grid

A dashboard lays out on 12 columns with Langfuse-style integer placements on each widget:
`x` from 0 to 11, `y` from 0, `w` from 3 to 12 with `x + w` at most 12, and `h` from 2 to 8 rows.
The validator refuses a placement outside the grid and any two widgets that overlap. A row is 72 px
with a 16 px gap, so a widget of `h` rows is `72h + 16(h - 1)` px tall: 160 px at h2, 248 px at
h3, 424 px at h5.

The page draws the grid with `react-grid-layout` (MIT, pinned at 2.2.4, in its own `vendor-grid`
chunk). Its stylesheets are not imported; `src/index.css` re-tokens the classes it emits, so no raw
color enters. In edit mode, a widget moves by its title bar and resizes from its bottom-right
corner, and vertical compaction closes every gap a drag leaves, which is why a stored layout never
overlaps. For the keyboard, each widget's menu offers "Move earlier" and "Move later", which swap
its placement with its neighbour in reading order (by `y`, then `x`) and compact. Resize has no
keyboard path in v1. Below the `lg` breakpoint the grid stacks every widget full width in reading
order, read only.

## Chart palettes

A chart series is the one place a hue carries data instead of status. A Metrics time series draws
up to six ranked series plus the "Other" fold, which a grey ramp cannot keep apart past three lines.
The exception is narrow: series colors appear only inside a chart's SVG and its legend (and the
palette picker's swatches). Tables, number tiles, deltas and the pulse strip stay achromatic, and
no series color equals a status hue.

Three palettes ship, each with six series colors and an `other` color, defined once in
`frontend/tokens.css` as `--color-chart-<palette>-1..6` and `-other`:

- `tessary`, the default: the Tessary v2 chart ramp.
- `colorblind` ("Colorblind safe"): Okabe and Ito's set without its dimmest blue and without black.
- `greyscale`: grey steps only, where dash and stroke width tell series apart.

Each person picks one in Settings > Organization > Appearance. The choice is stored per browser in
`localStorage` as `tessary.prefs.chartPalette`; `ChartPaletteProvider` writes it to `<html>` as
`data-chart-palette`, and that attribute re-points the `--color-series-1..6` and
`--color-series-other` tokens a chart reads. A component never names a palette token. Every value
clears 3:1 against `--color-surface`, the card a chart sits on. The "Other" series is always
dashed, so it reads as the fold in every palette. The values and contrast ratios are in
`frontend/DESIGN_SYSTEM.md` § *Charts*.

## Time range, filter and links

The window is in the URL. `?range=24h`, `7d`, `30d` or `90d` picks a preset, which ends at the
start of the next UTC hour, so every widget on the page and its cache key agree on "now" for an
hour. `?range=custom&from=<ISO>&to=<ISO>` is a custom window, entered as two local times; the page
refuses a reversed window or one longer than 90 days before it sends anything. No `range` means the
dashboard's `default_range`.

`?call_site=<id>` filters every widget whose view has a `call_site` dimension and is never saved.
A widget whose view has none says "Not filtered by call site".

A table row or tile links where the figure can be inspected:

| Key | Opens |
|---|---|
| a call site | Traces with `call_site=<id>` and the widget's window as a custom range |
| `__unattributed__` call site | Traces with `call_site_scope=none` and the window, because a call-site filter never matches NULL |
| a `cases` row or the Cases opened tile | Triage |
| `__other__` in any view, a model, and every other dimension | nothing; Traces has no filter for them |

## What changed from Vitals

The Vitals page showed four totals and two tables over 7 or 30 days. Metrics' default dashboard
covers the same figures, and four of them read differently on purpose:

- **Latency reads lower.** Vitals measured the trace envelope; Metrics measures the root span's
  own interval (rule 4), about 10 percent lower at p95.
- **Spend per trace divides by priced traces** (rule 6), not by every trace with token usage.
- **No `flagged` and no tool-error column.** Judgment belongs to the classifiers; the tool-error
  number left in August ([tool-error.md](./tool-error.md) §8.4).
- **Four range presets and a custom window** replace the 7/30 toggle, and the priced-model count
  in the Vitals response is gone.

`/vitals` in the app redirects to `/metrics`. `TokenUsage`, the ingest pricer's four token buckets,
moved from `vitals/` to `pricing/`.
