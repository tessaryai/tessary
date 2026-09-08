// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OrgInvitation(
        String id,
        @JsonProperty("org_id") String orgId,
        String email,
        String role,
        @JsonProperty("invited_by") String invitedBy,
        @JsonProperty("workos_invitation_id") String workosInvitationId,
        String state,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("accepted_at") String acceptedAt,
        @JsonProperty("revoked_at") String revokedAt) {
    public static final String PENDING = "pending";
    public static final String ACCEPTED = "accepted";
    public static final String REVOKED = "revoked";
}
