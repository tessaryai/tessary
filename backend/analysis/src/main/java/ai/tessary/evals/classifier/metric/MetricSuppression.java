// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.metric;

import ai.tessary.evals.classifier.metric.MetricDriftDetector.Decision;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One event, one finding — the cross-grain half of {@code classifiers/metric_drift/PROGRAM.md} §6.1.
 *
 * <p>{@code duration_drift} is one on/off switch spanning two grains: a turn's own duration and the
 * duration of each tool call inside it. That is deliberate — "this turn was slow" sends someone to read
 * traces, whereas "this turn was slow and 34 of its 38 seconds were one {@code search_docs} call against
 * a p50 of 400 ms" names the fix — but it means one real event can present on both measures. A turn that
 * is slow <em>because</em> one tool is slow is ONE cause, and a findings stream with no alert budget
 * (§9) cannot afford to say it twice.
 *
 * <p>So: when a tool-duration shift accounts for a turn-duration shift on a call site whose traffic that
 * tool bucket was measured over, the <b>tool</b> finding is emitted and the turn shift rides on it as
 * evidence. The tool row names the fix; the turn row only restates the symptom, and nothing is lost
 * because the symptom is attached to the row that survives.
 *
 * <h2>What must NOT be suppressed</h2>
 *
 * <p>An unexplained turn shift still fires on its own, and that case is the whole reason turn duration is
 * measured at all: <em>eleven tool calls where three used to do</em>. Every individual call is exactly as
 * fast as it always was, so every tool bucket's distribution is flat and no tool finding exists — what
 * moved is the COUNT, which is invisible at tool grain by construction. The arithmetic below protects
 * that case with a wide margin rather than by a special case: a flat tool bucket contributes a Δ of
 * approximately zero, which cannot cover any fraction of a turn's Δ.
 *
 * <h2>Why absolute time, and why a generous bar</h2>
 *
 * <p>The comparison is in milliseconds, not in log units, because "this tool's slowdown accounts for the
 * turn's" is a claim about <em>time</em>: a tool whose p50 went 20 ms → 40 ms doubled, and explains none
 * of a turn that gained four seconds. Ratios are the right scale for deciding whether something moved and
 * the wrong one for deciding what a move is made of.
 *
 * <p>The coverage bar is deliberately loose ({@link MetricDriftConfig#DEFAULT_EXPLAINED_BY_FRACTION}) for
 * two reasons. First, a tool bucket's Δ is measured <b>per call</b> while a turn's is per turn, and a turn
 * usually makes more than one call to the tool that dominates it — so a single call's Δ systematically
 * understates its own contribution, and demanding full coverage would mean the rule almost never fires.
 * Second, suppressing costs nothing: the turn shift is attached to the tool finding either way, so the
 * only thing at stake is which of the two is the headline, and the tool is the one that names a fix.
 * Partial explanation is still explanation.
 *
 * <p>Pure — decisions and two numbers in, a verdict out. No database, no clock, no Spring.
 */
public final class MetricSuppression {

    private MetricSuppression() {}

    /**
     * Deltas smaller than this are treated as no movement at all. Whole milliseconds are the finest
     * quantity any producer reports, so a tenth of one is below the measurement's own resolution — and a
     * turn whose median did not move has nothing for a tool to account for, however far its tail ran.
     */
    private static final double NO_MOVEMENT_MILLIS = 0.1;

    /**
     * One shifted window, reduced to what the rule needs. Built only from decisions that actually
     * {@link Decision#fired()} — a comparison that stayed under the floor is not a shift and cannot
     * explain or be explained.
     *
     * @param callSites the entry points whose traffic this window was measured over. A turn bucket IS a
     *     call site, so its set is that one; a tool bucket is keyed on an {@code ActionSymbol} alone and
     *     is deliberately not scoped by call site (a tool's latency is a tool's latency whoever called
     *     it), so its set is however many entry points contributed to the window that just closed.
     * @param refMillis the reference window's median in milliseconds
     * @param curMillis the closed window's median in milliseconds. The MEDIAN rather than the mean
     *     because a sketch's mean lives in log space, and {@code e^meanLog} is the geometric mean —
     *     which is not additive in time and so cannot be reasoned about as a share of a turn. The p50 is
     *     a real number of milliseconds and is already what the finding's evidence prints.
     */
    public record Shift(
            String measure,
            String bucketKey,
            Set<String> callSites,
            Decision decision,
            double refMillis,
            double curMillis) {

        /** How far the median moved, in milliseconds. Signed: positive is slower. */
        public double deltaMillis() {
            return curMillis - refMillis;
        }
    }

    /**
     * A turn shift and the tool shift that accounts for it.
     *
     * @param covered the share of the turn's Δ the tool's own Δ covers. Above 1 is ordinary and not an
     *     error — a tool that slowed by six seconds inside a turn that slowed by four has explained the
     *     turn and then some, which happens when the agent also dropped a step. It is reported as
     *     measured rather than clamped, because "1.5" and "1.0" say different things to whoever reads
     *     the evidence.
     */
    public record Explanation(Shift tool, double covered) {}

    /**
     * The tool shift that best accounts for {@code turn}, or null when none does.
     *
     * <p>A candidate qualifies when it was measured over the turn's call site, moved the same way, and
     * covers at least {@code explainedByFraction} of the turn's Δ. The best of them is the one covering
     * most — with several tools inside one call site slowing at once, the finding a human should read
     * first is the one that accounts for the most time.
     *
     * <p>Direction is tested as the SIGN of the two deltas rather than against
     * {@link Decision#direction()}, because those two can honestly disagree: W₁ can fire upward on a
     * shift that lives entirely in the tail while the median holds or drifts the other way. Testing both
     * would mean two answers to one question; the rule is stated in absolute time, so absolute time is
     * what decides it. An opposite-signed candidate yields a negative coverage and falls out below.
     *
     * @param tools every tool-duration shift this pass produced, in emission order
     */
    public static @Nullable Explanation explain(Shift turn, List<Shift> tools, double explainedByFraction) {
        double turnDelta = turn.deltaMillis();
        // Not finite when a window is empty — unreachable for a fired decision, and answered rather than
        // propagated as a NaN comparison that reads as "no" for reasons nothing states.
        if (!Double.isFinite(turnDelta) || Math.abs(turnDelta) < NO_MOVEMENT_MILLIS) return null;

        Explanation best = null;
        for (Shift tool : tools) {
            if (Collections.disjoint(tool.callSites(), turn.callSites())) continue;
            double toolDelta = tool.deltaMillis();
            if (!Double.isFinite(toolDelta)) continue;
            double covered = toolDelta / turnDelta;
            if (covered < explainedByFraction) continue;
            if (best == null || covered > best.covered()) best = new Explanation(tool, covered);
        }
        return best;
    }
}
