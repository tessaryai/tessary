// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

public enum JudgeError implements ErrorCode {
    GRADER_NOT_FOUND(HttpStatus.NOT_FOUND, "No grader with id '%s' in the loaded pipeline"),
    UPSTREAM_FAILED(HttpStatus.BAD_GATEWAY, "Judge provider call failed: %s"),
    UPSTREAM_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Judge provider rate-limited the request"),
    PARSE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "Judge response was not valid JSON"),
    DETERMINISTIC_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "Deterministic grader could not run: %s"),
    UNSUPPORTED_CONTENT_TYPE(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Unsupported content type for grading (v1 supports text, images, and PDF documents): %s"),
    MEDIA_NOT_FOUND(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Referenced media object could not be resolved for grading (media id '%s')"),
    DOCUMENT_TEXT_UNAVAILABLE(
            HttpStatus.UNPROCESSABLE_ENTITY, "Document could not be extracted for grading (media id/type '%s')");

    private final HttpStatus status;
    private final String template;

    JudgeError(HttpStatus status, String template) {
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
        return JudgeError.class;
    }
}
