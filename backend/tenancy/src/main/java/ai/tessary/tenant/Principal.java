// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * A principal — any actor known to the platform, generalised from the old {@code app_user}. A
 * principal is a {@code human} (a WorkOS-backed user), an {@code agent}, or a {@code service}; the latter
 * two have no WorkOS id or email and may name a {@code parentPrincipalId} (the human/org they act on
 * behalf of). {@code status} gates access ({@code active}/{@code suspended}); {@code attributes} is a
 * free-form JSON bag. Snake_case on the wire, camelCase in Java.
 */
public record Principal(
        String id,
        @JsonProperty("workos_user_id") @Nullable String workosUserId,
        @Nullable String email,
        @JsonProperty("display_name") @Nullable String displayName,
        @JsonProperty("avatar_url") @Nullable String avatarUrl,
        String kind,
        @JsonProperty("parent_principal_id") @Nullable String parentPrincipalId,
        String status,
        @Nullable String attributes,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("last_seen_at") @Nullable String lastSeenAt) {

    /** The kind of actor a principal represents. */
    public static final class Kind {
        private Kind() {}

        public static final String HUMAN = "human";
        public static final String AGENT = "agent";
        public static final String SERVICE = "service";
    }

    /** Whether a principal may act. */
    public static final class Status {
        private Status() {}

        public static final String ACTIVE = "active";
        public static final String SUSPENDED = "suspended";
    }

    /** Build a {@code human} principal (a WorkOS-backed user) with active status and no parent. */
    public static Principal human(
            String id,
            String workosUserId,
            String email,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            String createdAt,
            @Nullable String lastSeenAt) {
        return new Principal(
                id,
                workosUserId,
                email,
                displayName,
                avatarUrl,
                Kind.HUMAN,
                null,
                Status.ACTIVE,
                "{}",
                createdAt,
                lastSeenAt);
    }
}
