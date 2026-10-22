// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

public enum RcaError implements ErrorCode {
    SUBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "RCA subject not found: %s"),
    // A run that ended with no usable answer, or failed in a way none of the codes below names.
    UPSTREAM_FAILED(HttpStatus.BAD_GATEWAY, "RCA analysis failed: %s"),
    LAUNCHER_UNREACHABLE(
            HttpStatus.BAD_GATEWAY,
            "RCA could not reach the analysis launcher at %s — it is unreachable or not running"),
    // A deployment fault: every evidence row is read through MCP, so a run without it could only paraphrase the
    // detector. Fail loudly instead.
    NO_EVIDENCE_DOOR(HttpStatus.BAD_GATEWAY, "RCA cannot reach the evidence: %s"),
    PROVIDER_REJECTED(HttpStatus.SERVICE_UNAVAILABLE, "The model provider rejected the key for RCA: %s"),
    REQUEST_REFUSED(HttpStatus.SERVICE_UNAVAILABLE, "The model provider refused the RCA request: %s"),
    PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The model provider did not answer the RCA run: %s"),
    LAUNCHER_MISCONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "The analysis launcher refused the RCA run: %s"),
    TIMED_OUT(HttpStatus.GATEWAY_TIMEOUT, "The RCA run hit its wall clock: %s"),
    // Nothing failed: the job goes back to the queue unspent.
    SANDBOX_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Every agent sandbox is busy: %s");

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
