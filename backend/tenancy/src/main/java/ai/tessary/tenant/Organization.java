// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/** Non-null {@code archivedAt} means archived (reversible) but still resolvable. */
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
