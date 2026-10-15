// SPDX-License-Identifier: Apache-2.0
package ai.tessary.onboarding;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One read for onboarding: which rung from listening to first case, how far into it, and what happens next, so a new
 * project's wait reads as progress rather than as broken. Supersedes {@code /substrate/status} for onboarding;
 * cookie-authed and project-scoped.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/onboarding")
public class OnboardingController {

    private final OnboardingRepository repo;
    private final TenantPathResolver resolver;

    public OnboardingController(OnboardingRepository repo, TenantPathResolver resolver) {
        this.repo = repo;
        this.resolver = resolver;
    }

    @GetMapping
    public ApiResponse<OnboardingView> progress(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        String projectId = r.project().id();

        boolean listening = repo.hasIngestKey(projectId);
        var traffic = repo.trafficWindow(projectId).orElse(null);
        var baselines = repo.baselineProgress(projectId);
        var findings = repo.findingProgress(projectId);
        var cases = repo.caseProgress(projectId);

        return ApiResponse.ok(new OnboardingView(
                stage(listening, traffic != null, baselines.armed(), findings.findings(), cases.triaged()),
                listening,
                traffic == null ? null : traffic.firstAt(),
                traffic == null ? null : traffic.lastAt(),
                baselines.buckets(),
                baselines.armed(),
                baselines.samplesInFlight(),
                baselines.bestWindowCount(),
                MetricDriftConfig.DEFAULT_MIN_SAMPLE,
                findings.findings(),
                findings.firstAt(),
                cases.cases(),
                cases.triaged(),
                cases.firstAt()));
    }

    /** Monotonic; see {@link OnboardingStage}. */
    private static OnboardingStage stage(
            boolean listening, boolean hasTraffic, long armedBuckets, long findings, long triagedCases) {
        if (triagedCases > 0) return OnboardingStage.CASE;
        if (findings > 0) return OnboardingStage.FINDING;
        if (armedBuckets > 0) return OnboardingStage.WATCHING;
        if (hasTraffic) return OnboardingStage.FITTING;
        if (listening) return OnboardingStage.LISTENING;
        return OnboardingStage.NOT_CONNECTED;
    }

    /**
     * @param baselineBuckets zero with traffic means the sweep has not folded it yet, not an error @param
     * baselineSamplesInFlight climbs every sweep, the evidence the wait is progress @param baselineMinSample the
     * DEFAULT ({@link MetricDriftConfig#DEFAULT_MIN_SAMPLE}); a tuned project differs, so describe it as the usual bar
     * @param cases includes a human pressing <i>Real deviation</i> @param triagedCases cases whose finding Layer 2
     * ruled on, the milestone
     */
    public record OnboardingView(
            OnboardingStage stage,
            boolean listening,
            @JsonProperty("first_trace_at") @Nullable String firstTraceAt,
            @JsonProperty("last_trace_at") @Nullable String lastTraceAt,
            @JsonProperty("baseline_buckets") long baselineBuckets,
            @JsonProperty("baseline_buckets_armed") long baselineBucketsArmed,
            @JsonProperty("baseline_samples_in_flight") long baselineSamplesInFlight,
            @JsonProperty("baseline_best_window_count") long baselineBestWindowCount,
            @JsonProperty("baseline_min_sample") int baselineMinSample,
            long findings,
            @JsonProperty("first_finding_at") @Nullable String firstFindingAt,
            long cases,
            @JsonProperty("triaged_cases") long triagedCases,

            @JsonProperty("first_triaged_case_at") @Nullable String firstTriagedCaseAt) {}
}
