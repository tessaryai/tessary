// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.errors;

import org.springframework.http.HttpStatus;

public enum IngestError implements ErrorCode {
    UPSTREAM_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "Upstream %s rejected credentials"),
    UPSTREAM_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Upstream %s rate-limited the request"),
    UPSTREAM_FAILED(HttpStatus.BAD_GATEWAY, "Upstream %s call failed: %s"),
    MAPPING_NOT_FOUND(HttpStatus.NOT_FOUND, "No source mapping '%s'"),
    SOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "No source with id '%s'"),
    UNSUPPORTED_PROVIDER(HttpStatus.BAD_REQUEST, "Unsupported provider '%s'"),
    DUPLICATE_NAME(HttpStatus.CONFLICT, "Source name '%s' already in use"),
    MISSING_SECRET_KEY(HttpStatus.PRECONDITION_FAILED, "evals.secret-key (EVALS_SECRET_KEY) is not configured"),
    INVALID_BASE_URL(HttpStatus.BAD_REQUEST, "Invalid baseUrl: %s"),
    OTLP_DISABLED(HttpStatus.NOT_FOUND, "OTLP receiver is disabled"),
    OTLP_TOKEN_REQUIRED(HttpStatus.FORBIDDEN, "OTLP ingest requires a project-scoped token"),
    OTLP_WRONG_KEY_SCOPE(HttpStatus.FORBIDDEN, "this API key is not scoped for ingest (needs a write or mcp key)"),
    OTLP_MALFORMED_BODY(HttpStatus.BAD_REQUEST, "Malformed OTLP protobuf body"),
    OTLP_BODY_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "OTLP request body exceeds %d bytes");

    private final HttpStatus status;
    private final String template;

    IngestError(HttpStatus status, String template) {
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
        return IngestError.class;
    }
}
