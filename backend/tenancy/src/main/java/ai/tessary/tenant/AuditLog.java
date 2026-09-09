// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * One immutable audit row — the {@code audit_log} table. It records a
 * governed action against a typed subject ({@code subjectKind},
 * {@code subjectId}) — an API key today, any governed resource tomorrow — by a {@code principalId},
 * optionally within an {@code organizationId}, with an optional structured {@code changes} diff. Never
 * updated after insert.
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

    /** The subject kind used for API-key lifecycle audit rows. */
    public static final String SUBJECT_API_KEY = "api_key";

    /** The lifecycle actions we record. The wire value is the lowercase name. */
    public enum Action {
        CREATED,
        UPDATED,
        ROTATED,
        REVOKED,
        DELETED;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Build an API-key audit row (subject_kind='api_key') for key lifecycle events. */
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
