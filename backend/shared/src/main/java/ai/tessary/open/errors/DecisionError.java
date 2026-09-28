// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/**
 * Error codes for a hosted decision-model call ({@code llm/decisions/}): one typed question set sent
 * to TypeSafe's Jev directly, over OpenRouter, or on the deployment's own provider.
 */
public enum DecisionError implements ErrorCode {
    /** 401, 402 or 403: the key was refused or has no funds. Retrying cannot help until that changes. */
    PROVIDER_REJECTED(HttpStatus.BAD_GATEWAY, "Decision provider %s rejected the key (HTTP %s)"),
    /** 429, 5xx or a transport failure that outlasted every retry. */
    PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "Decision provider %s is unavailable: %s"),
    /** Any other 4xx: the provider refused this request, and sending it again would be refused too. */
    REQUEST_REFUSED(HttpStatus.BAD_GATEWAY, "Decision provider %s refused the request (HTTP %s)"),
    /** A 2xx whose body does not answer every question asked in the documented shape. */
    MALFORMED_ANSWER(HttpStatus.BAD_GATEWAY, "Decision provider %s returned an unusable answer: %s");

    private final HttpStatus status;
    private final String template;

    DecisionError(HttpStatus status, String template) {
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
        return DecisionError.class;
    }
}
