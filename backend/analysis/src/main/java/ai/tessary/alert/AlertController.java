// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import ai.tessary.alert.AlertDtos.AlertEventView;
import ai.tessary.alert.AlertDtos.AlertRuleView;
import ai.tessary.alert.AlertDtos.PolicyView;
import ai.tessary.alert.AlertDtos.SetEnabledRequest;
import ai.tessary.alert.AlertDtos.SnoozeRequest;
import ai.tessary.alert.AlertDtos.UpsertAlertRuleRequest;
import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.TenantPathResolver.Resolved;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unified alert config + read surface: one {@code alert_rule} CRUD API across both grains
 * (per-classifier threshold rules and per-project digest/brief roll-up schedules) plus a fired-alert read.
 * Evaluation + firing is async ({@link AlertWorker}). Snooze/disable here never touch
 * the underlying classifier.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}")
public class AlertController {

    private static final int DEFAULT_EVENT_LIMIT = 200;

    private final AlertService service;
    private final TenantPathResolver resolver;
    private final CapabilityService capabilities;
    private final ObjectMapper mapper;

    public AlertController(
            AlertService service, TenantPathResolver resolver, CapabilityService capabilities, ObjectMapper mapper) {
        this.service = service;
        this.resolver = resolver;
        this.capabilities = capabilities;
        this.mapper = mapper;
    }

    /** Alerts are a paid capability ({@link Feature#ALERTS}): resolve the project AND require the entitlement. */
    private Resolved requireCapableProject(TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        capabilities.require(r.org().id(), Capability.ALERTS);
        return r;
    }

    // ---- rule CRUD --------------------------------------------------------------------------------

    @GetMapping("/alert-rules")
    public ApiResponse<List<AlertRuleView>> listRules(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = requireCapableProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(
                service.listRules(r.project().id()).stream().map(this::view).toList());
    }

    @GetMapping("/alert-rules/{id}")
    public ApiResponse<AlertRuleView> getRule(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = requireCapableProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(view(service.getRule(r.project().id(), id)));
    }

    @PutMapping("/alert-rules")
    public ApiResponse<AlertRuleView> upsertRule(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody UpsertAlertRuleRequest req) {
        var r = requireCapableProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(view(service.upsertRule(r.project().id(), req)));
    }

    @PutMapping("/alert-rules/{id}/enabled")
    public ApiResponse<AlertRuleView> setRuleEnabled(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @Valid @RequestBody SetEnabledRequest req) {
        var r = requireCapableProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(view(service.setRuleEnabled(r.project().id(), id, req.enabled())));
    }

    @PutMapping("/alert-rules/{id}/snooze")
    public ApiResponse<AlertRuleView> snoozeRule(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id,
            @Valid @RequestBody SnoozeRequest req) {
        var r = requireCapableProject(ctx, orgSlug, projectSlug);
        return ApiResponse.ok(view(service.snoozeRule(r.project().id(), id, req.snoozedUntil())));
    }

    @DeleteMapping("/alert-rules/{id}")
    public ApiResponse<AlertRuleView> deleteRule(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String id) {
        var r = requireCapableProject(ctx, orgSlug, projectSlug);
        AlertRuleView deleted = view(service.getRule(r.project().id(), id));
        if (!service.deleteRule(r.project().id(), id)) {
            throw new TessaryException(AlertError.RULE_NOT_FOUND, id);
        }
        return ApiResponse.ok(deleted);
    }

    /**
     * A rule as the wire sees it. Only a case-opened rule carries a policy; parsing {@code attributes}
     * for the others would surface a cadence and a quiet window on a rule that has neither, which the
     * notification UI would then render as editable settings that do nothing.
     */
    private AlertRuleView view(AlertRuleRow row) {
        if (!AlertRuleRow.RuleType.CASE_OPENED.equals(row.ruleType())) return AlertRuleView.of(row);
        return AlertRuleView.of(row, PolicyView.of(AlertPolicy.of(mapper, row.attributes())));
    }

    // ---- fired alerts -----------------------------------------------------------------------------

    @GetMapping("/alert-events")
    public ApiResponse<List<AlertEventView>> fired(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @RequestParam(name = "ruleId", required = false) @Nullable String ruleId,
            @RequestParam(name = "classifierId", required = false) @Nullable String classifierId,
            @RequestParam(name = "limit", defaultValue = "200") int limit) {
        var r = requireCapableProject(ctx, orgSlug, projectSlug);
        String pid = r.project().id();
        int cap = limit <= 0 ? DEFAULT_EVENT_LIMIT : Math.min(limit, 1000);
        List<AlertEventRow> fired;
        if (ruleId != null && !ruleId.isBlank()) {
            fired = service.firedByRule(pid, ruleId, cap);
        } else if (classifierId != null && !classifierId.isBlank()) {
            fired = service.firedByClassifier(pid, classifierId, cap);
        } else {
            fired = service.firedByProject(pid, cap);
        }
        return ApiResponse.ok(fired.stream().map(AlertEventView::of).toList());
    }
}
