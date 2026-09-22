// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.toolerror.ToolErrorDetector.State;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One tool's CUSUM state as it travels between sweeps: the accumulators, how far the replay got, and the
 * assumptions the accumulators were built under. Design contract:
 * {@code classifiers/tool_error/PROGRAM.md} §5, which carries the argument for why this classifier has
 * state at all.
 *
 * <p>A plain record rather than a nested type of the repository, because {@link ToolErrorTrend} — which
 * has no database and is meant to keep it that way — both consumes and produces it.
 *
 * @param baseline the in-control reference these accumulators were measured against, or null on a row
 *     that has not learned one yet. <b>Frozen once learned.</b> Re-learning it each pass from the leading
 *     buckets of a sliding window is what let a slow degradation drag its own reference along behind it
 * @param watermarkBucket the last hourly bucket folded in, or null on a row that has never advanced.
 *     The next sweep folds only buckets strictly after it, which is the whole defence against a retried
 *     pass counting the same evidence twice
 * @param stateEpoch the tuning the accumulators were built under. Evidence scored under one set of
 *     log-likelihood weights is meaningless under another, and nothing about the failure is loud
 * @param pendingPinBy who pressed absorb before the run was thick enough to pin from, or null. The
 *     reference installs on the first sweep where {@link State#callsSinceOnsetUp()} reaches the minimum.
 *     <b>Read-only to the sweep</b> — it records a human decision, and
 *     {@link ToolErrorStateRepository#save} deliberately does not write this column
 * @param resetAt when a human last cleared this row's accumulator, or null if nobody has. <b>A fence,
 *     read-only to the sweep.</b> A replay that rebuilds rather than resumes skips every bucket before it,
 *     while learning a reference and while folding, so the spell a human just closed cannot be
 *     re-accumulated from the hours they ruled on. A resumed replay needs no fence: its watermark already
 *     sits past those hours
 */
public record CarriedState(
        String toolKey,
        State state,
        @Nullable ToolErrorRate baseline,
        @Nullable String watermarkBucket,
        String stateEpoch,
        @Nullable String pendingPinBy,
        @Nullable String pendingPinAt,
        @Nullable String resetAt) {

    /**
     * Whether {@code bucket} falls before the reset fence. Compared as instants, not strings: a bucket is a
     * whole hour and a reset carries fractional seconds, and {@code Instant.toString} drops a zero fraction,
     * so the two do not order lexically.
     */
    public boolean fencedOff(String bucket) {
        return resetAt != null && Instant.parse(bucket).isBefore(Instant.parse(resetAt));
    }

    /**
     * This state with its accumulator and watermark cleared, so a replay rebuilds the whole window against the
     * frozen reference instead of resuming after the last hour it folded. The reference, the pending pin and the
     * reset fence are kept: the first is learned once and frozen, the other two record human decisions.
     *
     * <p>For a classifier whose hourly tallies are not final when first read (a backfill lands one hour across
     * several uploads; a late flag turns an old conversation into a failure), which a resume would keep at its
     * first, partial count for good.
     */
    public CarriedState rebuilding() {
        return new CarriedState(toolKey, State.EMPTY, baseline, null, stateEpoch, pendingPinBy, pendingPinAt, resetAt);
    }

    /** Whether an absorb is waiting for the run to grow thick enough to pin from. */
    public boolean hasPendingPin() {
        return pendingPinAt != null;
    }

    /**
     * The epoch string an accumulator is only valid under.
     *
     * <p>Everything that changes what a single call is WORTH goes in. The shift multiple and floor set
     * the alternative hypothesis and so the per-call weights; the ARL target sets the threshold the sum
     * is compared to; the down-arm floor decides whether that arm accumulated at all. Change any of them
     * and the stored number is a sum of terms that no longer mean what they meant — arithmetically fine,
     * silently wrong, and with no error anywhere to notice it.
     *
     * <p>The reference is deliberately NOT in here. It is carried explicitly on {@link #baseline} and
     * compared explicitly, because "the reference moved" and "the tuning moved" want different handling
     * and folding them together makes both invisible.
     */
    public static String epochOf(ToolErrorConfig config, String schemaVersion) {
        return String.join(
                "|",
                schemaVersion,
                Long.toString(config.arlTarget()),
                Double.toString(config.shiftMultiple()),
                Double.toString(config.shiftFloor()),
                Double.toString(config.downArmMinRate()));
    }

    /** Whether this state can be resumed under {@code epoch} against {@code against}, or must be rebuilt. */
    public boolean resumableUnder(String epoch, ToolErrorRate against) {
        return stateEpoch.equals(epoch)
                && watermarkBucket != null
                && baseline != null
                && baseline.calls() == against.calls()
                && baseline.failures() == against.failures();
    }
}
