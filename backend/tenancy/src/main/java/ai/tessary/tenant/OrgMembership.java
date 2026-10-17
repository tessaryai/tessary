// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public record OrgMembership(
        @JsonProperty("org_id") String orgId,
        @JsonProperty("principal_id") String principalId,
        String role,
        @Nullable String scopes,
        @Nullable String attributes,
        @JsonProperty("created_at") String createdAt) {
    public static final String OWNER = "owner";
    public static final String MEMBER = "member";

    /** Empty scopes grant the role's full permissions. */
    public static OrgMembership of(String orgId, String principalId, String role, String createdAt) {
        return new OrgMembership(orgId, principalId, role, "{}", "{}", createdAt);
    }

    public boolean isOwner() {
        return OWNER.equals(role);
    }

    /** The earliest owner, ties broken by id, so a system run is attributed to the same person every time. */
    public static Optional<String> eldestOwner(List<OrgMembership> memberships) {
        return memberships.stream()
                .filter(OrgMembership::isOwner)
                .min(Comparator.comparing(OrgMembership::createdAt).thenComparing(OrgMembership::principalId))
                .map(OrgMembership::principalId);
    }
}
