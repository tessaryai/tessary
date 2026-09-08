// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.onboarding;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.classifier.metric.MetricDriftConfig;
import ai.tessary.evals.web.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One read for the whole of onboarding: where a project is on the ladder from <i>listening</i> to
 * <i>first case</i>, and the numbers behind whichever rung it is on.
 *
 * <p>Launch segment G replaced a four-step grader wizard with a single step — point an exporter at us —
 * and everything after that step is waiting rather than doing. This endpoint is what makes the waiting
 * legible: it says which rung, how far into it, and what will happen next, so the surface can show
 * progress instead of an empty page (G3), and can be honest that a new project genuinely sees nothing
 * for a while rather than looking broken (G4).
 *
 * <p><b>It supersedes {@code /substrate/status} for onboarding.</b> That endpoint answers one bit —
 * has anything landed — which was the whole question while "first value" meant a graded run. Under the
 * launch definition first value is a triaged case, and the interesting part of the journey is
 * entirely after the first trace. {@code /substrate/status} stays for the untagged-span nudge, which is a
 * different question.
 *
 * <p>Cookie-authed and project-scoped like {@code SubstrateController}: this is a browser read for the
 * person who just wired the exporter, not part of the programmatic surface.
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

    /**
     * The furthest rung reached — see {@link OnboardingStage} for why this is monotonic rather than a
     * set of independent booleans.
     */
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
     * The whole onboarding read.
     *
     * @param stage the furthest rung reached — what the surface renders
     * @param listening whether a key exists that an exporter could push with
     * @param firstTraceAt event time of the earliest observation, null before any
     * @param lastTraceAt event time of the latest observation — with {@code firstTraceAt}, how much
     *     history the detectors have to work with
     * @param baselineBuckets {@code (bucket, measure)} pairs the sweep has opened. Zero WITH traffic is a
     *     real state and not an error: the sweep has not folded this project's traffic yet.
     * @param baselineBucketsArmed how many can compare — the number that ends the warm-up
     * @param baselineSamplesInFlight samples sitting in windows that have not closed. The evidence that
     *     the wait is progress: it climbs every sweep even while nothing else changes.
     * @param baselineBestWindowCount the fullest single window, so the surface can show the leading edge
     *     rather than an average that hides it
     * @param baselineMinSample samples a window needs before it can be compared — the DEFAULT
     *     ({@link MetricDriftConfig#DEFAULT_MIN_SAMPLE}), which is what a project that has not tuned its
     *     classifier runs on. A project that has edited {@code min_sample} will differ; the surface must
     *     therefore describe this as the usual bar rather than promise it.
     * @param findings findings written by any detector
     * @param firstFindingAt event time of the earliest, null before any
     * @param cases cases opened, by any arm including a human pressing <i>Real deviation</i>
     * @param triagedCases cases whose finding Layer 2 ruled on — the milestone (decision D9)
     * @param firstTriagedCaseAt when the first of those opened; the instant TTFV stops
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
