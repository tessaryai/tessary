// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/** Error codes for Settings → Data retention. */
public enum RetentionError implements ErrorCode {
    ABOVE_PLAN_CEILING(HttpStatus.UNPROCESSABLE_ENTITY, "Retention of %s days exceeds this plan's ceiling of %s days");

    private final HttpStatus status;
    private final String template;

    RetentionError(HttpStatus status, String template) {
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
        return RetentionError.class;
    }
}
