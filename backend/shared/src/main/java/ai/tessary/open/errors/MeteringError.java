// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/** Error codes for the usage-metering feature. */
public enum MeteringError implements ErrorCode {
    TOKEN_REQUIRED(HttpStatus.UNAUTHORIZED, "Usage metering requires a project-scoped token"),
    INVALID_RANGE(HttpStatus.UNPROCESSABLE_ENTITY, "Usage query requires both a 'from' and a 'to' bound"),
    UNKNOWN_UNIT(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Unknown usage unit '%s' (expected ingested_spans, l1_evals, l2_evals, llm_tokens, "
                    + "llm_cost_micro_usd_platform, llm_cost_micro_usd_byo, or storage)"),
    UNKNOWN_BUCKET_UNIT(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown usage bucket unit '%s' (expected hour or day)"),
    UNKNOWN_SERIES_GRAIN(
            HttpStatus.UNPROCESSABLE_ENTITY, "Unknown usage series grain '%s' (expected hour, day or week)"),
    UNKNOWN_GROUPING(
            HttpStatus.UNPROCESSABLE_ENTITY, "Unknown usage grouping '%s' (expected none, lane, project or model)"),
    WINDOW_TOO_FINE(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "A '%s'-grain series over that range is %s buckets (max %s) — widen the grain or shorten the range");

    private final HttpStatus status;
    private final String template;

    MeteringError(HttpStatus status, String template) {
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
        return MeteringError.class;
    }
}
