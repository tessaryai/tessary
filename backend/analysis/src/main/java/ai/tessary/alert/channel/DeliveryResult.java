// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import org.jspecify.annotations.Nullable;

/**
 * One {@link AlertChannel#deliver} outcome; delivery never throws on a transport error. {@code httpStatus} is null
 * without a response. {@code error} is short and secret-free, never the upstream body, which could echo SSRF-probed
 * content.
 */
public record DeliveryResult(
        boolean ok, @Nullable Integer httpStatus, @Nullable String error) {

    public static DeliveryResult success(int httpStatus) {
        return new DeliveryResult(true, httpStatus, null);
    }

    public static DeliveryResult failure(@Nullable Integer httpStatus, String error) {
        return new DeliveryResult(false, httpStatus, error);
    }
}
