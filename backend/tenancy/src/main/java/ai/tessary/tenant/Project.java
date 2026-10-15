// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * An organization always has exactly one {@code isDefault} project. {@code archivedAt} is reversible;
 * {@code deletingAt} is one-way, set before the delete endpoint returns so a doomed project is visible rather than a
 * hanging request.
 */
public record Project(
        String id,
        @JsonProperty("org_id") String orgId,
        String slug,
        String name,
        @Nullable String description,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("archived_at") @Nullable String archivedAt,
        @JsonProperty("settings") @Nullable String settings,
        @JsonProperty("is_default") boolean isDefault,
        @JsonProperty("deleting_at") @Nullable String deletingAt) {

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** The purge worker owns this project's data from here. */
    @JsonIgnore
    public boolean isDeleting() {
        return deletingAt != null;
    }

    /**
     * Marked by {@code {"sample": true}} in {@code settings}. A parse failure reads as not a sample: it only gates
     * chrome and default resolution, so false is the safe direction.
     */
    @JsonIgnore
    public boolean isSample() {
        if (settings == null) return false;
        try {
            return Boolean.TRUE.equals(
                    SETTINGS_MAPPER.readTree(settings).path("sample").asBoolean(false));
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            return false;
        }
    }

    /** Only for {@link #isSample}; the rest of the app treats settings opaquely. */
    private static final com.fasterxml.jackson.databind.ObjectMapper SETTINGS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
