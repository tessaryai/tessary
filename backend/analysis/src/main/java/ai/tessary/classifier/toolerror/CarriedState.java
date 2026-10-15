// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.toolerror.ToolErrorDetector.State;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A plain record because {@link ToolErrorTrend}, which has no database, both consumes and produces it. Contract:
 * {@code devdocs/concepts/tool-error.md} §5. @param baseline frozen once it holds
 * {@link ToolErrorConfig#freezeBaselineCalls()}; re-learning from a sliding window let a slow degradation drag its
 * reference along @param watermarkBucket the next sweep folds only buckets strictly after it @param pendingPinBy a
 * human decision; {@link ToolErrorStateRepository#save} never writes it @param resetAt a fence: a rebuild skips buckets
 * before it so a closed spell cannot re-accumulate @param learnedThrough not stored; kept across {@link #rebuilding} so
 * a rebuild never counts the reference's hours twice
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

    /** Compared as instants: {@code Instant.toString} drops a zero fraction, so the strings do not order lexically. */
    public boolean fencedOff(String bucket) {
        return resetAt != null && Instant.parse(bucket).isBefore(Instant.parse(resetAt));
    }

    /** {@code state} is null for a key that has never swept. */
    public static long learned(@Nullable CarriedState state, List<HourlyToolTally> tallies) {
        long learned = 0;
        for (HourlyToolTally t : tallies) {
            if (state == null || !state.fencedOff(t.bucket())) learned += t.calls();
        }
        return learned;
    }

    /**
     * For a classifier whose hourly tallies are not final when first read, which a resume would keep at their first
     * partial count. The reference, pending pin, reset fence and {@link #learnedThrough} are kept.
     */
    public CarriedState rebuilding() {
        return new CarriedState(
                toolKey, State.EMPTY, baseline, null, stateEpoch, pendingPinBy, pendingPinAt, resetAt, learnedThrough);
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
                Double.toString(config.downArmMinRate()));
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
