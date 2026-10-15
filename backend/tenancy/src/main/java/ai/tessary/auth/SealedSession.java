// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * Cookie payload, enough to skip the provider round trip on later requests. The expiry is trusted at face because
 * AES-GCM already authenticated the body. Unknown properties are ignored so cookies sealed with since-dropped fields
 * still unseal.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SealedSession(
        @JsonProperty("refresh_token") @Nullable String refreshToken,
        @JsonProperty("access_token_expires_at") @Nullable String accessTokenExpiresAt,
        @JsonProperty("workos_user_id") String workosUserId,
        @JsonProperty("organization_id") @Nullable String organizationId,
        @JsonProperty("session_id") @Nullable String sessionId) {}
