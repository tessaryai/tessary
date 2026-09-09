// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

public enum CommonError implements ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed: %s"),
    INVALID_BODY(HttpStatus.BAD_REQUEST, "Malformed request body: %s"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Invalid parameter: %s"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeded size limit: %s"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type: %s"),
    INTERRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, "Operation interrupted: %s"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String template;

    CommonError(HttpStatus status, String template) {
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
        return CommonError.class;
    }
}
