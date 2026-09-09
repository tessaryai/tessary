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
 * Billing surface for an org. There is no charging integration — self-serve billing left the repo in
 * epic 1 (#883) — so what this controller anchors is the RBAC contract: billing is a separate axis
 * from organization content, reachable only by a role holding {@link Permission#BILLING_MANAGE}
 * (owner or the dedicated billing role). A viewer/member/admin who can manage organization
 * content is deliberately 403'd here, and the gate survives a direct API call — not just
 * UI hiding. Any future charging surface slots behind this same gate.
 *
 * <p>The org-scoped usage read lives here — cross-project usage totals per unit over a
 * billing period — behind the same {@link Permission#BILLING_MANAGE} gate, so billing works from real
 * metered consumption. Per-project self-serve usage lives separately behind the project-token
 * {@code MeteringController}; org rollups are never exposed through a project token.
 */
@RestController
public class BillingController {

    private final TenantPathResolver resolver;
    private final MeteringService metering;

    public BillingController(TenantPathResolver resolver, MeteringService metering) {
        this.resolver = resolver;
        this.metering = metering;
    }

    /** One billable unit's total over the requested period (org-scoped). */
    public record UsageLine(String unit, long value) {}

    /**
     * The billing summary for an org. The {@code plan} and {@code billing_email} fields are permanently
     * dead placeholders left in the wire shape by #883; the {@code usage} block is the
     * cross-project metered consumption per unit over the requested period — the basis billing works from.
     */
    public record BillingSummary(
            @JsonProperty("org_id") String orgId,
            String plan,
            @JsonProperty("billing_email") String billingEmail,
            List<UsageLine> usage) {}

    /** The billing summary for an org. */
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
        // plan/billing_email are dead placeholders (#883); the usage block is real metered consumption.
        return ApiResponse.ok(new BillingSummary(r.org().id(), "free", null, usage));
    }

    /**
     * The org's LLM token + cost breakdown over {@code [from, to)} (both optional; an omitted bound is
     * open) — the detail behind the {@code llm_tokens} line above: input / output / cache-read /
     * cache-write kept apart, cost split by who paid, cut by product lane, by project and by model.
     *
     * <p>Read live off the per-call ledger rather than the closed-bucket rollups, so it answers "what
     * are we burning right now" instead of "what did we burn as of the last closed hour". Same
     * {@link Permission#BILLING_MANAGE} gate as the summary — per-call model and cost data is
     * commercial detail, not organization content.
     */
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
     * The same LLM consumption as {@link #getLlmUsage}, bucketed over time — what the usage chart
     * draws. {@code grain} is the bucket width ({@code hour} / {@code day} / {@code week}, default
     * {@code day}) and {@code group} the series axis ({@code none} / {@code lane} / {@code project} /
     * {@code model}, default {@code none}); {@code lane}, {@code project} and {@code model} narrow the
     * window to one key each, echoing back a key from the breakdown slices.
     *
     * <p>Unlike the breakdown read both bounds default rather than staying open ({@code to} to now,
     * {@code from} to 30 days before it) — a per-bucket read of an unbounded ledger is a full scan.
     * Same {@link Permission#BILLING_MANAGE} gate, for the same reason.
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
     * The org's Layer-2 triage spend broken down PER RULING, costliest first, with the cost of an
     * average ruling above it.
     *
     * <p>Launch requirement H2 is that spend be attributable "per org and per triage". The two
     * reads above answer the first: {@code by_lane} says the triage agent cost this org $X. Neither can
     * say across how many rulings, and after decision D14 — one path, always a sandbox — a ruling is an
     * agent session rather than a chat call, so the unit price is the number that decides whether the
     * filter is worth what it costs (H3), and the evidence any decision to cap would rest on (H4).
     *
     * <p>{@code limit} bounds only the listed rulings; the aggregate figures always cover the whole
     * window, so a truncated list cannot produce a truncated total. Same {@link Permission#BILLING_MANAGE}
     * gate as the other two — this is commercial detail, not organization content.
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
