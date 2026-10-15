// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.classifier.ClassifierDtos.ClassifierDailyVolumeView;
import ai.tessary.classifier.ClassifierDtos.ClassifierEventView;
import ai.tessary.classifier.ClassifierDtos.ClassifierHealthView;
import ai.tessary.classifier.ClassifierDtos.ClassifierView;
import ai.tessary.classifier.ClassifierDtos.GroundednessStatusView;
import ai.tessary.classifier.ClassifierDtos.SetCallSitesRequest;
import ai.tessary.classifier.ClassifierDtos.SetEnabledRequest;
import ai.tessary.classifier.ClassifierDtos.SetTuningRequest;
import ai.tessary.classifier.ClassifierDtos.TuningView;
import ai.tessary.classifier.detector.groundedness.GroundednessStatus;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.detection.DetectionTableRegistry;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Built-ins are seeded by the first successful generation run, never by a read. Evaluation is async
 * ({@link ClassifierWorker}).
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/classifiers")
public class ClassifierController {

    private final ClassifierService service;
    private final ClassifierReset reset;
    private final GroundednessStatus groundednessStatus;
    private final TenantPathResolver resolver;
    private final DetectionTableRegistry detectionTables;

    public ClassifierController(
            ClassifierService service,
            ClassifierReset reset,
            GroundednessStatus groundednessStatus,
            TenantPathResolver resolver,
            DetectionTableRegistry detectionTables) {
        this.service = service;
        this.reset = reset;
        this.groundednessStatus = groundednessStatus;
        this.resolver = resolver;
        this.detectionTables = detectionTables;
    }

    private ClassifierView view(String projectId, ClassifierRow row) {
        return ClassifierView.of(
                row, service.readiness(projectId, row), detectionTables.showsInTraceViews(row.detector()));
    }

    @GetMapping
    public ApiResponse<List<ClassifierView>> list(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        String projectId = r.project().id();
        return ApiResponse.ok(service.list(projectId).stream()
                .map(row -> view(projectId, row))
                .toList());
    }

    /** Makes a sweep failing for days visible in the product, not only in Loki. */
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
        ClassifierRow row = service.get(r.project().id(), id);
        return ApiResponse.ok(view(r.project().id(), row));
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
        ClassifierRow row = service.setEnabled(r.project().id(), id, req.enabled());
        return ApiResponse.ok(view(r.project().id(), row));
    }

    /** 409s while a sweep is running. */
    @PostMapping("/{id}/reset")
    public ApiResponse<ClassifierView> reset(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "reset a classifier");
        ClassifierRow row = reset.reset(r.project().id(), id, ctx.userEmail());
        return ApiResponse.ok(view(r.project().id(), row));
    }

    @PutMapping("/{id}/call-sites")
    public ApiResponse<ClassifierView> setCallSites(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @Valid @RequestBody SetCallSitesRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "choose the call sites a classifier runs on");
        ClassifierRow row = service.setCallSiteIds(r.project().id(), id, req.callSiteIds());
        return ApiResponse.ok(view(r.project().id(), row));
    }

    @GetMapping("/call-sites")
    public ApiResponse<List<String>> callSites(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.knownCallSiteIds(r.project().id()));
    }

    /** 422s for any other detector. */
    @GetMapping("/{id}/tuning")
    public ApiResponse<TuningView> getTuning(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(service.getTuning(r.project().id(), id));
    }

    /** Every field is clamped server-side, so the response may differ from what was submitted. */
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

    /** Read-only. 422s for any other classifier. */
    @GetMapping("/{id}/groundedness-status")
    public ApiResponse<GroundednessStatusView> getGroundednessStatus(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        String projectId = r.project().id();
        return ApiResponse.ok(groundednessStatus.view(projectId, service.get(projectId, id)));
    }

    /**
     * {@code tracking} returns only the HIGH-confidence subset; {@code discovery} or no {@code mode} returns
     * everything.
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

    /** One batch read for the whole classifier list, avoiding N+1 calls. */
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
