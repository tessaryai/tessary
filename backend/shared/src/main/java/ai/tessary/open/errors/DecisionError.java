// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/** Errors for a hosted decision-model call ({@code llm/decisions/}). */
public enum DecisionError implements ErrorCode {
    /** 401 or 403. Retrying cannot help until the key changes. */
    PROVIDER_REJECTED(HttpStatus.BAD_GATEWAY, "Decision provider %s rejected the key (HTTP %s)"),
    /** 402. Retrying cannot help until it is topped up. */
    PROVIDER_NO_CREDIT(HttpStatus.BAD_GATEWAY, "Decision provider %s has no credit left on the key (HTTP %s)"),
    /** 429, 5xx or a transport failure that outlasted every retry. */
    PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "Decision provider %s is unavailable: %s"),
    /**
     * Any other 4xx; resending would be refused too. The last argument is the provider's trimmed explanation, so the
     * log can name an unserved model id.
     */
    REQUEST_REFUSED(HttpStatus.BAD_GATEWAY, "Decision provider %s refused the request (HTTP %s): %s"),
    /** A 2xx whose body does not answer every question in the documented shape. */
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
