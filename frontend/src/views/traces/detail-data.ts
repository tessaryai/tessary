// SPDX-License-Identifier: Apache-2.0
/*
 * Trace interior — data layer over the trace detail API.
 *
 * `GET {base}/traces/:traceId` returns two things: the trace's own row, rollup
 * columns and all, and its spans. All three renderings are views over the spans
 * — Conversation reads their dialogue, Tree reads their nesting, Timeline reads
 * their timing — while the header reads the trace.
 *
 * WHAT THE HEADER NO LONGER DOES IS ADD ANYTHING UP. It used to reduce over the
 * span list for a token total and scan it for a time envelope, which meant the
 * detail could quietly disagree with the list row for the same trace: the list
 * showed the rollup worker's numbers and the header showed whatever spans had
 * arrived by the time this request ran. Both read `trace` now, so they cannot
 * disagree, and an unsettled trace says so instead of presenting a partial sum
 * as a finished one.
 */
import { useQuery } from "@tanstack/react-query";
import type { TraceDetailView } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";

/** One step of a trace — the v2 `span`. Formerly `observation`. */
export type Span = TraceDetailView["spans"][number];

/** The trace's own row: identity, timing, and the rollup columns the worker wrote. */
export type TraceRollup = TraceDetailView["trace"];

export function useTraceDetail(traceId: string | null | undefined) {
  const api = useProjectApi();
  return useQuery({
    queryKey: ["trace", api.base, traceId],
    queryFn: () => api.getTrace(traceId ?? ""),
    enabled: !!traceId,
  });
}

/**
 * Parent first, then siblings by clock — the order the work actually happened in.
 *
 * The API returns observations in no particular order: a trace routinely lists
 * an `execute_tool` span *before* the agent span that called it, which reads as
 * a tool running before the question that prompted it. Sorting by `started_at`
 * alone is not enough either, since a parent and its first child can share a
 * timestamp. All three views order through here so they agree.
 */
export function spanOrder(spans: Span[], parentOf?: Map<string, string>): Span[] {
  const byId = new Map(spans.map((o) => [o.id, o]));
  const children = new Map<string | null, Span[]>();
  for (const o of spans) {
    // A parent outside this trace is treated as a root, not dropped. `parent_span_id` is the
    // producer's own statement; `path` is what the platform derived from it, and a null path means
    // "ancestry not resolved yet", never "root".
    const declared = parentOf?.get(o.id) ?? o.parent_span_id;
    const key = declared != null && byId.has(declared) ? declared : null;
    children.set(key, [...(children.get(key) ?? []), o]);
  }
  for (const list of children.values()) {
    list.sort((a, b) => (a.started_at ?? "").localeCompare(b.started_at ?? ""));
  }

  const out: Span[] = [];
  const seen = new Set<string>();
  const walk = (parent: string | null) => {
    for (const o of children.get(parent) ?? []) {
      if (seen.has(o.id)) continue; // a cycle is corrupt data; show once and move on
      seen.add(o.id);
      out.push(o);
      walk(o.id);
    }
  };
  walk(null);
  for (const o of spans) if (!seen.has(o.id)) out.push(o);
  return out;
}

/**
 * Depth of each observation in the parent chain, for the Tree view's indent.
 *
 * `parentOf` re-parents a span for display without touching the data: a trace
 * parents its tool spans to the agent, so the llm call that actually requested
 * them is their sibling. Passing the request→execution links nests them under it.
 */
