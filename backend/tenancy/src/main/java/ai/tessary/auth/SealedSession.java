// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * Cookie payload. We stash everything we need to short-circuit auth on subsequent
 * requests without a WorkOS round-trip: the tokens for refresh, the user identity
 * for {@code /api/me}, and the access-token expiry so we know when to refresh.
 *
 * <p>The expiry is materialised explicitly (rather than decoding the access_token
 * JWT each time) — AES-GCM has already authenticated the cookie body, so we trust
 * the value at face.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SealedSession(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") @Nullable String refreshToken,
        @JsonProperty("access_token_expires_at") @Nullable String accessTokenExpiresAt,
        @JsonProperty("workos_user_id") String workosUserId,
        @Nullable String email,
        @JsonProperty("display_name") @Nullable String displayName,
        @JsonProperty("avatar_url") @Nullable String avatarUrl,
        @JsonProperty("organization_id") @Nullable String organizationId) {}
