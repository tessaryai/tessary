// SPDX-License-Identifier: Apache-2.0
/*
 * The metrics catalog, the built-in dashboard and result builders the Metrics tests share. The catalog and the
 * dashboard copy the backend contract field for field, so a test that renders them sees what the server serves.
 */
import type { Window } from "../views/metrics/metricsRules";
import type {
  MetricDashboard, MetricDashboardList, MetricDashboardSummary, MetricsCatalog, MetricsCatalogMeasure,
  MetricsCatalogView, MetricsQueryRequest, MetricsQueryResult, MetricsRow, MetricWidgetDefinition,
} from "../views/metrics/wire";

const dim = (name: string, label: string, high_cardinality = false) => ({ name, label, high_cardinality });

const measure = (
  name: string,
  label: string,
  unit: MetricsCatalogMeasure["unit"],
  gap_fill: MetricsCatalogMeasure["gap_fill"],
  companions: string[] = [],
): MetricsCatalogMeasure => ({ name, label, unit, gap_fill, companions });

const PRICED_TRACE_COMPANIONS = ["unpriced_traces", "unsettled_traces", "priced_traces"];
const TRACE_LATENCY_COMPANIONS = ["latency_n", "unterminated_traces", "rootless_traces"];
const PRICED_CALL_COMPANIONS = ["unpriced_calls", "unsettled_calls", "priced_traces"];

const TRACES_VIEW: MetricsCatalogView = {
  name: "traces",
  label: "Traces",
  description:
    "One row per trace. Latency is the root span's own interval. Spend and per-trace spend count settled traces only.",
  dimensions: [
    dim("call_site", "Call site"), dim("name", "Trace name", true), dim("user", "User", true),
    dim("session", "Session", true), dim("outcome", "Outcome"),
  ],
  measures: [
    measure("traces", "Traces", "count", "zero"),
    measure("errored_traces", "Errored traces", "count", "zero"),
    measure("cost_usd", "Spend", "usd", "zero", PRICED_TRACE_COMPANIONS),
    measure("usd_per_trace", "Spend per priced trace", "usd", "none", PRICED_TRACE_COMPANIONS),
    measure("unpriced_traces", "Unpriced traces", "count", "zero"),
    measure("unsettled_traces", "Traces still settling", "count", "zero"),
    measure("priced_traces", "Priced traces", "count", "zero"),
    measure("latency_p50_ms", "Median latency", "ms", "none", TRACE_LATENCY_COMPANIONS),
    measure("latency_p95_ms", "p95 latency", "ms", "none", TRACE_LATENCY_COMPANIONS),
    measure("latency_p99_ms", "p99 latency", "ms", "none", TRACE_LATENCY_COMPANIONS),
    measure("latency_n", "Traces with a finished root", "count", "zero"),
    measure("unterminated_traces", "Traces that did not finish", "count", "zero"),
    measure("rootless_traces", "Traces with no root span", "count", "zero"),
  ],
};

const LLM_CALLS_VIEW: MetricsCatalogView = {
  name: "llm_calls",
  label: "LLM calls",
  description: "One row per LLM span. Container spans are excluded, so a subtree total is never summed twice.",
  dimensions: [dim("call_site", "Call site"), dim("model", "Model"), dim("cost_source", "Cost source")],
  measures: [
    measure("calls", "LLM calls", "count", "zero"),
    measure("tokens", "Tokens", "tokens", "zero", ["unsettled_calls"]),
    measure("cost_usd", "Spend", "usd", "zero", PRICED_CALL_COMPANIONS),
    measure("usd_per_trace", "Spend per priced trace", "usd", "none", PRICED_CALL_COMPANIONS),
    measure("unpriced_calls", "Unpriced calls", "count", "zero"),
    measure("unsettled_calls", "Calls in traces still settling", "count", "zero"),
    measure("priced_traces", "Priced traces", "count", "zero"),
    measure("spending_traces", "Traces with token usage", "count", "zero"),
  ],
};

const TOOL_CALLS_VIEW: MetricsCatalogView = {
  name: "tool_calls",
  label: "Tool calls",
  description: "One row per tool call. A call with no start time falls outside every window.",
  dimensions: [dim("tool", "Tool"), dim("error_type", "Error type"), dim("scope", "Scope"), dim("tool_type", "Tool type")],
  measures: [
    measure("calls", "Tool calls", "count", "zero"),
    measure("errored_calls", "Errored calls", "count", "zero"),
    measure("error_rate", "Error rate", "ratio", "none", ["errored_calls"]),
    measure("retries", "Retries", "count", "zero"),
    measure("latency_p50_ms", "Median latency", "ms", "none", ["latency_n"]),
    measure("latency_p95_ms", "p95 latency", "ms", "none", ["latency_n"]),
    measure("latency_p99_ms", "p99 latency", "ms", "none", ["latency_n"]),
    measure("latency_n", "Calls with a latency", "count", "zero"),
  ],
};

