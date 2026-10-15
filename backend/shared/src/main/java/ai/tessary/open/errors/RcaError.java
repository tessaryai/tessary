// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

public enum RcaError implements ErrorCode {
    SUBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "RCA subject not found: %s"),
    // Layer-neutral on purpose: it also fires for launcher HTTP errors and unparseable agent output, not only model
    // failures.
    UPSTREAM_FAILED(HttpStatus.BAD_GATEWAY, "RCA analysis failed: %s"),
    LAUNCHER_UNREACHABLE(
            HttpStatus.BAD_GATEWAY,
            "RCA could not reach the analysis launcher at %s — it is unreachable or not running"),
    // A deployment fault: every evidence row is read through MCP, so a run without it could only paraphrase the
    // detector. Fail loudly instead.
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
