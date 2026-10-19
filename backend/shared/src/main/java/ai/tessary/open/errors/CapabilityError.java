// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/**
 * {@code QUOTA_EXCEEDED} is declared here because substrate ingest maps it to a gRPC status, though nothing in this
 * build throws it.
 */
public enum CapabilityError implements ErrorCode {
    /** 403, same shape as RBAC. */
    DISABLED(HttpStatus.FORBIDDEN, "The '%s' capability is not available for this organization"),
    /** 403: the deployment's license lists the capability false, so no override can turn it on. */
    LICENSED(HttpStatus.FORBIDDEN, "The '%s' capability is not included in this deployment's license"),
    /** Write-side guard on org_feature_flag. */
    UNKNOWN(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown capability '%s'"),
    /** 402, the monetization boundary. */
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
