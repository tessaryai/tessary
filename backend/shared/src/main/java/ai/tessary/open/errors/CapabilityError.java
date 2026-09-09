// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/**
 * Errors for the capability gate and the metered limits behind it. {@code DISABLED} is the
 * server-side gate saying a capability is off for this org; {@code UNKNOWN} and
 * {@code UNAVAILABLE} guard the capability-override write path; {@code QUOTA_EXCEEDED} is the
 * hard limit a metered request hits past its cap, declared here because substrate ingest reads
 * it to map a quota refusal onto a gRPC status even though nothing in this build throws it.
 */
public enum CapabilityError implements ErrorCode {
    /** A capability that is off for this org. 403, same shape as RBAC. */
    DISABLED(HttpStatus.FORBIDDEN, "The '%s' capability is not available for this organization"),
    /** A wire key that names no {@code Capability} (write-side guard on org_feature_flag). */
    UNKNOWN(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown capability '%s'"),
    /**
     * A capability whose code is not in this build. Refused rather than silently ignored: an
     * operator who "enables" an absent classifier and gets nothing back would go looking for the
     * bug in their data.
     */
    UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "The '%s' capability is not available in this edition"),
    /** A per-org override written where the active flag adapter won't read it back; refused rather than stored as a lie. */
    OVERRIDES_MANAGED_EXTERNALLY(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Per-organization overrides for '%s' are managed in LaunchDarkly in this edition"),
    /** A metered request that would exceed the plan's quota. 402, the monetization boundary. */
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
