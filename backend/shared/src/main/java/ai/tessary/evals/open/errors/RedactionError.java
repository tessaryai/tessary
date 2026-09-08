// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.errors;

import org.springframework.http.HttpStatus;

/**
 * Error codes for the PII redaction feature — per-project redaction rules and the
 * authoring/testing playground. Wire form is {@code REDACTION.<NAME>}.
 */
public enum RedactionError implements ErrorCode {
    INVALID_PATTERN(HttpStatus.BAD_REQUEST, "Invalid redaction regex pattern: %s"),
    RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "Redaction rule not found: %s"),
    BUILT_IN_IMMUTABLE(HttpStatus.CONFLICT, "Built-in redaction rule %s can be disabled but not edited or deleted");

    private final HttpStatus status;
    private final String template;

    RedactionError(HttpStatus status, String template) {
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
        return RedactionError.class;
    }
}
