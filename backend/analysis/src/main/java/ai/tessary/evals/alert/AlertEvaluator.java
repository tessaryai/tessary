// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The snooze rule — all that is left of the threshold evaluator.
 *
 * <p>This class used to hold the per-classifier threshold-breach decision: count a classifier's detections
 * over a rolling window and decide whether the count crossed a configured bar. That decision moved to
 * the classifier itself ({@code ClassifierArming}), where the same window opens a FINDING with the
 * flagged spans as evidence instead of a message with no state behind it. Migration {@code 0089}
 * translated the configured numbers onto the classifiers and disabled the rules.
 *
 * <p>What could not move is the snooze horizon, which is a property of the DELIVERY of an alert rather
 * than of any judgement, and applies equally to the roll-up and case-opened rules that remain. It is a
 * static predicate on a rule row, so this is a utility class rather than a bean.
 */
public final class AlertEvaluator {

    private AlertEvaluator() {}

    /** True while {@code now} is before the alert's snooze horizon (soft mute). A null/blank horizon never mutes. */
    public static boolean isSnoozed(@Nullable String snoozedUntil, Instant now) {
        if (snoozedUntil == null || snoozedUntil.isBlank()) return false;
        try {
            return now.isBefore(Instant.parse(snoozedUntil));
        } catch (RuntimeException e) {
            return false; // an unparseable horizon never mutes (fail-open to firing, surfaced by the row)
        }
    }
}
