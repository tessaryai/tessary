// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/**
 * Errors for one prompt-playground chat call on the org's own provider key. A rejected key is 502, not 401, because the
 * SPA reads every 401 as a signed-out session. A missing key is {@link ModelConfigError#MISSING_CREDENTIALS}.
 */
public enum PlaygroundError implements ErrorCode {
    PROVIDER_UNSUPPORTED(HttpStatus.BAD_REQUEST, "Provider %s cannot run the playground: %s"),
    UNKNOWN_CONFIG_KEY(
            HttpStatus.BAD_REQUEST,
            "Config key '%s' is not forwarded by the playground; remove it (forwarded: temperature, max_tokens, top_p,"
                    + " stop)"),
    PROVIDER_REJECTED(HttpStatus.BAD_GATEWAY, "Provider %s rejected the key (HTTP %s)"),
    PROVIDER_NO_CREDIT(HttpStatus.PAYMENT_REQUIRED, "Provider %s has no credit left on the key (HTTP %s)"),
    REQUEST_REFUSED(HttpStatus.UNPROCESSABLE_ENTITY, "Provider %s refused the request (HTTP %s): %s"),
    TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Provider %s did not answer within %s ms"),
    BUSY(HttpStatus.TOO_MANY_REQUESTS, "The playground already has %s runs in flight; try again in a moment"),
    PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "Provider %s is unavailable: %s"),
    MALFORMED_RESPONSE(HttpStatus.BAD_GATEWAY, "Provider %s returned an unusable answer: %s");

    private final HttpStatus status;
    private final String template;

    PlaygroundError(HttpStatus status, String template) {
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
        return PlaygroundError.class;
    }
}
