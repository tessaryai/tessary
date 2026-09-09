// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/** Errors raised by the signal RCA (root-cause analysis) surface. */
public enum RcaError implements ErrorCode {
    NOT_A_MOVER(
            HttpStatus.CONFLICT, "No significant movement for %s right now — an RCA needs a live mover to snapshot"),
    SUBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "RCA subject not found: %s"),
    // Deliberately layer-neutral: this fires for launcher HTTP errors and unparseable agent output
    // as well as genuine model failures, and the old "synthesis LLM call failed" wording sent an
    // investigation after the model when the real fault was a sidecar that was not running.
    UPSTREAM_FAILED(HttpStatus.BAD_GATEWAY, "RCA analysis failed: %s"),
    LAUNCHER_UNREACHABLE(
            HttpStatus.BAD_GATEWAY,
            "RCA could not reach the analysis launcher at %s — it is unreachable or not running"),
    // A deployment fault, not a user one: the dossier carries the claim and every row behind it is read
    // through MCP, so a run without that door can only paraphrase the detector back. Fail loudly rather
    // than shipping a report built on nothing.
    NO_EVIDENCE_DOOR(HttpStatus.BAD_GATEWAY, "RCA cannot reach the evidence: %s");

    private final HttpStatus status;
    private final String template;

    RcaError(HttpStatus status, String template) {
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
        return RcaError.class;
    }
}
