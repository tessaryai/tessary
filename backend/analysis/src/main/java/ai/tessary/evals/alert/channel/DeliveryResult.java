// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert.channel;

import org.jspecify.annotations.Nullable;

/**
 * The outcome of one {@link AlertChannel#deliver} call. Best-effort delivery never throws on
 * a transport error; it returns a result the {@code AlertDeliveryListener} records in the
 * delivery-attempt log. {@code httpStatus} is the upstream HTTP status when there was a response (null
 * for a pre-flight or network failure). On failure {@code error} carries a short, secret-free reason —
 * never the upstream response body (which could echo SSRF-probed internal content).
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
