// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * An organization (product label) backed by the {@code organization} table. {@code archivedAt} is the
 * soft-archive marker — non-null means the organization is archived (reversible) but still resolvable;
 * {@code settings} is a per-organization JSON blob for extensible, owner-managed preferences.
 */
public record Organization(
        String id,
        @JsonProperty("workos_org_id") @Nullable String workosOrgId,
        String slug,
        String name,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("archived_at") @Nullable String archivedAt,
        @JsonProperty("settings") @Nullable String settings) {

    public boolean isArchived() {
        return archivedAt != null;
    }
}
