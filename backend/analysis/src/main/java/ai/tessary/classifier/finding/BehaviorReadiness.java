// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import org.jspecify.annotations.Nullable;

/**
 * How far a LEARNING epoch is from arming, and when it is likely to get there.
 *
 * <p>Arming is automatic and unattended (PROGRAM.md §5.2), which leaves an operator with no way to
 * judge whether enabling the classifier means "findings tomorrow" or "findings in two months". That
 * question has a mechanical answer — both bars are counters on the epoch — so it is answered rather
 * than left to be discovered by waiting.
 *
 * <p>Three bars must clear, and they are not equally informative:
 *
 * <ul>
 *   <li><b>Depth</b> — the counts must support {@code arm_min_order}. This is the bar that decides
 *       whether the model works at all; a profile armed at order 1 measured 0.17 recall with
 *       reordering and deletion both at 0.00.
 *   <li><b>Volume</b> — a floor against tiny projects. Deliberately NOT a readiness signal: the same
 *       300 traces fitted order 3 on an 11-symbol agent and order 2 on a 28-symbol one.
 *   <li><b>Saturation</b> — a discovery rate below its floor for several consecutive fits.
 * </ul>
 *
 * <p>Volume projects exactly, depth projects as a lower bound, saturation does not project at all.
 * That asymmetry is reported rather than papered over with a fabricated estimate.
 *
 * <p><b>The record is here and the arithmetic is not.</b> This shape stays open regardless of whether
 * anything open still reaches it in the checked-in OpenAPI spec — #919 moved its only referencer,
 * {@code BehaviorDtos.BehaviorProfileView}, into {@code tessary-paid/behavior-drift}, which dropped it
 * out of the spec, and it still stays open (package-info.java, rule 4). Every number in it is read off
 * a drift epoch row, so the factory cannot live here.
 * {@code BehaviorProfileViews.readiness} on the drift side is what fills one.
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

    /** What the epoch is still waiting on — the one thing worth putting in front of an operator. */
    public static final class BlockedBy {
        private BlockedBy() {}

        /** Below the floor that stops tiny projects arming at all. */
        public static final String VOLUME = "volume";

        /** The counts cannot yet support the minimum n-gram order — the bar that decides usefulness. */
        public static final String DEPTH = "depth";

        /** Depth and volume are met but the agent is still producing new behaviour. */
        public static final String SATURATION = "saturation";

        /** Every bar is clear; the next fit pass arms the profile. */
        public static final String NONE = "none";
    }
}
