// SPDX-License-Identifier: Apache-2.0
// Two parallel reads: the header and the heavier spans read, so the header never waits on spans.
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

/** The whole session in one line: trace count, duration, total tokens, and unsettled or truncated markers. */
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
