// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The snooze rule, a property of alert delivery that applies to every remaining rule type. Threshold decisions belong
 * to {@code ClassifierArming}.
 */
public final class AlertEvaluator {

    private AlertEvaluator() {}

    /** A null or blank horizon never mutes. */
    public static boolean isSnoozed(@Nullable String snoozedUntil, Instant now) {
        if (snoozedUntil == null || snoozedUntil.isBlank()) return false;
        try {
            return now.isBefore(Instant.parse(snoozedUntil));
        } catch (RuntimeException e) {
            return false; // an unparseable horizon never mutes (fail-open to firing, surfaced by the row)
        }
    }
}
