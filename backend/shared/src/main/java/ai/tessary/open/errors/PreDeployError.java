// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/** Errors for the production-signal → pre-deploy feedback loop. */
public enum PreDeployError implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "No pre-deploy check '%s'");

    private final HttpStatus status;
    private final String template;

    PreDeployError(HttpStatus status, String template) {
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
        return PreDeployError.class;
    }
}
