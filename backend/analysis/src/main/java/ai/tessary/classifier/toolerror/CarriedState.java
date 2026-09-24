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
 *     that has not learned one yet. <b>Frozen once learned</b>, that is once it holds
 *     {@link ToolErrorConfig#freezeBaselineCalls()}; below that it is still learning, and its counts are all
 *     that says so. Re-learning it each pass from the leading buckets of a sliding window is what let a slow
 *     degradation drag its own reference along behind it
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
 * @param learnedThrough the hour a still-learning {@link #baseline} has learned up to: it never learns from
 *     this hour or an earlier one again. Null when nothing says. Not stored: while the reference learns,
 *     every hour a replay folds is learned from too, so it is the watermark the row was saved with.
 *     {@link #rebuilding} clears the watermark and keeps this, so a rebuild grows the reference from the
 *     hours after it only, instead of counting its hours twice or re-learning it from a window that has
 *     slid forward since
 */
public record CarriedState(
        String toolKey,
        State state,
        @Nullable ToolErrorRate baseline,
        @Nullable String watermarkBucket,
        String stateEpoch,
        @Nullable String pendingPinBy,
        @Nullable String pendingPinAt,
        @Nullable String resetAt,
        @Nullable String learnedThrough) {

    /** A row as stored, whose reference has learned up to its watermark. */
    public CarriedState(
            String toolKey,
            State state,
            @Nullable ToolErrorRate baseline,
            @Nullable String watermarkBucket,
            String stateEpoch,
            @Nullable String pendingPinBy,
            @Nullable String pendingPinAt,
            @Nullable String resetAt) {
        this(
                toolKey,
                state,
                baseline,
                watermarkBucket,
                stateEpoch,
                pendingPinBy,
                pendingPinAt,
                resetAt,
                watermarkBucket);
    }

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
     * reset fence are kept: the first is learned once and frozen, the other two record human decisions. So is
     * {@link #learnedThrough}, which is how a reference still learning knows which hours it already holds.
     *
     * <p>For a classifier whose hourly tallies are not final when first read (a backfill lands one hour across
     * several uploads; a late flag turns an old conversation into a failure), which a resume would keep at its
     * first, partial count for good.
     */
    public CarriedState rebuilding() {
        return new CarriedState(
                toolKey, State.EMPTY, baseline, null, stateEpoch, pendingPinBy, pendingPinAt, resetAt, learnedThrough);
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
     * <p>So is when the reference stops learning, but only for a config where it learns while judging. There
     * every hour judged before the freeze was judged against a smaller reference than the one after it, which
     * a different minimum or freeze would not reproduce. A config that freezes the reference when judging
     * starts leaves the epoch exactly as it was, so no existing row rebuilds for a dial it does not use.
     *
     * <p>The reference is deliberately NOT in here. It is carried explicitly on {@link #baseline} and
     * compared explicitly, because "the reference moved" and "the tuning moved" want different handling
     * and folding them together makes both invisible.
     */
    public static String epochOf(ToolErrorConfig config, String schemaVersion) {
        String epoch = String.join(
                "|",
                schemaVersion,
                Long.toString(config.arlTarget()),
                Double.toString(config.shiftMultiple()),
                Double.toString(config.shiftFloor()),
                Double.toString(config.downArmMinRate()));
        if (!config.learnsWhileJudging()) return epoch;
        return epoch + "|learn=" + config.minBaselineCalls() + "-" + config.freezeBaselineCalls();
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
