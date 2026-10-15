// SPDX-License-Identifier: Apache-2.0
package ai.tessary.billing;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.metering.LlmUsageFilter;
import ai.tessary.metering.MeteringDtos.LlmUsageSeriesView;
import ai.tessary.metering.MeteringDtos.LlmUsageView;
import ai.tessary.metering.MeteringDtos.TriageSpendView;
import ai.tessary.metering.MeteringService;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Billing is a separate RBAC axis from org content: only {@link Permission#BILLING_MANAGE} (owner or the billing role)
 * reaches it, enforced on the API, not just the UI. Org rollups are never exposed through a project token.
 */
@RestController
public class BillingController {

    private final TenantPathResolver resolver;
    private final MeteringService metering;

    public BillingController(TenantPathResolver resolver, MeteringService metering) {
        this.resolver = resolver;
        this.metering = metering;
    }

    public record UsageLine(String unit, long value) {}

    /** {@code plan} and {@code billing_email} are dead placeholders in the wire shape. */
    public record BillingSummary(
            @JsonProperty("org_id") String orgId,
            String plan,
            @JsonProperty("billing_email") String billingEmail,
            List<UsageLine> usage) {}

    @GetMapping("/api/orgs/{orgSlug}/billing")
    public ApiResponse<BillingSummary> getBilling(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.BILLING_MANAGE, "access billing");
        List<UsageLine> usage = metering.orgTotals(r.org().id(), from, to).stream()
                .map(t -> new UsageLine(t.metric(), t.value()))
                .toList();
        return ApiResponse.ok(new BillingSummary(r.org().id(), "free", null, usage));
    }

    /** Read live off the per-call ledger rather than closed-bucket rollups. An omitted bound is open. */
    @GetMapping("/api/orgs/{orgSlug}/usage/llm")
    public ApiResponse<LlmUsageView> getLlmUsage(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.BILLING_MANAGE, "access billing");
        return ApiResponse.ok(metering.orgLlmUsage(r.org().id(), from, to));
    }

    /**
     * Bounds default ({@code to} now, {@code from} 30 days earlier), since an unbounded per-bucket read is a full scan.
     */
    @GetMapping("/api/orgs/{orgSlug}/usage/llm/series")
    public ApiResponse<LlmUsageSeriesView> getLlmUsageSeries(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable String grain,
            @RequestParam(required = false) @Nullable String group,
            @RequestParam(required = false) @Nullable String lane,
            @RequestParam(required = false) @Nullable String project,
            @RequestParam(required = false) @Nullable String model) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.BILLING_MANAGE, "access billing");
        return ApiResponse.ok(metering.orgLlmUsageSeries(
                r.org().id(), from, to, grain, group, new LlmUsageFilter(lane, project, model)));
    }

    /**
     * Per-ruling cost decides whether the triage filter is worth it. {@code limit} bounds only the listed rulings;
     * aggregates cover the whole window.
     */
    @GetMapping("/api/orgs/{orgSlug}/usage/llm/triages")
    public ApiResponse<TriageSpendView> getTriageSpend(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        var r = resolver.requireOrg(ctx, orgSlug);
        r.require(Permission.BILLING_MANAGE, "access billing");
        return ApiResponse.ok(metering.orgTriageSpend(r.org().id(), from, to, Math.clamp(limit, 1, 500)));
    }
}
