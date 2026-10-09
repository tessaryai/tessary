// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/** Errors raised by the case surface — Triage and the case page. */
public enum CaseError implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "Case not found: %s"),
    // Closing a case is the one place the product asks for prose, because the reason is the whole
    // value of the record — "resolved" with no account of why teaches the next reader nothing.
    REASON_REQUIRED(HttpStatus.BAD_REQUEST, "Resolving a case needs a one-line reason"),
    ALREADY_RESOLVED(HttpStatus.CONFLICT, "Case %s is already resolved"),
    NOT_MUTED(HttpStatus.CONFLICT, "Case %s is not muted"),
    // Absorbing moves the DETECTOR's reference, so it needs one that can move. Some cases have none (see
    // CaseService#absorbable); they can still be resolved and muted.
    NOT_ABSORBABLE(HttpStatus.CONFLICT, "Case %s has no detector reference that an absorb could move"),
    // A disposition says what a resolved frustration case turned out to be, and each one changes what the
    // classifier counts next. No other case has anything it would change, so it is refused rather than stored.
    DISPOSITION_NOT_APPLICABLE(HttpStatus.BAD_REQUEST, "Case %s takes no disposition; only a frustration case does"),
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
