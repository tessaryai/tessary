// SPDX-License-Identifier: Apache-2.0
// Every column is a column on the trace row, written by the rollup worker when the trace settled,
// so the page is a filter, a sort and a page. Span-level facts live in the trace detail.
import { useInfiniteQuery } from "@tanstack/react-query";
import { useMemo, useRef } from "react";
import type { TraceFilterParams } from "../../api/client";
import type { TraceListItemView as TraceListItem } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";

export type TraceFilters = {
  q?: string;
  status?: string;
  callSite?: string;
  /** true keeps traces with any call site, false only those with none. */
  hasCallSite?: boolean;
  kind?: string;
  /** Absolute ISO-8601 bounds. Resolved by the picker, never a relative token — see index-filters. */
  from?: string | null;
  to?: string | null;
  /** A classifier id, or "any": traces a classifier flagged. */
  detectedBy?: string;
};

/** The filters as the traces and sessions reads take them. */
export function filterParams(filters: TraceFilters): TraceFilterParams {
  return {
    q: filters.q,
    status: filters.status,
    callSite: filters.callSite,
    hasCallSite: filters.hasCallSite,
    kind: filters.kind,
    fromTimestamp: filters.from ?? undefined,
    toTimestamp: filters.to ?? undefined,
    detectedBy: filters.detectedBy,
  };
}

/** 50 a page: the server default, and small enough to paint before the scroll reaches the sentinel. */
const PAGE_SIZE = 50;

/**
 * The traces list, page by page. `sort` is pinned to `when`: other sorts would reorder the keyset under
 * an infinite scroll. `epoch` is a cache-busting nonce so ↻ can re-pin a rolling range and drop old pages.
 */
export function useTracesIndex(filters: TraceFilters, epoch = 0, enabled = true) {
  const api = useProjectApi();
  return useInfiniteQuery({
    queryKey: ["traces-index", api.base, filters, epoch],
    queryFn: ({ pageParam }) =>
      api.listTraces({ limit: PAGE_SIZE, sort: "when", cursor: pageParam ?? undefined, ...filterParams(filters) }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.next_cursor ?? null,
    // A search the server stopped at its timeout would only run as long again, three more times.
    retry: false,
    enabled,
  });
}

/**
 * Call-site options harvested from the rows on screen; there is no session-scoped facets endpoint. The set
 * only grows, because once filtered every row carries the same call site.
 */
export function useObservedFacets(rows: TraceListItem[]): { callSites: string[] } {
  const seen = useRef(new Set<string>());
  return useMemo(() => {
    for (const row of rows) {
      if (row.call_site_id) seen.current.add(row.call_site_id);
    }
    return { callSites: [...seen.current].sort() };
  }, [rows]);
}

/** Why a number is absent: `pending` (not rolled up yet), `none` (settled, really nothing) or `value`. */
export type CellState = "pending" | "none" | "value";

export function cellState(row: TraceListItem, value: number | null | undefined): CellState {
  if (value != null) return "value";
  return row.is_settled ? "none" : "pending";
}

/** `14:52:11` for today, `Aug 8 14:52:11` for anything older, so rows from different days do not read as one hour. */
export function formatWhen(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  const clock = d.toLocaleTimeString("en-US", {
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
  const now = new Date();
  const sameDay =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate();
  if (sameDay) return clock;
  const date = d.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    // A year only when it is not this one — the same rule as the date itself.
    year: d.getFullYear() === now.getFullYear() ? undefined : "numeric",
  });
  return `${date} ${clock}`;
}

/** A duration labeled per part (`42s`, `5m 30s`, `2h 15m 30s`, `3d 2h 15m 30s`), since session rows can span days. */
export function formatLatency(ms: number | null | undefined): string {
  if (ms == null) return "—";
  const totalSeconds = Math.round(Math.max(0, ms) / 1000);
  const s = totalSeconds % 60;
  const totalMinutes = Math.floor(totalSeconds / 60);
  const m = totalMinutes % 60;
  const totalHours = Math.floor(totalMinutes / 60);
  const h = totalHours % 24;
  const d = Math.floor(totalHours / 24);

  if (totalSeconds < 60) return `${s}s`;
  if (totalMinutes < 60) return `${m}m ${s}s`;
  if (totalHours < 24) return `${h}h ${m}m ${s}s`;
  return `${d}d ${h}h ${m}m ${s}s`;
}

/** Four decimals: a single Haiku turn costs about $0.008. */
export function formatCost(usd: number | null | undefined): string {
  if (usd == null) return "—";
  if (usd === 0) return "0";
  return usd >= 1 ? usd.toFixed(2) : usd.toFixed(4);
}

/** Three significant digits. The thresholds are rounding boundaries: 9.999 renders "10.00" via toFixed(2). */
function threeDigits(n: number): string {
  if (n >= 99.95) return String(Math.round(n));
  if (n >= 9.995) return n.toFixed(1);
  return n.toFixed(2);
}

/** Ascending, so promotion is one step forward. */
const UNITS: { at: number; suffix: string }[] = [
  { at: 1, suffix: "" },
  { at: 1_000, suffix: "k" },
  { at: 1_000_000, suffix: "M" },
  { at: 1_000_000_000, suffix: "B" },
];

/**
 * Token counts at a fixed three digits (`1.23k`, `12.3k`, `123k`, `1.23M`) so a right-aligned column compares
 * by width. The exact count is in the tooltip; see {@link exactTokens}.
 */
export function formatTokens(tokens: number | null | undefined): string {
  if (tokens == null) return "—";
  if (tokens < 0) return String(tokens);

  let i = 0;
  while (i + 1 < UNITS.length && tokens >= UNITS[i + 1].at) i++;
  // 999,950 scales to 999.95, which rounds to a FOURTH digit ("1000k").
  // Promote it into the next unit — "1.00M" — rather than widen the column.
  if (i + 1 < UNITS.length && Math.round(tokens / UNITS[i].at) >= 1000) i++;

  const unit = UNITS[i];
  return unit.at === 1 ? String(tokens) : `${threeDigits(tokens / unit.at)}${unit.suffix}`;
}

/** The unrounded count, grouped — the tooltip behind every abbreviated cell. */
export function exactTokens(tokens: number | null | undefined): string | undefined {
  if (tokens == null) return undefined;
  return `${tokens.toLocaleString("en-US")} tokens`;
}

export type { TraceListItem };
