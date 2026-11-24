// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/** Errors of the Users view. A template never carries a user ID: these messages reach the logs. */
public enum EndUserError implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "No user ID or issue matches this request in the project"),
    ERASE_NEEDS_SESSION(HttpStatus.FORBIDDEN, "Erasing a user ID needs a signed-in session, not an API token");

    private final HttpStatus status;
    private final String template;

    EndUserError(HttpStatus status, String template) {
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
        return EndUserError.class;
    }
}
