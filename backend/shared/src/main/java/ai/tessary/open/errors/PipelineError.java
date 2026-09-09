// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

/**
 * Validation failures while assembling / importing a {@code .tessary/} bundle (the multipart
 * upload and the observer repo-import path). These were raw {@code IllegalArgumentException}s
 * that the global handler flattened to a generic {@code COMMON.INVALID_PARAMETER} while echoing
 * the parser's raw message; registering them gives the frontend switchable codes and keeps raw
 * parse detail out of the client response (it stays on the server-side cause).
 */
public enum PipelineError implements ErrorCode {
    EMPTY_UPLOAD(HttpStatus.BAD_REQUEST, "Upload must include at least one file"),
    FILE_READ_FAILED(HttpStatus.BAD_REQUEST, "Could not read uploaded file '%s'"),
    INVALID_MODE(HttpStatus.BAD_REQUEST, "Import mode must be 'upsert' or 'replace'; got '%s'"),
    NO_SHARDS(HttpStatus.BAD_REQUEST, "Upload had no recognisable .tessary/ shards or graders/ files — nothing to do"),
    MISSING_META(HttpStatus.BAD_REQUEST, "Upload is missing pipeline/meta.yaml — required for every import"),
    DUPLICATE_FILE(HttpStatus.BAD_REQUEST, "Upload contains more than one %s"),
    MISSING_ID(HttpStatus.BAD_REQUEST, "%s has no id field"),
    EXPECTED_YAML_LIST(HttpStatus.BAD_REQUEST, "Expected a YAML list, got %s"),
    MALFORMED_YAML(HttpStatus.BAD_REQUEST, "%s is not valid YAML"),
    MALFORMED_META_FIELD(HttpStatus.BAD_REQUEST, "pipeline/meta.yaml has a malformed '%s' section");

    private final HttpStatus status;
    private final String template;

    PipelineError(HttpStatus status, String template) {
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
        return PipelineError.class;
    }
}
