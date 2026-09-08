// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.classifier.ClassifierDtos.ClassifierDailyVolumeView;
import ai.tessary.evals.classifier.ClassifierDtos.ClassifierEventView;
import ai.tessary.evals.classifier.ClassifierDtos.ClassifierHealthView;
import ai.tessary.evals.classifier.ClassifierDtos.ClassifierMetricsView;
import ai.tessary.evals.classifier.ClassifierDtos.ClassifierView;
import ai.tessary.evals.classifier.ClassifierDtos.SetEnabledRequest;
import ai.tessary.evals.classifier.ClassifierDtos.SetModeRequest;
import ai.tessary.evals.classifier.ClassifierDtos.SetTuningRequest;
import ai.tessary.evals.classifier.ClassifierDtos.ToolErrorRateView;
import ai.tessary.evals.classifier.ClassifierDtos.TuningView;
import ai.tessary.evals.classifier.worker.ClassifierJobRow;
import ai.tessary.evals.classifier.worker.ClassifierWorker;
import ai.tessary.evals.tenant.rbac.Permission;
import ai.tessary.evals.web.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Classifier lifecycle surface: list the project's classifier definitions (built-ins are seeded by the
 * first successful generation run — never by a read), toggle enable/disable, and read detections. Evaluation is async ({@link ClassifierWorker}); this
 * controller is the definition + read API.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/classifiers")
public class ClassifierController {

    private static final int DEFAULT_EVENT_LIMIT = 200;

    private final ClassifierService service;
    private final TenantPathResolver resolver;

    public ClassifierController(ClassifierService service, TenantPathResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    @GetMapping
    public ApiResponse<List<ClassifierView>> list(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(
                service.list(r.project().id()).stream().map(ClassifierView::of).toList());
    }

    /**
     * Per-signal sweep-job health for the project (gh#545) — status/attempts/last error/last-swept-at, so
     * a signal whose sweep has been failing for days is observable from the product instead of only
     * from Loki. A healthy signal reports {@link ClassifierJobRow#PENDING}/{@link ClassifierJobRow#DONE} with no
     * {@code lastError}; a signal fast-failing its sweep reports {@link ClassifierJobRow#FAILED} (or the
     * dead-letter state once gh#531 lands) with the error text and attempt count.
     */
    @GetMapping("/health")
    public ApiResponse<List<ClassifierHealthView>> health(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.health(r.project().id()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ClassifierView> get(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(ClassifierView.of(service.get(r.project().id(), id)));
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<ClassifierView> setEnabled(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @Valid @RequestBody SetEnabledRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "enable or disable signals");
        return ApiResponse.ok(ClassifierView.of(service.setEnabled(r.project().id(), id, req.enabled())));
    }

    /**
     * Set the classifier's operating point: {@code discovery} (high recall) or {@code tracking}
     * (high precision). One definition, two modes — mirrors {@link #setEnabled}.
     */
    @PutMapping("/{id}/mode")
    public ApiResponse<ClassifierView> setMode(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @Valid @RequestBody SetModeRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "change a classifier's operating mode");
        return ApiResponse.ok(ClassifierView.of(service.setMode(r.project().id(), id, req.mode())));
    }

    /**
     * The window/threshold operating point for a metric-drift classifier (cost_drift or
     * duration_drift) — the numbers a tenant can tune to see deviation measured over a different
     * window. 422s for any other detector, which has nothing here to read.
     */
    @GetMapping("/{id}/tuning")
    public ApiResponse<TuningView> getTuning(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.getTuning(r.project().id(), id));
    }

    /**
     * Tune a metric-drift classifier's window/threshold operating point. Merges into the existing
     * config rather than replacing it wholesale, and every field is clamped server-side — the response
     * is the value actually in effect, which may differ from what was submitted.
     */
    @PutMapping("/{id}/tuning")
    public ApiResponse<TuningView> setTuning(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @Valid @RequestBody SetTuningRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "tune a classifier's window/threshold operating point");
        return ApiResponse.ok(service.setTuning(
                r.project().id(), id, req.windowTargetCount(), req.windowMaxHours(), req.minSample(), req.w1Floor()));
    }

    @GetMapping("/events")
    public ApiResponse<List<ClassifierEventView>> events(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.events(r.project().id(), DEFAULT_EVENT_LIMIT));
    }

    /**
     * Detections for a signal, optionally at a specific operating point via {@code ?mode=}:
     * {@code tracking} surfaces only the precise HIGH-confidence subset; {@code discovery} (or an
     * absent {@code mode}) surfaces the full high-recall set. So the SAME classifier's two modes are
     * observable side by side from one corpus.
     */
    @GetMapping("/{id}/events")
    public ApiResponse<List<ClassifierEventView>> eventsForClassifier(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @RequestParam(name = "mode", required = false) @Nullable String mode,
            @RequestParam(name = "limit", defaultValue = "200") int limit) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.eventsForClassifier(r.project().id(), id, mode, limit));
    }

    /**
     * Per-tool failure rates for the project's tool calls: {@code failed/total} grouped by tool name,
     * worst-first. A live derived read over {@code tool_call}; the signal {@code id} scopes/guards the
     * request.
     *
     * <p>This was the rate-bearing surface for the {@code tool_error} built-in, which no longer exists
     * (migration {@code 0030}). It survives because it never depended on that classifier — it reads the
     * raw {@code tool_call} rows directly — but the project-wide tool-error rate a user sees now comes
     * from the {@code vitals} slice, which windows it, scopes it per call site and compares it against
     * a baseline. Treat this as the unwindowed all-time cut, not the product surface.
     */
    @GetMapping("/{id}/tool-error-rates")
    public ApiResponse<List<ToolErrorRateView>> toolErrorRates(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.toolErrorRates(r.project().id(), id).stream()
                .map(ToolErrorRateView::of)
                .toList());
    }

    /**
     * The per-mode precision/recall surfacing: how many detections
     * discovery fires (high recall) vs how many tracking fires (high precision), plus the
     * low-confidence delta between them. Computed from the one persisted corpus — no re-sweep.
     */
    @GetMapping("/{id}/metrics")
    public ApiResponse<ClassifierMetricsView> metrics(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(
                ClassifierMetricsView.of(service.metrics(r.project().id(), id)));
    }

    /**
     * Per-classifier daily detected-trace counts over the trailing {@code days} UTC calendar days
     * (default 7, clamped 1–30), plus per-day project trace totals — one batch read for the whole
     * classifier list, so the UI can render volume bars and "% of all traces" without N+1 calls.
     */
    @GetMapping("/metrics/daily")
    public ApiResponse<ClassifierDailyVolumeView> dailyMetrics(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(name = "days", defaultValue = "7") int days) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(
                ClassifierDailyVolumeView.of(service.dailyVolume(r.project().id(), days)));
    }
}
