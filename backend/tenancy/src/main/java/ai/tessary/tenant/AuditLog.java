// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * One immutable {@code audit_log} row: a governed action on a typed subject by a principal, with an optional
 * {@code changes} diff.
 */
public record AuditLog(
        String id,
        @JsonProperty("project_id") String projectId,
        @JsonProperty("organization_id") @Nullable String organizationId,
        @JsonProperty("subject_kind") String subjectKind,
        @JsonProperty("subject_id") @Nullable String subjectId,
        @JsonProperty("principal_id") @Nullable String principalId,
        String action,
        @JsonProperty("details") @Nullable String details,
        @Nullable String changes,
        @Nullable String attributes,
        @JsonProperty("occurred_at") String occurredAt) {

    public static final String SUBJECT_API_KEY = "api_key";

    /** The wire value is the lowercase name. */
    public enum Action {
        CREATED,
        ROTATED,
        REVOKED;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static AuditLog forApiKey(
            String id,
            String projectId,
            @Nullable String apiKeyId,
            @Nullable String principalId,
            String action,
            @Nullable String details,
            String occurredAt) {
        return new AuditLog(
                id, projectId, null, SUBJECT_API_KEY, apiKeyId, principalId, action, details, null, null, occurredAt);
    }
}
