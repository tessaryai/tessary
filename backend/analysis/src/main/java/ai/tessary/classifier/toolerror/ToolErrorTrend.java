// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.toolerror.ToolErrorDetector.Decision;
import ai.tessary.classifier.toolerror.ToolErrorDetector.Direction;
import ai.tessary.classifier.toolerror.ToolErrorDetector.State;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository.AcceptedReference;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Replays a project's hourly tool tallies through {@link ToolErrorDetector} and reports which tools are
 * in an unrecovered degraded spell right now. Design contract:
 * {@code classifiers/tool_error/PROGRAM.md} §5.
 *
 * <p><b>Pure given its input, and that is still the whole design.</b> No database, no Spring, no clock.
 * What changed is that the accumulator it starts from is now an input rather than always zero: a sweep
 * hands in what the last sweep left, and gets back what this one leaves.
 *
 * <p>That reintroduces the cursor/double-count bug class this classifier used to be immune to, and
 * {@code classifiers/tool_error/PROGRAM.md} §5 explains what bought it back. The defence is entirely in
 * {@link CarriedState}: fold only buckets strictly after the watermark, and rebuild from scratch rather
 * than resume whenever the tuning or the reference has moved. Both live on the type rather than in a
 * caller's head, because a caller that forgets either produces wrong numbers with nothing to notice.
 *
 * <h2>Grouped, not per call</h2>
 *
 * <p>The detector scores one call at a time; the input here is an hour of them. Each bucket contributes
 * its calls' scores in one step, and the accumulator's floor at zero is applied per BUCKET rather than
 * per call.
 *
 * <p>That is the standard grouped Bernoulli CUSUM and it is a real approximation, in one direction:
 * within an hour a run of failures followed by successes cannot dip the accumulator to zero and restart,
 * so a grouped replay is very slightly slower to forget a burst than a per-call one. It never makes the
 * detector more sensitive. What it buys is that the whole history is one aggregate query instead of every
 * row, which is what makes recompute affordable at all. §4.3's run lengths were computed per call, so
 * PROGRAM.md §12's null run must be read as the authority over them.
 */
public final class ToolErrorTrend {

    private ToolErrorTrend() {}

    /**
     * One tool currently in an unrecovered spell.
     *
     * @param decision the alarm, carrying the rates, the effect size and the onset. <b>Its rates span the
     *     run since onset</b>, which is the only window that answers "how is this tool doing now"
     * @param baseline the in-control reference the spell is measured against
     * @param observed the calls this sweep folded in — the buckets after the last watermark, not the whole
     *     history since the reference. Incremental sweeps mean no single pass sees that history, and a
     *     field that silently meant "everything" on a rebuild and "the last hour" on a resume would be
     *     worse than one that means the same thing every time
     * @param onsetBucket the hour the spell began, or null when the detector could not bracket it
     */
    public record Spell(
            String toolKey,
            Decision decision,
            ToolErrorRate baseline,
            ToolErrorRate observed,
            @Nullable String onsetBucket) {}

    /**
     * What one sweep produced: the tools alarming now, and the state every tool should carry forward.
     *
     * <p>The two are separate because a tool that is NOT alarming still has state worth keeping — an
     * accumulator that is halfway up, or a run that started an hour ago and has not crossed yet. Returning
     * only spells would throw that away and make the next sweep start over.
     */
    public record Sweep(List<Spell> spells, List<CarriedState> advanced) {}

    /**
     * The schema version baked into every epoch. <b>Bump it whenever the meaning of a stored accumulator
     * changes</b> — a new arm, a different floor, a change to what a call contributes — and every carried
     * row rebuilds itself on the next sweep instead of resuming under assumptions that no longer hold.
     */
    public static final String STATE_SCHEMA_VERSION = "v2-onset";

    /**
     * Advance every tool's state to the end of its buckets and return the tools alarming there.
     *
     * <p><b>At the end, not anywhere during it.</b> A tool that degraded in the middle of the window and
     * has since recovered is not firing now, and the {@link ai.tessary.cases.CaseSource} contract is
     * "currently firing" — reporting it would open a case that nothing will ever close.
     *
     * @param tallies every tool's hourly buckets, oldest first. Order matters: a CUSUM fed out of order
     *     is not a CUSUM, which is why the query sorts rather than leaving it to the caller.
     * @param carried what the last sweep left, by tool. Empty is always safe — it rebuilds.
     */
    public static Sweep sweep(
            List<HourlyToolTally> tallies,
            ToolErrorConfig config,
            Map<String, AcceptedReference> accepted,
            Map<String, CarriedState> carried) {
        Map<String, List<HourlyToolTally>> byTool = new LinkedHashMap<>();
        for (HourlyToolTally t : tallies) {
            byTool.computeIfAbsent(t.toolKey(), k -> new ArrayList<>()).add(t);
        }
        List<Spell> spells = new ArrayList<>();
        List<CarriedState> advanced = new ArrayList<>();
        for (Map.Entry<String, List<HourlyToolTally>> e : byTool.entrySet()) {
            Replayed r = replay(e.getKey(), e.getValue(), config, accepted.get(e.getKey()), carried.get(e.getKey()));
            if (r == null) continue; // still learning a reference; nothing to judge and nothing to carry
            if (r.spell() != null) spells.add(r.spell());
            advanced.add(r.carried());
        }
        return new Sweep(List.copyOf(spells), List.copyOf(advanced));
    }

