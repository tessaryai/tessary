// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/** Error codes for the Slack app surface — events ingress, signature verification, outbound posts. */
public enum SlackError implements ErrorCode {
    NOT_CONFIGURED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Slack is not configured (set tessary.slack.signing-secret and tessary.slack.bot-token)"),
    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, "Slack request signature verification failed"),
    NO_INSTALL(HttpStatus.NOT_FOUND, "No project is installed for Slack workspace '%s'"),
    POST_FAILED(HttpStatus.BAD_GATEWAY, "Slack Web API call failed: %s");

    private final HttpStatus status;
    private final String template;

    SlackError(HttpStatus status, String template) {
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
        return SlackError.class;
    }
}
