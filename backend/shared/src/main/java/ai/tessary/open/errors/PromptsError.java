// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/**
 * Errors for the prompt library: names, versions, deployment labels, and the key scopes that read and write them. No
 * code is 401, because the SPA reads every 401 as a signed-out session.
 */
public enum PromptsError implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "Prompt %s not found"),
    VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Prompt %s has no version %s"),
    LABEL_NOT_FOUND(HttpStatus.NOT_FOUND, "Prompt %s has no label %s"),
    NAME_EXISTS(HttpStatus.CONFLICT, "A prompt named %s already exists"),
    TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "Prompt %s is a %s prompt; a new version cannot change its type"),
    INVALID_NAME(HttpStatus.BAD_REQUEST, "Invalid prompt name: %s"),
    INVALID_LABEL(HttpStatus.BAD_REQUEST, "Invalid label %s: use 1 to 36 characters from a-z, 0-9, _ . -"),
    LATEST_RESERVED(
            HttpStatus.BAD_REQUEST, "latest is derived from the newest version and cannot be set, moved, or deleted"),
    INVALID_BODY(HttpStatus.BAD_REQUEST, "Invalid prompt body: %s"),
    INVALID_CONFIG(HttpStatus.BAD_REQUEST, "config must be a JSON object of at most 65536 characters"),
    INVALID_TAG(HttpStatus.BAD_REQUEST, "Invalid tag %s: use 1 to 64 characters with no whitespace, at most 50 tags"),
    VERSION_LABELED(HttpStatus.CONFLICT, "Version %s carries label %s; move the label first"),
    LAST_VERSION(HttpStatus.CONFLICT, "Version %s is the only version of %s; delete the prompt instead"),
    VERSION_LIMIT(HttpStatus.CONFLICT, "Prompt %s already has %s versions; delete old versions first"),
    CONCURRENT_WRITE(HttpStatus.CONFLICT, "Another write changed prompt %s; retry"),
    BOTH_VERSION_AND_LABEL(HttpStatus.BAD_REQUEST, "Give version or label, not both"),
    NAME_REQUIRED(HttpStatus.BAD_REQUEST, "name is required"),
    TOKEN_REQUIRED(HttpStatus.FORBIDDEN, "the prompts API requires a project-scoped API key"),
    WRONG_KEY_SCOPE(
            HttpStatus.FORBIDDEN,
            "this API key cannot %s prompts (reads need a query or admin key, writes an admin key)");

    private final HttpStatus status;
    private final String template;

    PromptsError(HttpStatus status, String template) {
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
        return PromptsError.class;
    }
}
