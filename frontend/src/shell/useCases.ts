// SPDX-License-Identifier: Apache-2.0
/*
 * The Triage nav badge's data source — the ONLY nav count in the app.
 *
 * Reads `GET {projectBase}/cases` and counts what is open. Muted cases are
 * deliberately excluded: muting one is exactly the act of saying "stop telling
 * me about this", and a badge that kept counting it would ignore that.
 *
 * Resilient by design: while loading or on error the badge simply doesn't render
 * (count 0) — the shell never blocks on the cases endpoint.
 */
import { useQuery } from "@tanstack/react-query";
import { useProjectApi } from "../tenant/TenantContext";

export function useCaseCounts(): { open: number; isLoading: boolean } {
  const api = useProjectApi();
  const q = useQuery({
    queryKey: ["cases", api.base],
    queryFn: api.getTriage,
    retry: false,
    staleTime: 30_000,
  });
  return { open: q.data?.cases.length ?? 0, isLoading: q.isLoading };
}
