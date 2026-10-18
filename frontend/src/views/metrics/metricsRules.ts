// SPDX-License-Identifier: Apache-2.0
// The wire carries raw units and UTC instants, so every window, word and number a Metrics reader sees is made here.
import { usd } from "../../lib/usd";
import { type AxisUnit, stepPaths, type Step, toAxis, utcDay, yAxis } from "../classifiers/chartRules";
import { OTHER_KEY } from "./metricsLinks";
import type {
  MetricsCatalog, MetricsCatalogMeasure, MetricsCatalogView, MetricsPreset, MetricsQueryRequest, MetricsQueryResult,
  MetricsRow, MetricsUnit, MetricWidgetDefinition,
} from "./wire";

export const HOUR_MS = 3_600_000;
const DAY_MS = 24 * HOUR_MS;
/** Mirrors catalog.limits.max_window_days; the URL is parsed before the catalog loads. */
export const MAX_WINDOW_DAYS = 90;
/** Grid row height and vertical gap in px; gridLayout.ts reads them from here so Triage never loads the grid library. */
export const ROW_HEIGHT = 72;
export const GRID_MARGIN_Y = 16;

export const RANGE_PRESETS: { key: MetricsPreset; label: string; ms: number }[] = [
  { key: "24h", label: "Last 24 hours", ms: DAY_MS },
  { key: "7d", label: "Last 7 days", ms: 7 * DAY_MS },
  { key: "30d", label: "Last 30 days", ms: 30 * DAY_MS },
  { key: "90d", label: "Last 90 days", ms: 90 * DAY_MS },
];
const NOUNS: Record<MetricsPreset, string> = { "24h": "24 hours", "7d": "7 days", "30d": "30 days", "90d": "90 days" };

export type RangeState = { kind: "preset"; key: MetricsPreset } | { kind: "custom"; from: string; to: string };
export type Window = { from: string; to: string };

const presetOf = (key: MetricsPreset) => RANGE_PRESETS.find((p) => p.key === key)!;

export function nextHour(now: number): number {
  return Math.floor(now / HOUR_MS) * HOUR_MS + HOUR_MS;
}

export function resolveWindow(range: RangeState, now: number): Window {
  if (range.kind === "custom") return { from: range.from, to: range.to };
  const to = nextHour(now);
  return { from: new Date(to - presetOf(range.key).ms).toISOString(), to: new Date(to).toISOString() };
}

export function validateWindow(from: string | null, to: string | null): { window: Window } | { error: string } {
  const start = Date.parse(from ?? "");
  const end = Date.parse(to ?? "");
  if (Number.isNaN(start) || Number.isNaN(end)) return { error: "Pick a start and an end." };
  if (start >= end) return { error: "The start must be before the end." };
  if (end - start > MAX_WINDOW_DAYS * DAY_MS) return { error: "Pick a window of 90 days or less." };
  return { window: { from: from!, to: to! } };
}

export function parseRange(params: URLSearchParams, fallback: MetricsPreset): RangeState {
  const raw = params.get("range");
  const preset = RANGE_PRESETS.find((p) => p.key === raw);
  if (preset) return { kind: "preset", key: preset.key };
  if (raw === "custom") {
    const checked = validateWindow(params.get("from"), params.get("to"));
    if ("window" in checked) return { kind: "custom", ...checked.window };
  }
  return { kind: "preset", key: fallback };
}

export function rangeParams(range: RangeState): { range: string; from: string | null; to: string | null } {
  if (range.kind === "custom") return { range: "custom", from: range.from, to: range.to };
  return { range: range.key, from: null, to: null };
}

const hhmm = (at: number) => new Date(at).toISOString().slice(11, 16);

function instantLabel(iso: string): string {
  const at = Date.parse(iso);
  const day = new Date(at).toLocaleString("en-US", { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" });
  return `${day}, ${hhmm(at)}`;
}

export function rangeLabel(range: RangeState): string {
  if (range.kind === "preset") return presetOf(range.key).label;
  return `${instantLabel(range.from)} to ${instantLabel(range.to)} UTC`;
}

export function rangeNoun(range: RangeState): string {
  return range.kind === "preset" ? NOUNS[range.key] : "window";
}

export function viewOf(catalog: MetricsCatalog | undefined, name: string): MetricsCatalogView | undefined {
  return catalog?.views.find((v) => v.name === name);
}

export function measureOf(view: MetricsCatalogView | undefined, name: string): MetricsCatalogMeasure | undefined {
  return view?.measures.find((m) => m.name === name);
}

export function hasDimension(view: MetricsCatalogView | undefined, name: string): boolean {
  return view?.dimensions.some((d) => d.name === name) ?? false;
}

export function filteredByCallSite(w: MetricWidgetDefinition, view: MetricsCatalogView | undefined): boolean {
  return view?.name === w.view && hasDimension(view, "call_site");
}

export function widgetRequest(
  w: MetricWidgetDefinition,
  window: Window,
  callSite: string | null,
  view: MetricsCatalogView | undefined,
): MetricsQueryRequest {
  const filters = [...(w.filters ?? [])];
  if (callSite != null && filteredByCallSite(w, view)) filters.push({ field: "call_site", op: "in", values: [callSite] });
  return {
    view: w.view,
    measures: w.measures,
    dimension: w.dimension,
    filters: filters.length > 0 ? filters : null,
    from: window.from,
    to: window.to,
    granularity: w.chart === "timeseries" ? "auto" : null,
    top_n: w.top_n,
    compare: w.compare,
  };
}

const COMPACT = new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 1 });
const count = (v: number) => v.toLocaleString("en-US");

