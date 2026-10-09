// SPDX-License-Identifier: Apache-2.0
/*
 * The onboarding ladder, read once and shared.
 *
 * ONE derivation. Triage reads `stage` to pick which empty state it is in and the baseline counts to
 * draw the fitting one.
 *
 * Nothing here is stored progress. Every stage comes from state the product already keeps (an ingest
 * key, ingested observations, `metric_baseline` window state, findings, cases), so revoking the token or
 * deleting the traffic walks the ladder honestly back down.
 */
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useProjectApi } from "../../tenant/TenantContext";
import type { OnboardingProgress, OnboardingStage } from "../../api/types";

export interface Onboarding {
  data: OnboardingProgress | undefined;
  stage: OnboardingStage;
  /**
   * Baseline warm-up as a 0..1 fraction, or null when there is nothing to measure yet.
   *
   * The fullest window against `min_sample`, the leading edge rather than an average, because the
   * first bucket to arm is what ends the wait, and averaging over a long tail of rare tools would make
   * a project that is nearly ready look like it had barely started.
   */
  fittingProgress: number | null;
}

export function useOnboarding(): Onboarding {
  const api = useProjectApi();

  const query = useQuery({
    queryKey: ["onboarding", api.base],
    queryFn: api.onboarding,
    /*
     * Passive readers must not re-ask on every navigation.
     *
     * Triage mounts this purely to decide whether to show the warm-up panel, and
     * with react-query's default staleTime of 0 that re-issued the request on every arrival at the
     * surface, so moving Traces → Triage → Traces paid for it three times. The read is not free:
     * one of its five queries takes MIN/MAX over the project's whole observation table, so this
     * cache keeps us from asking at all when we already know the answer.
     *
     * A finished ladder is terminal in practice, so cache it for the session. It can still walk back
     * down (revoke the ingest key, delete the traffic), which is why this is a staleTime and not an
     * `enabled: false`: a remount after an invalidation still refetches.
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
