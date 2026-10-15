// SPDX-License-Identifier: Apache-2.0
// The Triage nav badge's count. While loading or on error it reads 0: the shell never blocks on it.
import { useQuery } from "@tanstack/react-query";
import { useProjectApi } from "../tenant/TenantContext";
import { casesQuery } from "./shellQueries";

export function useCaseCounts(): { open: number } {
  const api = useProjectApi();
  const q = useQuery({
    ...casesQuery(api),
    retry: false,
    staleTime: 30_000,
  });
  return { open: q.data?.cases.length ?? 0 };
}
