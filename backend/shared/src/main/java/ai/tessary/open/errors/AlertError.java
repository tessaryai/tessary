// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/** Error codes for the unified alert-rule feature and its delivery channels. */
public enum AlertError implements ErrorCode {
    RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "No alert rule '%s' for this project"),
    UNSUPPORTED_RULE_TYPE(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Unsupported alert rule_type: %s (expected 'threshold', 'digest', or 'brief')"),
    MISSING_CLASSIFIER(HttpStatus.UNPROCESSABLE_ENTITY, "%s"),
    MISSING_CRON(HttpStatus.UNPROCESSABLE_ENTITY, "%s"),
    INVALID_BASIS(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Invalid alert basis: %s (expected 'distinct_users', 'event_count', or 'every_match')"),
    // ---- channels ----
    CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "No alert channel '%s' for this project"),
    UNSUPPORTED_CHANNEL(HttpStatus.BAD_REQUEST, "Unsupported alert channel kind '%s'"),
    INVALID_CHANNEL_CONFIG(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid channel config: %s"),
    MISSING_SECRET_KEY(
            HttpStatus.FAILED_DEPENDENCY,
            "Alert channel credentials need tessary.secret-key to be configured (kind %s)");

    private final HttpStatus status;
    private final String template;

    AlertError(HttpStatus status, String template) {
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
        return AlertError.class;
    }
}
