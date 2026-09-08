// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.errors;

import org.springframework.http.HttpStatus;

/** Errors raised by the case surface — Triage and the case page. */
public enum CaseError implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "Case not found: %s"),
    // Closing a case is the one place the product asks for prose, because the reason is the whole
    // value of the record — "resolved" with no account of why teaches the next reader nothing.
    REASON_REQUIRED(HttpStatus.BAD_REQUEST, "Resolving a case needs a one-line reason"),
    ALREADY_RESOLVED(HttpStatus.CONFLICT, "Case %s is already resolved"),
    NOT_MUTED(HttpStatus.CONFLICT, "Case %s is not muted"),
    // Absorbing moves the DETECTOR's reference, so it needs one that can move. Two cases cannot: grader
    // degradation, which has no finding behind it at all, and SOP conformance, whose reference is an
    // authored rule — re-authoring it is a repo edit, not a button. Both can still be resolved and muted.
    NOT_ABSORBABLE(HttpStatus.CONFLICT, "Case %s has no detector reference that an absorb could move"),
    // A case left open by a classifier the organization no longer has. It still renders as history; every
    // action on it is withheld rather than failing deeper in with a less legible error.
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