export function depthOf(spans: Span[], parentOf?: Map<string, string>): Map<string, number> {
  const byId = new Map(spans.map((o) => [o.id, o]));
  const depths = new Map<string, number>();

  const resolve = (o: Span, seen: Set<string>): number => {
    const cached = depths.get(o.id);
    if (cached != null) return cached;
    // A cycle would be corrupt data rather than a deep trace; stop rather than hang.
    if (seen.has(o.id)) return 0;
    // The server's own `depth` is preferred where it has one: it derives from the materialized
    // `path`, which resolves ancestry the client cannot see when a parent span is missing from this
    // response. It is null while that ancestry is still pending, which is when the walk below has to
    // stand in — and NOT a licence to read the span as a root.
    if (parentOf == null && o.depth != null) {
      depths.set(o.id, o.depth);
      return o.depth;
    }
    seen.add(o.id);
    const declared = parentOf?.get(o.id) ?? o.parent_span_id;
    const parent = declared ? byId.get(declared) : undefined;
    const depth = parent ? resolve(parent, seen) + 1 : 0;
    depths.set(o.id, depth);
    return depth;
  };

  for (const o of spans) resolve(o, new Set());
  return depths;
}

/**
 * Wall-clock bounds of the trace, for the Timeline's waterfall scale.
 *
 * The trace's own `started_at`/`ended_at` are the scale where it has both — they
 * are the turn the user actually waited on, and they include time no span
 * covers. A trace still in flight has no end, and there the spans on screen are
 * the only bound available.
 */
export function traceBounds(trace: TraceRollup | undefined, spans: Span[]): { start: number; end: number } | null {
  if (trace?.started_at && trace.ended_at) {
    const start = new Date(trace.started_at).getTime();
    const end = new Date(trace.ended_at).getTime();
    if (!Number.isNaN(start) && !Number.isNaN(end) && end > start) return { start, end };
  }
  let start = Number.POSITIVE_INFINITY;
  let end = Number.NEGATIVE_INFINITY;
  for (const o of spans) {
    const s = o.started_at ? new Date(o.started_at).getTime() : NaN;
    if (Number.isNaN(s)) continue;
    start = Math.min(start, s);
    const e = o.ended_at ? new Date(o.ended_at).getTime() : s + (o.duration_ms ?? 0);
    if (!Number.isNaN(e)) end = Math.max(end, e);
  }
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return null;
  return { start, end };
}

/**
 * The whole trace in one line, read off the trace row.
 *
 * Conversation deliberately carries no per-span headers — a reader following the
 * dialogue does not want a latency figure between two sentences — so the counts
 * live here, once, at the top.
 *
 * Every figure is the rollup worker's. The client-side reduce this replaced
 * produced a different number from the list row whenever a span had arrived
 * since the last rollup, and there was nothing on screen to say which was right.
 * When the trace has not settled the line says so rather than presenting a
 * partial as a total.
 */
export function traceSummary(trace: TraceRollup | undefined, spans: Span[]): string {
  const count = trace?.span_count ?? spans.length;
  const parts = [`${count} step${count === 1 ? "" : "s"}`];
  if (trace?.latency_ms != null) parts.push(formatDuration(trace.latency_ms));
  if (trace?.total_tokens != null) parts.push(formatTokens(trace.total_tokens));
  if (trace && trace.unpriced_spans != null && trace.unpriced_spans > 0) {
    parts.push(`${trace.unpriced_spans} unpriced`);
  }
  const models = [...new Set(spans.map((o) => o.model).filter((m): m is string => !!m))];
  if (models.length === 1) parts.push(models[0]);
  else if (models.length > 1) parts.push(`${models.length} models`);
  if (trace && !trace.is_settled) parts.push("still arriving");
  return parts.join(" · ");
}

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return "—";
  // Minutes past a minute: a hung tool call runs to seven figures of milliseconds, and `1029.68s`
  // is a number a reader has to divide before it means anything.
  if (ms >= 60_000) {
    const mins = Math.floor(ms / 60_000);
    return `${mins}m ${Math.round((ms % 60_000) / 1000)}s`;
  }
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${ms}ms`;
}

export function formatTokens(n: number | null | undefined): string {
  if (n == null) return "—";
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M tok`;
  if (n >= 1_000) return `${Math.round(n / 1000)}k tok`;
  return `${n} tok`;
}

export function clockLabel(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleTimeString("en-US", { hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export type { TraceDetailView };