export function formatValue(unit: MetricsUnit, v: number | null | undefined): string {
  if (v == null) return "—";
  if (unit === "count") return count(v);
  if (unit === "tokens") return COMPACT.format(v);
  if (unit === "usd") return v >= 1 ? usd(v) : v === 0 ? "$0.00" : `$${v.toFixed(3)}`;
  if (unit === "ms") return v >= 1000 ? `${(v / 1000).toFixed(1)}s` : `${Math.round(v)}ms`;
  return `${(v * 100).toFixed(1)}%`;
}

const signed = (d: number, text: string) => (d > 0 ? `+${text}` : text);

export function deltaText(
  current: number | null | undefined,
  previous: number | null | undefined,
  unit: MetricsUnit,
): string {
  if (current == null || previous == null) return "—";
  if (unit === "count") {
    const d = Math.round(current - previous);
    return signed(d, count(d));
  }
  if (previous <= 0) return "—";
  const pct = Math.round(((current - previous) / previous) * 100);
  return signed(pct, `${pct}%`);
}

export function lowerFirst(label: string): string {
  return /^.[A-Z]/.test(label) ? label : label.charAt(0).toLowerCase() + label.slice(1);
}

/** Keyed by measure name; `calls` names both views' call counts, so it words the count by the measure's own label. */
export const NOTE_COPY: Record<string, (v: string, label: string) => string> = {
  usd_per_trace: (v) => `${v} per priced trace`,
  unterminated_traces: (v) => `${v} did not finish`,
  latency_p50_ms: (v) => `median ${v}`,
  calls: (v, label) => `across ${v} ${lowerFirst(label)}`,
  open_cases: (v) => `${v} still open`,
};

export function noteText(measure: MetricsCatalogMeasure, v: number | null | undefined): string {
  const value = formatValue(measure.unit, v);
  const copy = NOTE_COPY[measure.name];
  return copy ? copy(value, measure.label) : `${value} ${lowerFirst(measure.label)}`;
}

const COMPANION_LINES: Record<string, { key: string; line: (v: string, n: string) => string }[]> = {
  llm_calls: [
    { key: "unpriced_calls", line: (v) => `${v} unpriced calls not in this figure` },
    { key: "unsettled_calls", line: (v) => `${v} calls in traces still settling` },
  ],
  traces: [
    { key: "unpriced_traces", line: (v) => `${v} unpriced traces not in this figure` },
    { key: "unsettled_traces", line: (v) => `${v} traces still settling` },
    { key: "unterminated_traces", line: (v, n) => `${v} of ${n} traces did not finish` },
    { key: "rootless_traces", line: (v) => `${v} traces have no root span` },
  ],
};

/** Every line that applies, in order; the tile caps the note and these together at three. */
export function companionNotes(
  view: string,
  values: Record<string, number | null>,
  n: number,
  skip: string[],
): string[] {
  return (COMPANION_LINES[view] ?? []).flatMap(({ key, line }) => {
    const v = values[key] ?? 0;
    return v > 0 && !skip.includes(key) ? [line(count(v), count(n))] : [];
  });
}

export function unpricedOf(result: MetricsQueryResult): number {
  return result.rows.reduce((sum, r) => sum + (r.values.unpriced_calls ?? r.values.unpriced_traces ?? 0), 0);
}

/** Every unfiltered llm_calls read reports the same total, so the largest one is the figure, never their sum. */
export function unpricedTotal(
  results: (MetricsQueryResult | undefined)[],
): { count: number; noun: "LLM calls" | "traces" } | null {
  const largest = (view: string) =>
    Math.max(0, ...results.flatMap((r) => (r?.view === view ? [unpricedOf(r)] : [])));
  const calls = largest("llm_calls");
  if (calls > 0) return { count: calls, noun: "LLM calls" };
  const traces = largest("traces");
  return traces > 0 ? { count: traces, noun: "traces" } : null;
}

export type SeriesPoint = { at: number; v: number | null; n: number };
export type Series = { key: string | null; label: string; rank: number; other: boolean; points: SeriesPoint[] };

