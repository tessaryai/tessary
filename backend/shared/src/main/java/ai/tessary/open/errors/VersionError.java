// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

public enum VersionError implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "No project version for commit '%s'"),
    UNKNOWN_NODE_KIND(HttpStatus.BAD_REQUEST, "Unknown lineage node kind '%s'"),
    LINEAGE_UNRESOLVED(HttpStatus.NOT_FOUND, "No commit lineage for %s '%s'");

    private final HttpStatus status;
    private final String template;

    VersionError(HttpStatus status, String template) {
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
        return VersionError.class;
    }
}
