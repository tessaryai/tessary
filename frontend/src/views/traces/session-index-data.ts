// SPDX-License-Identifier: Apache-2.0
// No `sort` param: sessions page by last_activity_at, most recent first.
import { useInfiniteQuery } from "@tanstack/react-query";
import type { SessionListItemView as SessionListItem } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";
import { filterParams, type TraceFilters } from "./index-data";

const PAGE_SIZE = 50;

/**
 * The sessions list with totals. `epoch` mirrors useTracesIndex's cache-busting nonce. A session is listed
 * when one of its traces passes every filter.
 */
export function useSessionsIndex(filters: TraceFilters, epoch = 0, enabled = true) {
  const api = useProjectApi();
  return useInfiniteQuery({
    queryKey: ["sessions-index", api.base, filters, epoch],
    queryFn: ({ pageParam }) =>
      api.listSessions({ limit: PAGE_SIZE, cursor: pageParam ?? undefined, include: "totals", ...filterParams(filters) }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.next_cursor ?? null,
    // A search the server stopped at its timeout would only run as long again, three more times.
    retry: false,
    enabled,
  });
}

/** The call site that touched the session most, plus a count of the others (`"cyrano-de-bergerac +2"`). */
export function sessionName(row: SessionListItem): string {
  if (!row.dominant_call_site_id) return row.id;
  const extra = (row.call_site_count ?? 1) - 1;
  return extra > 0 ? `${row.dominant_call_site_id} +${extra}` : row.dominant_call_site_id;
}

/**
 * First trace start to most recent activity, not a sum of trace latencies (traces overlap or leave gaps).
 * Multi-day values for long sessions are real.
 */
export function sessionDurationMs(row: SessionListItem): number | null {
  const start = new Date(row.started_at).getTime();
  const end = new Date(row.last_activity_at).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return null;
  return end - start;
}

export type { SessionListItem };
