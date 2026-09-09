// SPDX-License-Identifier: Apache-2.0
/*
 * The onboarding ladder, read once and shared.
 *
 * ONE derivation. Triage reads `stage` to pick which empty state it is in and the baseline counts to
 * draw the fitting one; the connect gate reads it to know when to stand down. Nothing here is stored
 * progress, which is the property the old `setupProgress.ts` had and the reason it was trustworthy.
 *
 * Nothing here is stored progress. Every stage comes from state the product already keeps (an ingest
 * key, ingested observations, `metric_baseline` window state, findings, cases), so revoking the token or
 * deleting the traffic walks the ladder honestly back down.
 */
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useProjectApi } from "../../tenant/TenantContext";
import type { OnboardingProgress, OnboardingStage } from "../../api/types";

/** The ladder, in order. Mirrors the backend `OnboardingStage` enum. */
export const STAGES: OnboardingStage[] = [
  "not_connected",
  "listening",
  "fitting",
  "watching",
  "finding",
  "case",
];

export interface Onboarding {
  data: OnboardingProgress | undefined;
  stage: OnboardingStage;
  /** Index of `stage` in {@link STAGES}: how many rungs are behind us. */
  stageIndex: number;
  /** True until a project has produced a triaged case. The setup surface stays useful until then. */
  warmingUp: boolean;
  /**
   * Baseline warm-up as a 0..1 fraction, or null when there is nothing to measure yet.
   *
   * The fullest window against `min_sample`, the leading edge rather than an average, because the
   * first bucket to arm is what ends the wait, and averaging over a long tail of rare tools would make
   * a project that is nearly ready look like it had barely started.
   */
  fittingProgress: number | null;
  isLoading: boolean;
  error: unknown;
}

/**
 * @param poll while the surface is on screen the user is in their terminal wiring the exporter RIGHT
 * NOW, so the read polls, in the background too, since React Query pauses intervals on blur and the
 * milestone would otherwise only land on refocus. Passive readers (Triage) leave it off.
 */
export function useOnboarding({ poll = false }: { poll?: boolean } = {}): Onboarding {
  const api = useProjectApi();

  const query = useQuery({
    queryKey: ["onboarding", api.base],
    queryFn: api.onboarding,
    // Stop polling once the ladder is finished: there is nothing left to watch for.
    refetchInterval: (q) => (poll && q.state.data?.stage !== "case" ? 5000 : false),
    refetchIntervalInBackground: poll,
    /*
     * Passive readers must not re-ask on every navigation.
     *
     * Triage mounts this with `poll: false` purely to decide whether to show the warm-up panel, and
     * with react-query's default staleTime of 0 that re-issued the request on every arrival at the
     * surface, so moving Traces → Triage → Traces paid for it three times. The read is not free:
     * one of its five queries takes MIN/MAX over the project's whole observation table, so this
     * cache keeps us from asking at all when we already know the answer.
     *
     * A finished ladder is terminal in practice, so cache it for the session. It can still walk back
     * down (revoke the ingest key, delete the traffic), which is why this is a staleTime and not an
     * `enabled: false`: a remount after an invalidation still refetches, and Setup's 5 s poll is
     * untouched. The value is derived from the same `stage` the poll reads, so the two can never
     * disagree about when the ladder is done.
     */
    staleTime: (q) => (q.state.data?.stage === "case" ? Infinity : 60_000),
  });

  const data = query.data;
  const stage: OnboardingStage = data?.stage ?? "not_connected";

  return useMemo(() => {
    const stageIndex = Math.max(0, STAGES.indexOf(stage));
    const minSample = data?.baseline_min_sample ?? 0;
    const best = data?.baseline_best_window_count ?? 0;
    return {
      data,
      stage,
      stageIndex,
      warmingUp: stage !== "case",
      fittingProgress: minSample > 0 && data != null ? Math.min(1, best / minSample) : null,
      isLoading: query.isLoading,
      error: query.error,
    };
  }, [data, stage, query.isLoading, query.error]);
}
