// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.errors;

import org.springframework.http.HttpStatus;

/**
 * Errors for the aggregation-first query API — the {@code /v1/query/*} surface. Bad request
 * shapes (unknown dataset / facet field / interval) are client errors; a missing project-scoped token
 * is a {@code 403}, mirroring {@link IngestError#OTLP_TOKEN_REQUIRED} on the sibling {@code /v1/traces}
 * front door.
 */
public enum QueryError implements ErrorCode {
    TOKEN_REQUIRED(HttpStatus.FORBIDDEN, "the query API requires a project-scoped token"),
    WRONG_KEY_SCOPE(HttpStatus.FORBIDDEN, "this API key is not scoped for the query API (needs a query or admin key)"),
    UNKNOWN_DATASET(HttpStatus.BAD_REQUEST, "unknown dataset '%s'"),
    UNKNOWN_FIELD(HttpStatus.BAD_REQUEST, "field '%s' is not queryable on dataset '%s'"),
    UNKNOWN_INTERVAL(HttpStatus.BAD_REQUEST, "unknown timeseries interval '%s'"),
    INVALID_RANGE(HttpStatus.BAD_REQUEST, "an explicit time range (from/to) is required for this operation"),
    UNKNOWN_SEARCH_MODE(HttpStatus.BAD_REQUEST, "unknown search mode '%s'"),
    SEARCH_MODE_UNSUPPORTED(HttpStatus.NOT_IMPLEMENTED, "search mode '%s' is not yet supported"),
    SEARCH_UNSUPPORTED_FOR_DATASET(HttpStatus.BAD_REQUEST, "search is not supported on dataset '%s'");

    private final HttpStatus status;
    private final String template;

    QueryError(HttpStatus status, String template) {
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
        return QueryError.class;
    }
}
