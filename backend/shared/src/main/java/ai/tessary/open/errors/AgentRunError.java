// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/**
 * Errors for agent-sandbox runs and sessions. Callers retry an unreachable launcher or a full one, park on a launcher
 * refusing until the deployment changes, and spend an attempt on a run failure, an unusable answer or a timeout.
 */
public enum AgentRunError implements ErrorCode {
    LAUNCHER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The agent launcher is not answering: %s"),
    LAUNCHER_MISCONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "The agent launcher refused the run: %s"),
    RUN_FAILED(HttpStatus.BAD_GATEWAY, "The agent run failed: %s"),
    BAD_OUTPUT(HttpStatus.BAD_GATEWAY, "The agent returned nothing usable: %s"),
    TIMED_OUT(HttpStatus.GATEWAY_TIMEOUT, "The agent run hit its %sms wall clock"),
    CAPACITY(HttpStatus.SERVICE_UNAVAILABLE, "The agent launcher is at its session cap: %s");

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
