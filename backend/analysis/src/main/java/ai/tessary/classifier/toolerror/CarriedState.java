// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.toolerror.ToolErrorDetector.State;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A plain record because {@link ToolErrorTrend}, which has no database, both consumes and produces it. Contract:
 * {@code devdocs/concepts/tool-error.md} §5. @param baseline frozen once it holds
 * {@link ToolErrorConfig#freezeBaselineCalls()}; re-learning from a sliding window let a slow degradation drag its
 * reference along @param watermarkBucket the next sweep folds only buckets strictly after it @param pendingPinBy a
 * human decision; {@link ToolErrorStateRepository#save} never writes it @param resetAt a fence: a rebuild skips buckets
 * before it so a closed spell cannot re-accumulate @param learnedThrough the newest hour folded into the reference,
 * stored as {@code learned_through}, so no pass ever counts an hour twice @param learnBefore a learning fence: hours at
 * or after it never join the reference. Changeset 0051 set it at an open finding's onset; {@link
 * ToolErrorStateRepository#resetAndRelearn} lifts it, and a sweep never writes it
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
        @Nullable String learnedThrough,
        @Nullable String learnBefore) {

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
                watermarkBucket,
                null);
    }

    /** Compared as instants: {@code Instant.toString} drops a zero fraction, so the strings do not order lexically. */
    public boolean fencedOff(String bucket) {
        return resetAt != null && Instant.parse(bucket).isBefore(Instant.parse(resetAt));
    }

    /** Also true for an hour at or after the learning fence, which is judged but never learned. */
    public boolean fencedForLearning(String bucket) {
        return fencedOff(bucket)
                || (learnBefore != null && !Instant.parse(bucket).isBefore(Instant.parse(learnBefore)));
    }

    /**
     * Whether the hour starting at {@code bucket} may join the reference: its end plus {@code settleHours} is not after
     * {@code clock}, the key's newest bucket. Late spans and labels for it have landed by then.
     */
    public static boolean settled(String bucket, String clock, int settleHours) {
        return !Instant.parse(bucket).plus(Duration.ofHours(1L + settleHours)).isAfter(Instant.parse(clock));
    }

    /** {@code state} is null for a key that has never swept. @param tallies one key's, oldest first */
    public static long learned(@Nullable CarriedState state, List<HourlyToolTally> tallies, int settleHours) {
        if (tallies.isEmpty()) return 0;
        String clock = tallies.getLast().bucket();
        long learned = 0;
        for (HourlyToolTally t : tallies) {
            if (!settled(t.bucket(), clock, settleHours)) continue;
            if (state == null || !state.fencedForLearning(t.bucket())) learned += t.calls();
        }
        return learned;
    }

    /**
     * For a classifier whose hourly tallies are not final when first read, which a resume would keep at their first
     * partial count. The reference, pending pin, both fences and {@link #learnedThrough} are kept.
     */
    public CarriedState rebuilding() {
        return new CarriedState(
                toolKey,
                State.EMPTY,
                baseline,
                null,
                stateEpoch,
                pendingPinBy,
                pendingPinAt,
                resetAt,
                learnedThrough,
                learnBefore);
    }

    public boolean hasPendingPin() {
        return pendingPinAt != null;
    }

    /**
     * Everything that changes what one call is worth goes in; a stale accumulator is arithmetically fine and silently
     * wrong. The reference is deliberately not included: "reference moved" and "tuning moved" need different handling.
     */
    public static String epochOf(ToolErrorConfig config, String schemaVersion) {
        String epoch = String.join(
                "|",
                schemaVersion,
                Long.toString(config.arlTarget()),
                Double.toString(config.shiftMultiple()),
                Double.toString(config.shiftFloor()),
                Double.toString(config.downArmMinRate()),
                "settle=" + config.referenceSettleHours());
        if (!config.learnsWhileJudging()) return epoch;
        return epoch + "|learn=" + config.minBaselineCalls() + "-" + config.freezeBaselineCalls();
    }

    public boolean resumableUnder(String epoch, ToolErrorRate against) {
        return stateEpoch.equals(epoch)
                && watermarkBucket != null
                && baseline != null
                && baseline.calls() == against.calls()
                && baseline.failures() == against.failures();
    }
}
