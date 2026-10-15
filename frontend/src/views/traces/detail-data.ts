// SPDX-License-Identifier: Apache-2.0
// The header reads the trace row's rollups, never sums spans, so it cannot disagree with the list row.
import { useQuery } from "@tanstack/react-query";
import type { TraceDetailView } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";

export type Span = TraceDetailView["spans"][number];

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
 * The API returns spans unordered (a tool span can precede its agent), and a parent can share its
 * child's timestamp. All three views order through here.
 */
export function spanOrder(spans: Span[], parentOf?: Map<string, string>): Span[] {
  const byId = new Map(spans.map((o) => [o.id, o]));
  const children = new Map<string | null, Span[]>();
  for (const o of spans) {
    // A parent outside this trace makes a root. A null `path` means ancestry not resolved yet, never root.
    const declared = parentOf?.get(o.id) ?? o.parent_span_id;
    const key = declared != null && byId.has(declared) ? declared : null;
    children.set(key, [...(children.get(key) ?? []), o]);
  }
  for (const list of children.values()) {
    list.sort((a, b) => a.started_at.localeCompare(b.started_at));
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

/** `parentOf` re-parents tool spans under the llm call that requested them, for display only. */
export function depthOf(spans: Span[], parentOf: Map<string, string>): Map<string, number> {
  const byId = new Map(spans.map((o) => [o.id, o]));
  const depths = new Map<string, number>();

  const resolve = (o: Span, seen: Set<string>): number => {
    const cached = depths.get(o.id);
    if (cached != null) return cached;
    if (seen.has(o.id)) return 0;
    seen.add(o.id);
    const declared = parentOf.get(o.id) ?? o.parent_span_id;
    const parent = declared ? byId.get(declared) : undefined;
    const depth = parent ? resolve(parent, seen) + 1 : 0;
    depths.set(o.id, depth);
    return depth;
  };

  for (const o of spans) resolve(o, new Set());
  return depths;
}

/** The trace's own bounds where it has both, since they include time no span covers. */
export function traceBounds(trace: TraceRollup | undefined, spans: Span[]): { start: number; end: number } | null {
  if (trace?.started_at && trace.ended_at) {
    const start = new Date(trace.started_at).getTime();
    const end = new Date(trace.ended_at).getTime();
    if (!Number.isNaN(start) && !Number.isNaN(end) && end > start) return { start, end };
  }
  let start = Number.POSITIVE_INFINITY;
  let end = Number.NEGATIVE_INFINITY;
  for (const o of spans) {
    const s = new Date(o.started_at).getTime();
    if (Number.isNaN(s)) continue;
    start = Math.min(start, s);
    const e = o.ended_at ? new Date(o.ended_at).getTime() : s + (o.duration_ms ?? 0);
    if (!Number.isNaN(e)) end = Math.max(end, e);
  }
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return null;
  return { start, end };
}

/** Every figure is the rollup worker's; an unsettled trace says so rather than show a partial sum. */
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
