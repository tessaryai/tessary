// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

public enum CaseError implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "Case not found: %s"),
    ALREADY_RESOLVED(HttpStatus.CONFLICT, "Case %s is already closed"),
    // Absorbing moves the detector's reference; cases with none can still be closed.
    NOT_ABSORBABLE(HttpStatus.CONFLICT, "Case %s has no detector reference that an absorb could move"),
    // Each disposition changes what the frustration classifier counts next; no other case has anything to change.
    DISPOSITION_NOT_APPLICABLE(HttpStatus.BAD_REQUEST, "Case %s takes no disposition; only a frustration case does"),
    // The case still renders as history, but actions are refused here rather than failing deeper with a less legible
    // error.
    DETECTOR_UNAVAILABLE(HttpStatus.CONFLICT, "The %s classifier is not available to this organization");

    private final HttpStatus status;
    private final String template;

    CaseError(HttpStatus status, String template) {
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
        return CaseError.class;
    }
}
