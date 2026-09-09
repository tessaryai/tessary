// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import org.jspecify.annotations.Nullable;

/**
 * How far a LEARNING epoch is from arming, and when it is likely to get there.
 *
 * <p>Arming is automatic and unattended, so an operator has no direct way to tell whether enabling
 * the classifier means findings tomorrow or findings in two months. Three bars must clear, and they
 * are not equally informative:
 *
 * <ul>
 *   <li><b>Depth</b>: the counts must support {@code arm_min_order}. This is the bar that decides
 *       whether the model works at all; a profile armed at order 1 measured 0.17 recall with
 *       reordering and deletion both at 0.00.
 *   <li><b>Volume</b>: a floor against tiny projects, not a readiness signal: the same 300 traces
 *       fitted order 3 on an 11-symbol agent and order 2 on a 28-symbol one.
 *   <li><b>Saturation</b>: a discovery rate below its floor for several consecutive fits.
 * </ul>
 *
 * <p>Volume projects exactly, depth projects as a lower bound, saturation does not project at all;
 * that asymmetry is reported rather than papered over with a fabricated estimate.
 *
 * <p>Every number in this record is read off a drift epoch row, so it is assembled by the caller
 * that owns that data rather than built here.
 */
public record BehaviorReadiness(
        long tracesObserved,
        int tracesRequired,
        int fittedOrder,
        int requiredOrder,
        @Nullable Double supportAtRequiredOrder,
        double supportRequired,
        @Nullable Double discoveryRate,
        double discoveryFloor,
        int saturatedFits,
        int saturatedFitsRequired,
        @Nullable Double tracesPerDay,
        @Nullable String estimatedReadyAt,
        boolean estimateIsLowerBound,
        String blockedBy) {

    /** What the epoch is still waiting on: the one thing worth putting in front of an operator. */
    public static final class BlockedBy {
        private BlockedBy() {}

        /** Below the floor that stops tiny projects arming at all. */
        public static final String VOLUME = "volume";

        /** The counts cannot yet support the minimum n-gram order, the bar that decides usefulness. */
        public static final String DEPTH = "depth";

        /** Depth and volume are met but the agent is still producing new behaviour. */
        public static final String SATURATION = "saturation";

        /** Every bar is clear; the next fit pass arms the profile. */
        public static final String NONE = "none";
    }
}
