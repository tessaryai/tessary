// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.errors;

import org.springframework.http.HttpStatus;

/** Account-creation refusals that are policy, not credentials (#1226). */
public enum AuthError implements ErrorCode {
    SIGNUP_REFUSED(
            HttpStatus.FORBIDDEN, "This instance is not accepting sign-ups; ask an administrator for an invitation");

    private final HttpStatus status;
    private final String template;

    AuthError(HttpStatus status, String template) {
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
        return AuthError.class;
    }
}
