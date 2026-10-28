// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.toolerror.ToolErrorDetector.Decision;
import ai.tessary.classifier.toolerror.ToolErrorDetector.State;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository.AcceptedReference;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Pure: no database, Spring or clock. The carried accumulator is an input, which reopens the double-count bug class;
 * {@link CarriedState} defends by folding only buckets after the watermark and rebuilding when tuning or reference
 * moved. Grouped per hourly bucket (floor applied per bucket), which is slightly slower to forget a burst than per call
 * and never more sensitive. Contract: {@code devdocs/concepts/tool-error.md} §5.
 */
public final class ToolErrorTrend {

    private ToolErrorTrend() {}

    /**
     * @param decision its rates span the run since onset @param lastBucket the tool's own event clock, written as
     * {@code last_seen_at} instead of the sweep's wall clock
     */
    public record Spell(
            String toolKey,
            Decision decision,
            ToolErrorRate baseline,
            @Nullable String onsetBucket,
            @Nullable String lastBucket) {}

    /** A non-alarming tool still has state worth keeping, so the next sweep need not start over. */
    public record Sweep(List<Spell> spells, List<CarriedState> advanced) {}

    /**
     * Bump it whenever the meaning of a stored accumulator changes, so carried rows rebuild instead of resuming under
     * stale assumptions.
     */
    public static final String STATE_SCHEMA_VERSION = "v2-onset";

    /**
     * At the end, not anywhere during it: the {@link ai.tessary.cases.CaseSource} contract is "currently firing".
     * @param tallies oldest first: a CUSUM fed out of order is not a CUSUM @param carried empty is always safe; it
     * rebuilds
     */
    public static Sweep sweep(
            List<HourlyToolTally> tallies,
            ToolErrorConfig config,
            Map<String, AcceptedReference> accepted,
            Map<String, CarriedState> carried) {
        return sweep(tallies, config, accepted, carried, STATE_SCHEMA_VERSION);
    }

    /**
     * For a classifier whose accumulator also depends on tuning this engine does not know about; the version is baked
     * into each carried row's epoch.
     */
    public static Sweep sweep(
            List<HourlyToolTally> tallies,
            ToolErrorConfig config,
            Map<String, AcceptedReference> accepted,
            Map<String, CarriedState> carried,
            String schemaVersion) {
        Map<String, List<HourlyToolTally>> byTool = new LinkedHashMap<>();
        for (HourlyToolTally t : tallies) {
            byTool.computeIfAbsent(t.toolKey(), k -> new ArrayList<>()).add(t);
        }
        List<Spell> spells = new ArrayList<>();
        List<CarriedState> advanced = new ArrayList<>();
        for (Map.Entry<String, List<HourlyToolTally>> e : byTool.entrySet()) {
            Replayed r = replay(
                    e.getKey(), e.getValue(), config, accepted.get(e.getKey()), carried.get(e.getKey()), schemaVersion);
            if (r == null) continue; // still learning a reference; nothing to judge and nothing to carry
            if (r.spell() != null) spells.add(r.spell());
            advanced.add(r.carried());
        }
        return new Sweep(List.copyOf(spells), List.copyOf(advanced));
    }

    /** One tool's outcome: the state to carry forward, and its spell when it is alarming. */
    record Replayed(CarriedState carried, @Nullable Spell spell) {}

