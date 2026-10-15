// SPDX-License-Identifier: Apache-2.0
// Nothing here is stored progress: every stage derives from existing state, so revoking the token or
// deleting traffic walks the ladder back down.
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useProjectApi } from "../../tenant/TenantContext";
import type { OnboardingProgress, OnboardingStage } from "../../api/types";

export interface Onboarding {
  data: OnboardingProgress | undefined;
  stage: OnboardingStage;
  /**
   * The fullest window against `min_sample`: the first bucket to arm ends the wait, and an average over
   * rare tools would understate it.
   */
  fittingProgress: number | null;
}

export function useOnboarding(): Onboarding {
  const api = useProjectApi();

  const query = useQuery({
    queryKey: ["onboarding", api.base],
    queryFn: api.onboarding,
    /*
     * One of its queries scans the whole observation table, so passive readers must not re-ask per
     * navigation. A staleTime, not `enabled: false`, because the ladder can still walk back down.
     */
    staleTime: (q) => (q.state.data?.stage === "case" ? Infinity : 60_000),
  });

  const data = query.data;
  const stage: OnboardingStage = data?.stage ?? "not_connected";

  return useMemo(() => {
    const minSample = data?.baseline_min_sample ?? 0;
    const best = data?.baseline_best_window_count ?? 0;
    return {
      data,
      stage,
      fittingProgress: minSample > 0 && data != null ? Math.min(1, best / minSample) : null,
    };
  }, [data, stage]);
}
