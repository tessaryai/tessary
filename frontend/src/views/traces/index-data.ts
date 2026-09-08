// SPDX-License-Identifier: Apache-2.0
/*
 * Traces index — data layer over the traces read API.
 *
 * Every column this table renders is a column on the trace row: span count,
 * error count, the five typed token buckets, the costs, the previews, the call
 * site. The server computes none of them per request — the rollup worker wrote
 * them when the trace settled — so the page is a filter, a sort and a page all
 * the way down.
 *
 * That is also why there is no models column and no tool-call count any more.
 * Both were questions about a trace's SPANS, and answering them for a page of
 * fifty rows meant aggregating every observation in the project. They live in
 * the trace detail now, where reading one trace's spans is the point.
 */
import { useInfiniteQuery } from "@tanstack/react-query";
import { useMemo, useRef } from "react";
import type { TraceListItemView as TraceListItem } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";

export type TraceFilters = {
  q?: string;
  status?: string;
  callSite?: string;
  model?: string;
  kind?: string;
  /** Absolute ISO-8601 bounds. Resolved by the picker, never a relative token — see index-filters. */
  from?: string | null;
  to?: string | null;
};

/**
 * 50 a page: the server's own default, and small enough that the first screen
 * paints before the scroll can reach the sentinel.
 */
const PAGE_SIZE = 50;

/**
 * The traces list, page by page.
 *
 * `sort` is pinned to `when` rather than left to the server's default: the list
 * is a feed of what just happened, and reading it means newest first, always.
 * The other sorts the API offers (tokens, cost) are an analysis question, not a
 * monitoring one, and they would reorder the keyset under an infinite scroll.
 *
 * `epoch` is a cache-busting nonce, not a filter. It exists so the ↻ button can
 * re-resolve a rolling range's bounds and drop every page already fetched —
 * appending fresh rows onto a stale cursor chain would interleave two different
 * windows into one list.
 */
export function useTracesIndex(filters: TraceFilters, epoch = 0, enabled = true) {
  const api = useProjectApi();
  return useInfiniteQuery({
    queryKey: ["traces-index", api.base, filters, epoch],
    queryFn: ({ pageParam }) =>
      api.listTraces({
        limit: PAGE_SIZE,
        sort: "when",
        cursor: pageParam ?? undefined,
        q: filters.q,
        status: filters.status,
        callSite: filters.callSite,
        model: filters.model,
        kind: filters.kind,
        fromTimestamp: filters.from ?? undefined,
        toTimestamp: filters.to ?? undefined,
      }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.next_cursor ?? null,
    enabled,
  });
}

/**
 * Call-site options for the facet, harvested from the rows on screen.
 *
 * There is no facets endpoint on the project API — `/v1/query/facets` is
 * token-scoped for MCP, not session-scoped for the UI — so the honest source is
 * what the list has actually served. The call site is on the trace row (copied
 * down from its root span by the rollup), which is why it can still be harvested
 * this way and the model and kind vocabularies cannot: those are span facts, and
 * the list no longer reads spans at all.
 *
 * The set only ever grows within a session. Once you filter to one call site, the
 * loaded rows all carry it, and a set recomputed from them would collapse to a
 * single option and strand you there.
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

/**
 * A trace's numbers are absent for three different reasons, and the table has to
 * say which.
 *
 * - `pending` — the trace has not rolled up. Its totals are not zero and not
 *   unknown; they are not yet computed, because spans are still arriving.
 * - `none` — settled, and the value really is nothing. No span reported usage.
 * - `value` — a real number.
 *
 * All three rendered as one em dash before this milestone, which meant a live
 * turn and a free turn were indistinguishable on the one surface where the
 * difference matters.
 */
export type CellState = "pending" | "none" | "value";

export function cellState(row: TraceListItem, value: number | null | undefined): CellState {
  if (value != null) return "value";
  return row.is_settled ? "none" : "pending";
}

/**
 * The When column: `14:52:11` for today, `Aug 8 14:52:11` for anything older.
 *
 * A bare clock was fine when the list was one screen of recent traffic. Over a
 * 30-day window scrolled indefinitely it is actively misleading — thirty rows
 * reading `03:27:04`, `03:14:32`, `03:04:31` look like one busy hour and are in
 * fact three different days. The date appears exactly when it carries
 * information, so today's traffic keeps the narrow, scannable column.
 */
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

/**
 * A duration, unit-labeled per part rather than a bare number — the Latency column now spans
 * everything from a sub-second tool call to a session running multiple days, so a fixed "seconds"
 * unit stopped making sense the moment session rows joined this column. Each tier keeps only the units
 * that matter at that scale: `42s`, `5m 30s`, `2h 15m 30s`, `3d 2h 15m 30s` — never a unit that would
 * read as `0` and never one so fine it just adds noise.
 */
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

/**
 * Dollars, unitless for the same reason. Four decimals is not fussiness: a
 * single Haiku turn costs about $0.008, so two decimals renders most of this
 * table as `0.01` and the input/output split as `0.00`.
 */
export function formatCost(usd: number | null | undefined): string {
  if (usd == null) return "—";
  if (usd === 0) return "0";
  return usd >= 1 ? usd.toFixed(2) : usd.toFixed(4);
}

/**
 * Three significant digits inside one unit: 1.23, 12.3, 123.
 *
 * The thresholds are the ROUNDING boundaries, not the plain ones: 9.999 is
 * under 10 but `toFixed(2)` renders it "10.00", and 99.999 is under 100 but
 * `toFixed(1)` renders it "100.0" — four digits either way. Comparing against
 * 9.995 / 99.95 picks the bracket the number will land in after rounding.
 */
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
 * Token counts at a fixed three digits — `1.23k`, `12.3k`, `123k`, `1.23M`.
 *
 * The width is the point: this is a right-aligned numeric column read by
 * comparison down the page, and a mix of `1k` and `1.2M` makes two turns that
 * differ 1000-fold look like near neighbours. Three digits is also the most
 * precision that fits without the column setting its own width. The exact count
 * is one hover away — see {@link exactTokens} — so nothing is actually lost.
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
