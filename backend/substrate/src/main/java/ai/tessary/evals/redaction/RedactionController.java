// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.redaction;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.redaction.RedactionDtos.PreviewRequest;
import ai.tessary.evals.redaction.RedactionDtos.PreviewView;
import ai.tessary.evals.redaction.RedactionDtos.RuleListView;
import ai.tessary.evals.redaction.RedactionDtos.RuleView;
import ai.tessary.evals.redaction.RedactionDtos.SetEnabledRequest;
import ai.tessary.evals.redaction.RedactionDtos.UpsertRuleRequest;
import ai.tessary.evals.web.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The PII redaction rules playground: per-project CRUD over redaction rules plus a preview
 * endpoint that applies a (possibly unsaved) rule, or the whole active rule set, to sample text. The rules
 * managed here are exactly the rules the server-side write-path guard ({@code SubstrateWriter} →
 * {@code RedactionService}) applies before persistence.
 *
 * <p><b>Split by capability, not by page.</b> Reading the rules, toggling a built-in and previewing the
 * active set are open to every org — that is the "what do you strip before you store it" answer a security
 * review needs. Authoring a rule of your own (create / update / delete, and previewing an unsaved pattern)
 * requires {@link CustomRuleGate}, which resolves {@code custom_redaction_enabled}.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/redaction")
public class RedactionController {

    private final RedactionService service;
    private final TenantPathResolver resolver;
    private final CustomRuleGate customRules;

    public RedactionController(RedactionService service, TenantPathResolver resolver, CustomRuleGate customRules) {
        this.service = service;
        this.resolver = resolver;
        this.customRules = customRules;
    }

    @GetMapping("/rules")
    public ApiResponse<RuleListView> list(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        List<RuleView> rules =
                service.listRules(r.project().id()).stream().map(RuleView::of).toList();
        return ApiResponse.ok(new RuleListView(rules));
    }

    @PostMapping("/rules")
    public ApiResponse<RuleView> create(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody UpsertRuleRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        customRules.requireCustomRules(r.org().id());
        RedactionRuleRow row = service.createRule(
                r.project().id(),
                req.name(),
                req.pattern(),
                req.replacement(),
                req.enabledOrDefault(),
                req.sortOrderOrDefault());
        return ApiResponse.ok(RuleView.of(row));
    }

    @PutMapping("/rules/{ruleId}")
    public ApiResponse<RuleView> update(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String ruleId,
            @Valid @RequestBody UpsertRuleRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        customRules.requireCustomRules(r.org().id());
        RedactionRuleRow row = service.updateRule(
                r.project().id(),
                ruleId,
                req.name(),
                req.pattern(),
                req.replacement(),
                req.enabledOrDefault(),
                req.sortOrderOrDefault());
        return ApiResponse.ok(RuleView.of(row));
    }

    @PutMapping("/rules/{ruleId}/enabled")
    public ApiResponse<RuleView> setEnabled(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String ruleId,
            @Valid @RequestBody SetEnabledRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        RedactionRuleRow row = service.setEnabled(r.project().id(), ruleId, req.enabled());
        return ApiResponse.ok(RuleView.of(row));
    }

    @DeleteMapping("/rules/{ruleId}")
    public ApiResponse<RuleView> delete(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String ruleId) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        customRules.requireCustomRules(r.org().id());
        service.deleteRule(r.project().id(), ruleId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/preview")
    public ApiResponse<PreviewView> preview(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody PreviewRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        String pattern = req.pattern();
        String replacement = req.replacement();
        RedactionService.PreviewResult result;
        if (pattern == null || pattern.isBlank()) {
            result = service.previewAll(r.project().id(), req.sampleText());
        } else {
            // A pattern in the body means an unsaved rule is being tried out, which is authoring.
            customRules.requireCustomRules(r.org().id());
            result = service.preview(pattern, replacement == null ? "" : replacement, req.sampleText());
        }
        return ApiResponse.ok(PreviewView.of(result));
    }
}