    /** One tool's outcome: the state to carry forward, and its spell when it is alarming. */
    record Replayed(CarriedState carried, @Nullable Spell spell) {}

    /** Replay one tool. Package-private so a test can drive a single series without assembling a map. */
    static @Nullable Replayed replay(
            String toolKey,
            List<HourlyToolTally> buckets,
            ToolErrorConfig config,
            @Nullable AcceptedReference accepted,
            @Nullable CarriedState carried) {
        // The reference is built from the leading buckets until it is thick enough to judge against, then
        // frozen. Frozen, not sliding: a reference that moved with the traffic would drift along with a
        // slow degradation and never notice it — the failure CusumDetector's comment names as the reason
        // the old rolling-baseline gate was replaced.
        //
        // UNLESS a human has pinned one. Then that reference IS the in-control rate and the replay starts
        // after the moment it was accepted — see AcceptedReference#acceptedAt. Re-learning from the leading
        // buckets would rebuild the very reference the human replaced, and replaying the pre-acceptance
        // history against the new one would re-accumulate the evidence they just absorbed; either way the
        // finding returns on the next pass and the button does nothing.
        // Read once rather than twice: an accessor called in the guard and again in the branch is two
        // calls that only happen to agree, which is exactly what a null analysis cannot assume.
        ToolErrorRate carriedBaseline = carried == null ? null : carried.baseline();
        ToolErrorRate baseline;
        int i;
        if (accepted != null) {
            baseline = accepted.asRate();
            i = 0;
            while (i < buckets.size() && buckets.get(i).bucket().compareTo(accepted.acceptedAt()) < 0) {
                i++;
            }
        } else if (carriedBaseline != null) {
            // Learned once, on some earlier sweep, and kept. Re-learning it here would read the leading
            // buckets of a window that has slid forward since, which is a reference walking after the very
            // degradation it is supposed to be measuring.
            baseline = carriedBaseline;
            i = 0;
        } else {
            baseline = new ToolErrorRate();
            i = 0;
            while (i < buckets.size() && baseline.calls() < config.minBaselineCalls()) {
                HourlyToolTally b = buckets.get(i);
                fold(baseline, b.calls(), b.failures());
                i++;
            }
        }
        if (baseline.calls() < config.minBaselineCalls()) return null; // still learning; waits, never skipped

        // Resume or rebuild. Resuming is the fast path and the fragile one, so it is taken only when the
        // state was built under this exact tuning against this exact reference — see resumableUnder.
        String epoch = CarriedState.epochOf(config, STATE_SCHEMA_VERSION);
        State state = State.EMPTY;
        String watermark = null;
        if (carried != null && carried.resumableUnder(epoch, baseline)) {
            state = carried.state();
            watermark = carried.watermarkBucket();
        }

        ToolErrorRate observed = new ToolErrorRate();
        for (int j = i; j < buckets.size(); j++) {
            HourlyToolTally b = buckets.get(j);
            // Strictly after the watermark. A bucket at or before it has already been folded in, and
            // folding it again is how a retried sweep invents a case out of evidence it already counted.
            if (watermark != null && b.bucket().compareTo(watermark) <= 0) continue;
            state = ToolErrorDetector.advanceBucket(state, baseline, config, b.calls(), b.failures(), b.bucket());
            fold(observed, b.calls(), b.failures());
            watermark = b.bucket();
        }

        // The pending absorb rides through untouched: it is a human decision, and a sweep passing over it
        // must neither honour nor forget it. ToolErrorService installs it once the run is thick enough.
        CarriedState next = new CarriedState(
                toolKey,
                state,
                baseline,
                watermark,
                epoch,
                carried == null ? null : carried.pendingPinBy(),
                carried == null ? null : carried.pendingPinAt());
        Decision decision = ToolErrorDetector.decide(state, baseline, config);
        return new Replayed(
                next, decision.fired() ? new Spell(toolKey, decision, baseline, observed, decision.onsetAt()) : null);
    }

    /** Fold a bucket's counts into a rate. Bulk, for the reason {@link ToolErrorRate#addCounts} gives. */
    private static void fold(ToolErrorRate into, long calls, long failures) {
        into.addCounts(calls, failures);
    }

    /** The direction word a finding's cause key carries. */
    public static String directionOf(Decision decision) {
        return decision.direction() == Direction.UP ? "up" : "down";
    }
}