const CASES_VIEW: MetricsCatalogView = {
  name: "cases",
  label: "Cases",
  description: "One row per case, placed by the time it opened.",
  dimensions: [
    dim("detector", "Detector"), dim("call_site", "Call site"), dim("state", "State"), dim("resolution", "Resolution"),
    dim("disposition", "Disposition"),
  ],
  measures: [
    measure("cases", "Cases opened", "count", "zero"),
    measure("open_cases", "Still open", "count", "zero"),
    measure("resolved_cases", "Resolved", "count", "zero"),
  ],
};

export const METRICS_CATALOG: MetricsCatalog = {
  views: [TRACES_VIEW, LLM_CALLS_VIEW, TOOL_CALLS_VIEW, CASES_VIEW],
  granularities: ["auto", "hour", "day", "week"],
  limits: {
    max_window_days: 90, max_hour_window_days: 14, max_top_n_with_axis: 5, max_top_n: 100, max_measures: 4,
    max_filters: 3, max_filter_values: 50, max_widgets: 12, max_dashboards: 20,
  },
};

export const WINDOW_7D: Window = { from: "2026-10-11T00:00:00Z", to: "2026-10-18T00:00:00Z" };

export function widget(over: Partial<MetricWidgetDefinition> & { id: string }): MetricWidgetDefinition {
  return {
    title: null, chart: "number", view: "llm_calls", measures: ["cost_usd"], dimension: null, filters: [], top_n: null,
    compare: null, x: 0, y: 0, w: 4, h: 3, ...over,
  };
}

const DEFAULT_WIDGETS: MetricWidgetDefinition[] = [
  widget({ id: "spend", title: "Spend", measures: ["cost_usd", "usd_per_trace"], compare: "previous_window", x: 0, y: 0, w: 4, h: 3 }),
  widget({ id: "traces", title: "Traces", view: "traces", measures: ["traces", "unterminated_traces"], x: 4, y: 0, w: 4, h: 3 }),
  widget({
    id: "p95-latency", title: "p95 latency", view: "traces", measures: ["latency_p95_ms", "latency_p50_ms"],
    compare: "previous_window", x: 8, y: 0, w: 4, h: 3,
  }),
  widget({ id: "tokens", title: "Tokens", measures: ["tokens", "calls"], x: 0, y: 3, w: 6, h: 2 }),
  widget({
    id: "cases-opened", title: "Cases opened", view: "cases", measures: ["cases", "open_cases"], compare: "previous_window",
    x: 6, y: 3, w: 6, h: 2,
  }),
  widget({
    id: "spend-by-call-site", title: "Spend by call site", chart: "timeseries", measures: ["cost_usd"],
    dimension: "call_site", top_n: 5, x: 0, y: 5, w: 6, h: 5,
  }),
  widget({
    id: "p95-over-time", title: "p95 latency over time", chart: "timeseries", view: "traces", measures: ["latency_p95_ms"],
    x: 6, y: 5, w: 6, h: 5,
  }),
  widget({
    id: "by-call-site", title: "By call site", chart: "table", view: "traces",
    measures: ["cost_usd", "usd_per_trace", "latency_p95_ms", "traces"], dimension: "call_site", top_n: 20,
    x: 0, y: 10, w: 12, h: 5,
  }),
  widget({
    id: "by-model", title: "By model", chart: "table", measures: ["cost_usd", "calls", "tokens"], dimension: "model",
    top_n: 20, x: 0, y: 15, w: 12, h: 4,
  }),
];

const DEFAULT_DESCRIPTION = "Spend, traces, latency, tokens and cases over the window.";

export const DEFAULT_DASHBOARD: MetricDashboard = {
  id: "default", name: "Default", description: DEFAULT_DESCRIPTION, built_in: true,
  definition: { version: 1, default_range: "7d", widgets: DEFAULT_WIDGETS },
  created_by: null, created_at: null, updated_at: null, can_manage: true,
};

export const DEFAULT_SUMMARY: MetricDashboardSummary = {
  id: "default", name: "Default", description: DEFAULT_DESCRIPTION, built_in: true, widget_count: 9, updated_at: null,
};

export const DASHBOARD_LIST: MetricDashboardList = { dashboards: [DEFAULT_SUMMARY], can_manage: true };

export function dashboard(
  over: Partial<MetricDashboard> & { id: string; widgets?: MetricWidgetDefinition[] },
): MetricDashboard {
  const { widgets = [], ...rest } = over;
  return {
    name: `Dashboard ${over.id}`, description: "", built_in: false,
    definition: { version: 1, default_range: "7d", widgets },
    created_by: "user-1", created_at: "2026-10-17T09:00:00Z", updated_at: "2026-10-17T09:00:00Z", can_manage: true,
    ...rest,
  };
}

