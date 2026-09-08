// SPDX-License-Identifier: Apache-2.0
/*
 * Sessions index — data layer over the sessions read API, the grouped sibling of index-data.ts.
 *
 * One difference from useTracesIndex worth naming: there is no `sort` param here, deliberately —
 * see api/client.ts's listSessions doc comment. Paging is always by last_activity_at, most recent
 * first, and this hook never asks for anything else.
 */
import { useInfiniteQuery } from "@tanstack/react-query";
import type { SessionListItemView as SessionListItem } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";

const PAGE_SIZE = 50;

/**
 * The sessions list, page by page, with totals (`include=totals`) — the grouped table always wants
 * rollup numbers on the row, unlike the flat list's opt-in per-trace rollups which are cheap by
 * default. `epoch` mirrors useTracesIndex's cache-busting nonce so the two toggles' refresh wiring
 * stays identical.
 */
export function useSessionsIndex(epoch = 0, enabled = true) {
  const api = useProjectApi();
  return useInfiniteQuery({
    queryKey: ["sessions-index", api.base, epoch],
    queryFn: ({ pageParam }) =>
      api.listSessions({ limit: PAGE_SIZE, cursor: pageParam ?? undefined, include: "totals" }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.next_cursor ?? null,
    enabled,
  });
}

/**
 * Name for a session row: the call site that touched it most, plus a count when more than one did.
 * `"cyrano-de-bergerac +2"` reads as "mostly this, and two others" — matching the mock's approved
 * naming, ties broken server-side by recency before this ever sees the row.
 */
export function sessionName(row: SessionListItem): string {
  if (!row.dominant_call_site_id) return row.id;
  const extra = (row.call_site_count ?? 1) - 1;
  return extra > 0 ? `${row.dominant_call_site_id} +${extra}` : row.dominant_call_site_id;
}

/**
 * How long the session ran, for the Latency column — the same "first input, last output" bracket the
 * Input/Output columns use, one level up: the span from the session's first trace starting to its most
 * recent activity, not a sum of individual trace latencies (traces in a session can overlap or leave
 * gaps, so a sum would misstate what actually happened). Read this figure knowing what it is: a
 * multi-day span for a long-running session is real, not a bug, and reads very differently from a
 * single trace's response-time latency even though it shares a column and a unit.
 */
export function sessionDurationMs(row: SessionListItem): number | null {
  const start = new Date(row.started_at).getTime();
  const end = new Date(row.last_activity_at).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return null;
  return end - start;
}

export type { SessionListItem };
