// SPDX-License-Identifier: Apache-2.0
/*
 * The Prompts reads, seeded into a test's QueryClient. A prompt write that forgets one leaves the list, the detail
 * or the open version stale for the 30 s staleTime, which no render of the writing component shows. A test seeds
 * them, does the write, and checks which were marked stale.
 */
import type { QueryClient } from "@tanstack/react-query";

const BASE = "/api/orgs/acme/projects/default";
// The list key carries a search no page test opens, so a seeded `{}` never stands in for a page's own read.
const KEYS = [["prompts", BASE, { q: "seeded" }],["prompt", BASE, "p-1"], ["prompt-version", BASE, "p-1", 2]];

export function seedPromptReads(qc: QueryClient) {
  for (const key of KEYS) qc.setQueryData(key, {});
}

/** Whether each seeded read was marked stale: the list, prompt p-1's detail, then its version 2. */
export function promptReadsStale(qc: QueryClient): boolean[] {
  return KEYS.map((key) => qc.getQueryState(key)?.isInvalidated ?? false);
}
