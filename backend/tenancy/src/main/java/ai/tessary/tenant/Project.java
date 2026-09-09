// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * A project inside an organization ({@code project} table). {@code isDefault} marks the one guaranteed
 * default project per organization (an organization always has exactly one). {@code archivedAt} is the
 * reversible soft-archive marker; {@code settings} is a per-project JSON blob for extensible prefs.
 *
 * <p>{@code deletingAt} is the one-way marker, and it is deliberately not a second meaning for
 * {@code archivedAt}: a project comes back from archived, and only ever leaves this state by ceasing to
 * exist. It is set by the delete endpoint before it returns, so the window where a project is doomed but
 * still present is visible to the API and to settings rather than being a request that appears to hang.
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

    /** True once the delete endpoint has accepted; the purge worker owns this project's data from here. */
    @JsonIgnore
    public boolean isDeleting() {
        return deletingAt != null;
    }

    /**
     * True for the quiet, lazily-created "sample project" every org can start with from the connect
     * gate (#1227) — a real project row, marked by {@code {"sample": true}} in its own {@code settings}
     * blob rather than a dedicated column, the same "reuse the existing extensible blob" move
     * {@link #isArchived} and {@link #isDeleting} would have taken had {@code archived_at}/{@code
     * deleting_at} not already existed. A parse failure (malformed settings, written by hand or by a
     * future feature reusing the same blob) is read as "not a sample project" rather than thrown — this
     * flag gates only which shell chrome renders and whether default-project resolution skips a row, so
     * false-negative is the safe direction, never a 500 on an otherwise ordinary project.
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

    /** Parses {@link #settings} only for {@link #isSample}; the rest of the app treats it opaquely. */
    private static final com.fasterxml.jackson.databind.ObjectMapper SETTINGS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
