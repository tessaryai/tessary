// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * Cookie payload. We stash what we need to short-circuit auth on subsequent requests without a
 * WorkOS round-trip: the principal's WorkOS id, the refresh token and org for refresh, and the
 * access-token expiry so we know when to refresh.
 *
 * <p>The expiry is materialised explicitly rather than derived from the provider's token —
 * AES-GCM has already authenticated the cookie body, so we trust the value at face.</p>
 *
 * <p>Unknown properties are ignored so cookies sealed with fields this record has since dropped
 * still unseal.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SealedSession(
        @JsonProperty("refresh_token") @Nullable String refreshToken,
        @JsonProperty("access_token_expires_at") @Nullable String accessTokenExpiresAt,
        @JsonProperty("workos_user_id") String workosUserId,
        @JsonProperty("organization_id") @Nullable String organizationId) {}
