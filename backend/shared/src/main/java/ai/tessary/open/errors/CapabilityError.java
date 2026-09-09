// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/**
 * Errors for the capability gate and the metered limits behind it.
 *
 * <p>{@code DISABLED} is the server-side capability gate — the capability is off for this org, by the
 * edition's default, by the org's own override, or by the hosted product's tier; the gate does not
 * distinguish, because to the caller they are the same fact. {@code UNKNOWN} and {@code UNAVAILABLE}
 * guard the capability-override write path. {@code QUOTA_EXCEEDED} is the hard limit a metered request
 * hits past its plan cap.
 *
 * <p><b>This was {@code PlanError} until epic 1 issue 9 (#845).</b> That issue strips paid vocabulary
 * out of {@code shared}, and "plan" is paid vocabulary — plans are the entitlement engine's, and the
 * engine is {@code tessary-paid/plan}. But four of the six constants could not leave with the name.
 * Three are thrown by the OPEN capability core, which the ledger keeps open (tessary-paid/OPEN-CORE.md:88):
 * {@code CapabilityService#require} raises {@code DISABLED}, and {@code CapabilityController}'s
 * override write raises {@code UNKNOWN} and {@code UNAVAILABLE}. The fourth, {@code QUOTA_EXCEEDED}, is
 * thrown only by the overlay but READ by open substrate ingest —
 * {@code OtlpGrpcTraceService} branches on it to map a quota refusal onto gRPC
 * {@code RESOURCE_EXHAUSTED} rather than an internal error — so it is the paid path's contract with the
 * open one, and under D2 (delete {@code tessary-paid/} and the rest must still build) it has to be
 * nameable here. The enum's previous javadoc and {@code tessary-paid/devdocs/reference/feature-flags.md} both said so
 * in advance: rename them, do not delete them.
 *
 * <p>The two that WERE only vocabulary — {@code UNKNOWN_PLAN} and {@code UNKNOWN_QUOTA}, write-side
 * guards on {@code org_plan} and {@code org_quota_override} — moved to
 * {@code PlanError} in {@code ai.tessary.paid.plan}, which keeps that simple name deliberately: {@link
 * ErrorCode#domain()} derives the wire domain from it, so those two codes stay {@code PLAN.*} byte for
 * byte.
 *
 * <p><b>The rename DOES change three wire codes the open build emits</b>, because the code is derived
 * and not declared: {@code PLAN.CAPABILITY_DISABLED} → {@code CAPABILITY.DISABLED},
 * {@code PLAN.UNKNOWN_CAPABILITY} → {@code CAPABILITY.UNKNOWN},
 * {@code PLAN.CAPABILITY_UNAVAILABLE} → {@code CAPABILITY.UNAVAILABLE}, and
 * {@code PLAN.QUOTA_EXCEEDED} → {@code CAPABILITY.QUOTA_EXCEEDED}. None of them appears in
 * {@code tessary-api.json}, in {@code schema.d.ts} or anywhere in {@code frontend/src} — error codes are
 * not part of the generated contract — so this is not a spec change and needs no regeneration. It IS
 * visible in every error body a client parses, emitted by {@code GlobalExceptionHandler}. The constant
 * prefixes are trimmed because the domain now carries them: {@code CAPABILITY.CAPABILITY_DISABLED}
 * would be a stutter.
 *
 * <p>{@code QUOTA_EXCEEDED} keeps its full name rather than becoming {@code QUOTA.EXCEEDED} in a second
 * enum. A separate {@code QuotaError} would buy a tidier string for a code no consumer reads, at the
 * price of a second type both editions have to keep in step.
 */
public enum CapabilityError implements ErrorCode {
    /** A capability that is off for this org. 403 — same shape as RBAC. */
    DISABLED(HttpStatus.FORBIDDEN, "The '%s' capability is not available for this organization"),
    /** A wire key that names no {@code Capability} (write-side guard on org_feature_flag). */
    UNKNOWN(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown capability '%s'"),
    /**
     * A capability this edition cannot honour, because the code behind it is not in the build. Refused rather
     * than silently ignored: an operator who "enables" an absent classifier and gets nothing back has been
     * told a lie, and would go looking for the bug in their data.
     */
    UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "The '%s' capability is not available in this edition"),
    /**
     * A per-org override written in an edition whose flag adapter does not read {@code org_feature_flag}. The
     * paid edition's only flag control is LaunchDarkly (epic 5, decision 3), so the row would be read by
     * nothing; refused rather than stored as a lie.
     */
    OVERRIDES_MANAGED_EXTERNALLY(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Per-organization overrides for '%s' are managed in LaunchDarkly in this edition"),
    /**
     * A metered request that would exceed the plan's quota. 402 — the monetization boundary.
     *
     * <p>Thrown only by {@code tessary-paid/plan}; declared here because open substrate ingest reads it
     * to map the refusal onto a gRPC status, and an open module cannot name a paid type.
     */
    QUOTA_EXCEEDED(HttpStatus.PAYMENT_REQUIRED, "Quota '%s' exceeded: %d of %d used for this period");

    private final HttpStatus status;
    private final String template;

    CapabilityError(HttpStatus status, String template) {
        this.status = status;
        this.template = template;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String template() {
        return template;
    }

    @Override
    public Class<? extends Enum<?>> declaringClass() {
        return CapabilityError.class;
    }
}
