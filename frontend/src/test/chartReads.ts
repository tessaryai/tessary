// SPDX-License-Identifier: Apache-2.0
/*
 * The Classifiers page's chart reads, seeded into a test's QueryClient. Nothing on the configure page
 * observes them, so a write there that forgets them leaves no trace in its own render: the Configure menu
 * and the cards are simply stale on the next visit to Classifiers, for as long as the 30 s staleTime runs.
 * A test seeds them, does the write, and checks both were marked stale.
 */
import type { QueryClient } from "@tanstack/react-query";

const BASE = "/api/orgs/acme/projects/default";
const KEYS = [
  ["classifier-chart-scopes", BASE, 7],
  ["classifier-charts", BASE, "call_site", "cs-answer", 7],
];

export function seedChartReads(qc: QueryClient) {
  for (const key of KEYS) qc.setQueryData(key, {});
}

/** Whether each seeded read was marked stale: the scopes (and so the menu), then one call site's cards. */
export function chartReadsStale(qc: QueryClient): boolean[] {
  return KEYS.map((key) => qc.getQueryState(key)?.isInvalidated ?? false);
}
