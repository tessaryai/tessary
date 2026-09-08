// SPDX-License-Identifier: Apache-2.0
/*
 * Session interior — data layer over the session detail + session spans APIs.
 *
 * Two separate reads, fetched in parallel by the rail: `useSessionDetail` for the header (totals,
 * trace list) and `useSessionSpans` for the heavier per-span read the three views actually render.
 * They stay two calls rather than one for the same reason the backend keeps them two endpoints (see
 * SessionDtos.SessionSpans's doc comment) — a session's header should never wait on span payloads it
 * doesn't need.
 */
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import type { SessionDetailView } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";
import type { Span } from "./detail-data";
import { formatTokens } from "./detail-data";

export function useSessionDetail(sessionId: string | null | undefined) {
  const api = useProjectApi();
  return useQuery({
    queryKey: ["session", api.base, sessionId],
    queryFn: () => api.getSession(sessionId ?? ""),
    enabled: !!sessionId,
  });
}

export function useSessionSpans(sessionId: string | null | undefined) {
  const api = useProjectApi();
  return useQuery({
    queryKey: ["session-spans", api.base, sessionId],
    queryFn: () => api.getSessionSpans(sessionId ?? ""),
    enabled: !!sessionId,
  });
}

/** Every span, grouped by which trace it belongs to — the shape every session view groups by. */
export function groupSpansByTrace(spans: Span[]): Map<string, Span[]> {
  const out = new Map<string, Span[]>();
  for (const s of spans) {
    const key = s.trace_id;
    const list = out.get(key);
    if (list) list.push(s);
    else out.set(key, [s]);
  }
  return out;
}

export function useSpansByTrace(spans: Span[] | undefined): Map<string, Span[]> {
  return useMemo(() => groupSpansByTrace(spans ?? []), [spans]);
}

/**
 * The whole session in one line — the rail header's meta, same shape as {@link traceSummary} one level
 * up: trace count, duration across every trace, total tokens, and the honesty devices (unsettled
 * traces, spans truncated) rather than presenting a partial sum as a finished one.
 */
export function sessionSummary(detail: SessionDetailView | undefined, spansTruncated: boolean): string {
  if (!detail) return "";
  const parts = [`${detail.trace_count} trace${detail.trace_count === 1 ? "" : "s"}`];
  if (detail.span_count != null) parts.push(`${detail.span_count} steps`);
  if (detail.total_tokens != null) parts.push(formatTokens(detail.total_tokens));
  if (detail.unpriced_spans != null && detail.unpriced_spans > 0) {
    parts.push(`${detail.unpriced_spans} unpriced`);
  }
  if (detail.unsettled_traces > 0) parts.push("still arriving");
  if (detail.traces_truncated || spansTruncated) parts.push("truncated");
  return parts.join(" · ");
}

export type { SessionDetailView };
