// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/**
 * Errors raised by the generic agent-sandbox run ({@code agentrun/}). Five outcomes, split by who has
 * to act: the launcher could not be reached, the launcher is refusing every request until a person
 * changes the deployment, this run failed on its own terms, the agent finished but answered with
 * nothing usable, or the run hit its wall clock. A caller that queues these runs retries the first,
 * parks on the second, and spends an attempt on the other three.
 */
public enum AgentRunError implements ErrorCode {
    LAUNCHER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The agent launcher is not answering: %s"),
    LAUNCHER_MISCONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "The agent launcher refused the run: %s"),
    RUN_FAILED(HttpStatus.BAD_GATEWAY, "The agent run failed: %s"),
    BAD_OUTPUT(HttpStatus.BAD_GATEWAY, "The agent returned nothing usable: %s"),
    TIMED_OUT(HttpStatus.GATEWAY_TIMEOUT, "The agent run hit its %sms wall clock");

    private final HttpStatus status;
    private final String template;

    AgentRunError(HttpStatus status, String template) {
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
        return AgentRunError.class;
    }
}