    private static @Nullable Replayed replay(
            String toolKey,
            List<HourlyToolTally> buckets,
            ToolErrorConfig config,
            @Nullable AcceptedReference accepted,
            @Nullable CarriedState carried,
            String schemaVersion) {
        // The reference is frozen once thick enough, never sliding, or it would drift along with a slow degradation. A
        // human-pinned reference replaces it and the replay starts after acceptance, or the finding would return on the
        // next pass. Read once: two accessor calls only happen to agree.
        ToolErrorRate carriedBaseline = carried == null ? null : carried.baseline();
        String epoch = CarriedState.epochOf(config, schemaVersion);
        // The key's own event clock: an hour may be learned only once its end plus the settle delay has passed on it.
        String clock = buckets.getLast().bucket();
        int settle = config.referenceSettleHours();
        String learnedThrough = carried == null ? null : carried.learnedThrough();
        String learnBefore = carried == null ? null : carried.learnBefore();
        ToolErrorRate baseline;
        int i;
        if (accepted != null) {
            baseline = accepted.asRate();
            i = 0;
            while (i < buckets.size() && buckets.get(i).bucket().compareTo(accepted.acceptedAt()) < 0) {
                i++;
            }
        } else if (carried != null && carriedBaseline != null && keepsCarried(carried, carriedBaseline, config)) {
            // Re-learning would read the leading buckets of a window that has slid forward, walking after the
            // degradation. A copy, because one still learning grows below.
            baseline = carriedBaseline.copy();
            i = 0;
        } else {
            baseline = new ToolErrorRate();
            learnedThrough = null;
            i = 0;
            // A reset that dropped the reference means "the old normal was wrong", so learn only after it.
            while (i < buckets.size()
                    && carried != null
                    && carried.fencedOff(buckets.get(i).bucket())) {
                i++;
            }
            // Stops at the first hour that has not settled, and at the learning fence.
            while (i < buckets.size() && baseline.calls() < config.minBaselineCalls()) {
                HourlyToolTally b = buckets.get(i);
                if (!CarriedState.settled(b.bucket(), clock, settle)) break;
                if (carried != null && carried.fencedForLearning(b.bucket())) break;
                fold(baseline, b.calls(), b.failures());
                learnedThrough = b.bucket();
                i++;
            }
        }
        if (baseline.calls() < config.minBaselineCalls()) return null; // still learning; waits, never skipped

        // Resume only when built under this exact tuning against this exact reference; see resumableUnder.
        State state = State.EMPTY;
        String watermark = null;
        boolean resumed = false;
        if (carried != null && carried.resumableUnder(epoch, baseline)) {
            resumed = true;
            state = carried.state();
            watermark = carried.watermarkBucket();
        }
        // A rebuild would otherwise re-read the hours a human reset just ruled on and re-accumulate the spell they
        // closed.
        @Nullable CarriedState fence = resumed ? null : carried;

        for (int j = i; j < buckets.size(); j++) {
            HourlyToolTally b = buckets.get(j);
            // Strictly after the watermark: folding a bucket twice is how a retried sweep invents a case.
            boolean afterWatermark = watermark == null || b.bucket().compareTo(watermark) > 0;
            if (afterWatermark && !(fence != null && fence.fencedOff(b.bucket()))) {
                state = ToolErrorDetector.advanceBucket(state, baseline, config, b.calls(), b.failures(), b.bucket());
                watermark = b.bucket();
            }
            // Learning is gated by learnedThrough and the settle delay, never by the watermark: an hour judged while
            // unsettled is learned on a later pass. Judged first, learned second, so no hour is judged against a
            // reference that holds it. A pinned reference never learns.
            if (accepted == null
                    && baseline.calls() < config.freezeBaselineCalls()
                    && CarriedState.settled(b.bucket(), clock, settle)
                    && (learnedThrough == null || b.bucket().compareTo(learnedThrough) > 0)
                    && !(carried != null && carried.fencedForLearning(b.bucket()))) {
                fold(baseline, b.calls(), b.failures());
                learnedThrough = b.bucket();
            }
        }

        // The pending absorb and both fences are human decisions, carried through untouched.
        CarriedState next = new CarriedState(
                toolKey,
                state,
                baseline,
                watermark,
                epoch,
                carried == null ? null : carried.pendingPinBy(),
                carried == null ? null : carried.pendingPinAt(),
                carried == null ? null : carried.resetAt(),
                learnedThrough,
                learnBefore);
        Decision decision = ToolErrorDetector.decide(state, baseline, config);
        return new Replayed(
                next, decision.fired() ? new Spell(toolKey, decision, baseline, decision.onsetAt(), watermark) : null);
    }

    /** A row that never advanced a watermark re-learns from the leading buckets after the reset fence. */
    private static boolean keepsCarried(CarriedState carried, ToolErrorRate carriedBaseline, ToolErrorConfig config) {
        boolean learning = carriedBaseline.calls() >= config.minBaselineCalls()
                && carriedBaseline.calls() < config.freezeBaselineCalls();
        return !learning || carried.learnedThrough() != null;
    }

    private static void fold(ToolErrorRate into, long calls, long failures) {
        into.addCounts(calls, failures);
    }
}
