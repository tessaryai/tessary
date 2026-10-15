// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth.link;

import org.jspecify.annotations.Nullable;

/** The device code is never stored, only its bcrypt hash and lookup prefix. */
public record DeviceLink(
        String id,
        String deviceCodePrefix,
        String deviceCodeHash,
        String userCode,
        String status,
        @Nullable String clientLabel,
        @Nullable String orgId,
        @Nullable String projectId,
        @Nullable String userId,
        @Nullable String mcpTokenId,
        String createdAt,
        String expiresAt,
        @Nullable String lastPolledAt,
        int pollCount) {
    public static final String PENDING = "pending";
    public static final String CONFIRMED = "confirmed";
    public static final String DENIED = "denied";
    public static final String CLAIMED = "claimed";
    public static final String EXPIRED = "expired";
}