/** Rows arrive in rank order with the fold last, so first appearance is the rank; a total result charts at the window start. */
export function seriesOf(result: MetricsQueryResult, measure: string, fallbackLabel: string): Series[] {
  const buckets = [...new Set(result.rows.map((r) => r.bucket))];
  const byKey = new Map<string | null, { label: string; rows: Map<string | null, MetricsRow> }>();
  for (const r of result.rows) {
    const entry = byKey.get(r.key) ?? { label: r.label ?? fallbackLabel, rows: new Map() };
    entry.rows.set(r.bucket, r);
    byKey.set(r.key, entry);
  }
  return [...byKey].map(([key, { label, rows }], i) => ({
    key,
    label,
    rank: i + 1,
    other: key === OTHER_KEY,
    points: buckets.map((b) => {
      const row = rows.get(b);
      return { at: Date.parse(b ?? result.window.from), v: row?.values[measure] ?? null, n: row?.n ?? 0 };
    }),
  }));
}

export function seriesColour(s: Series): string {
  return s.other ? "var(--color-series-other)" : `var(--color-series-${Math.min(s.rank, 6)})`;
}

/**
 * stepPaths joins steps that start within an hour of the last one, which at hour granularity draws through a missing
 * bucket, so each run of non-null buckets is drawn on its own.
 */
export function seriesPaths(
  points: SeriesPoint[],
  bucketMs: number,
  windowTo: number,
  x: (t: number) => number,
  y: (v: number) => number,
): { solid: string; dashed: string } {
  const runs: Step[][] = [];
  let run: Step[] = [];
  for (const p of points) {
    if (p.v == null) {
      if (run.length > 0) runs.push(run);
      run = [];
    } else {
      run.push({ start: p.at, end: p.at + bucketMs, v: p.v, open: false });
    }
  }
  if (run.length > 0) runs.push(run);
  const last = runs.at(-1)?.at(-1);
  if (last && windowTo < last.end) last.open = true;
  return runs.reduce(
    (acc, r) => {
      const paths = stepPaths(r, x, y);
      return { solid: acc.solid + paths.solid, dashed: acc.dashed + paths.dashed };
    },
    { solid: "", dashed: "" },
  );
}

export function bucketLabel(bucketIso: string, unit: "hour" | "day" | "week"): string {
  if (unit === "hour") return `${utcDay(bucketIso)}, ${hhmm(Date.parse(bucketIso))} UTC`;
  if (unit === "week") return `Week of ${utcDay(bucketIso)}`;
  return utcDay(bucketIso);
}

const MONDAY_OFFSET_MS = 4 * DAY_MS;

export function timeTicks(window: Window, unit: "hour" | "day" | "week" | null): { at: number; label: string }[] {
  const from = Date.parse(window.from);
  const to = Date.parse(window.to);
  let step = DAY_MS;
  let first = Math.ceil(from / DAY_MS) * DAY_MS;
  let label = (at: number) => utcDay(new Date(at).toISOString());
  if (unit === "hour") {
    step = 6 * HOUR_MS;
    first = Math.ceil(from / step) * step;
    label = hhmm;
  } else if (unit === "week") {
    step = 7 * DAY_MS;
    first = Math.ceil((from - MONDAY_OFFSET_MS) / step) * step + MONDAY_OFFSET_MS;
  } else if (to - from >= 15 * DAY_MS) {
    step = 7 * DAY_MS;
  }
  const ticks: { at: number; label: string }[] = [];
  for (let at = first; at <= to; at += step) ticks.push({ at, label: label(at) });
  return ticks;
}

export function barsFor(unit: MetricsUnit): boolean {
  return unit === "count";
}

export function axisUnit(unit: MetricsUnit): AxisUnit {
  if (unit === "usd") return "usd";
  if (unit === "ms") return "seconds";
  if (unit === "ratio") return "percent";
  return "count";
}

const decimalsOf = (step: number) => Math.max(0, -Math.floor(Math.log10(step) + 1e-9));

/** `max` is a raw value; the ladder and `top` are in axis units, so the caller maps every point through toAxis. */
export function axisTicks(unit: MetricsUnit, max: number): { top: number; ticks: { value: number; label: string }[] } {
  const au = axisUnit(unit);
  const axis = yAxis(toAxis(au, max), au);
  const usdDecimals = Math.max(2, decimalsOf(axis.ticks[1].value - axis.ticks[0].value));
  const relabel = (value: number, label: string) => {
    if (unit === "usd") return `$${value.toFixed(usdDecimals)}`;
    if (unit === "ms") return `${value}s`;
    if (unit === "ratio") return `${value}%`;
    if (unit === "tokens") return COMPACT.format(value);
    return label;
  };
  return { top: axis.top, ticks: axis.ticks.map((t) => ({ value: t.value, label: relabel(t.value, t.label) })) };
}

/** Card padding 32, border 2, title bar 28, two 10 px gaps, legend 18 and 8 px slack come off the rows' height. */
export function chartHeightFor(h: number): number {
  return Math.max(h * ROW_HEIGHT + (h - 1) * GRID_MARGIN_Y - 108, 72);
}

export function copyName(name: string, taken: string[]): string {
  let copy = `${name} (copy)`;
  for (let n = 2; taken.includes(copy); n++) copy = `${name} (copy ${n})`;
  return copy;
}

export function emptyResult(result: MetricsQueryResult): boolean {
  return result.rows.every((r) => r.n === 0);
}
