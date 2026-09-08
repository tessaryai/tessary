// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import ai.tessary.evals.tenant.rbac.Role;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

public record OrgMembership(
        @JsonProperty("org_id") String orgId,
        @JsonProperty("principal_id") String principalId,
        String role,
        @Nullable String scopes,
        @Nullable String attributes,
        @JsonProperty("created_at") String createdAt) {
    public static final String OWNER = "owner";
    public static final String ADMIN = "admin";
    public static final String MEMBER = "member";
    public static final String VIEWER = "viewer";
    public static final String BILLING = "billing";

    /** A membership with the role's full permissions (empty scopes) and no attributes. */
    public static OrgMembership of(String orgId, String principalId, String role, String createdAt) {
        return new OrgMembership(orgId, principalId, role, "{}", "{}", createdAt);
    }

    public boolean isOwner() {
        return OWNER.equals(role);
    }

    /** The strongly-typed role, defaulting to {@link Role#MEMBER} for an unrecognised value. */
    public Role roleEnum() {
        return Role.fromWireOrMember(role);
    }
}