const totalRow = (values: Record<string, number | null>, n: number): MetricsRow => ({
  bucket: null, key: null, label: null, n, values,
});

export function totalResult(
  view: string,
  values: Record<string, number | null>,
  n = 100,
  previous?: Record<string, number | null>,
): MetricsQueryResult {
  return {
    view, window: { ...WINDOW_7D, granularity: null, buckets: 0 }, dimension: null, rows: [totalRow(values, n)],
    previous_rows: previous ? [totalRow(previous, n)] : null, top_n: null, truncated: false, elapsed_ms: 12,
  };
}

/** `top_n` reports nothing folded; a test about the fold overrides it. */
export function seriesResult(
  view: string,
  measure: string,
  buckets: { bucket: string; keys: { key: string | null; label: string | null; v: number | null; n: number }[] }[],
  granularity: "hour" | "day" | "week" = "day",
): MetricsQueryResult {
  const rows = buckets.flatMap((b) =>
    b.keys.map((k) => ({ bucket: b.bucket, key: k.key, label: k.label, n: k.n, values: { [measure]: k.v } })),
  );
  const keys = new Set(rows.flatMap((r) => (r.key == null ? [] : [r.key])));
  return {
    view, window: { ...WINDOW_7D, granularity, buckets: buckets.length }, dimension: keys.size > 0 ? "call_site" : null,
    rows, previous_rows: null, top_n: keys.size > 0 ? { shown: keys.size, total_keys: keys.size } : null,
    truncated: false, elapsed_ms: 18,
  };
}

export function tableResult(
  view: string,
  dimension: string,
  rows: { key: string; label: string; n: number; values: Record<string, number | null> }[],
  totalKeys?: number,
): MetricsQueryResult {
  const shown = rows.filter((r) => r.key !== "__other__").length;
  const total = totalKeys ?? shown;
  return {
    view, window: { ...WINDOW_7D, granularity: null, buckets: 0 }, dimension,
    rows: rows.map((r) => ({ bucket: null, ...r })), previous_rows: null, top_n: { shown, total_keys: total },
    truncated: total > shown, elapsed_ms: 25,
  };
}

export const EMPTY_METRICS_RESULT: MetricsQueryResult = {
  view: "llm_calls", window: { ...WINDOW_7D, granularity: null, buckets: 0 }, dimension: null,
  rows: [totalRow({}, 0)], previous_rows: [totalRow({}, 0)], top_n: null, truncated: false, elapsed_ms: 3,
};

/** Every asked measure reads 1 and every companion 0, so a page renders figures without a companion line. */
function valuesFor(req: MetricsQueryRequest): Record<string, number | null> {
  const view = METRICS_CATALOG.views.find((v) => v.name === req.view);
  const companions = req.measures.flatMap((m) => view?.measures.find((x) => x.name === m)?.companions ?? []);
  return {
    ...Object.fromEntries(companions.map((c) => [c, 0])),
    ...Object.fromEntries(req.measures.map((m) => [m, 1])),
  };
}

const DAY_MS = 86_400_000;

export function resultFor(req: MetricsQueryRequest): MetricsQueryResult {
  const values = valuesFor(req);
  const window = { from: req.from, to: req.to };
  if (req.granularity != null) {
    const start = Date.parse(req.from);
    const buckets = [start, start + DAY_MS].map((t) => new Date(t).toISOString());
    const keys = req.dimension == null
      ? [{ key: null, label: null }]
      : [{ key: "chat", label: "Chat support" }, { key: "__other__", label: "Other (3 more)" }];
    const rows = buckets.flatMap((bucket) => keys.map((k) => ({ bucket, ...k, n: 10, values })));
    return {
      view: req.view, window: { ...window, granularity: "day", buckets: 2 }, dimension: req.dimension, rows,
      previous_rows: null, top_n: req.dimension == null ? null : { shown: 1, total_keys: 4 },
      truncated: req.dimension != null, elapsed_ms: 18,
    };
  }
  if (req.dimension != null) {
    return {
      view: req.view, window: { ...window, granularity: null, buckets: 0 }, dimension: req.dimension,
      rows: [
        { bucket: null, key: "chat", label: "Chat support", n: 40, values },
        { bucket: null, key: "search", label: "Search", n: 30, values },
        { bucket: null, key: "__other__", label: "Other (3 more)", n: 5, values },
      ],
      previous_rows: null, top_n: { shown: 2, total_keys: 5 }, truncated: true, elapsed_ms: 25,
    };
  }
  return {
    view: req.view, window: { ...window, granularity: null, buckets: 0 }, dimension: null, rows: [totalRow(values, 10)],
    previous_rows: req.compare == null ? null : [totalRow(values, 10)], top_n: null, truncated: false, elapsed_ms: 12,
  };
}
