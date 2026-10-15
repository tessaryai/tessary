// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import ai.tessary.classifier.metric.MetricDriftDetector.Decision;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * When a tool-duration shift accounts for a turn-duration shift on the same call site, the tool finding is emitted with
 * the turn shift as evidence: one cause, one finding. An unexplained turn shift (e.g. more tool calls, each as fast as
 * ever) still fires, since a flat tool bucket explains nothing. Compared in milliseconds, not log units, and with a
 * loose bar because a per-call delta understates a tool called several times per turn.
 */
public final class MetricSuppression {

    private MetricSuppression() {}

    /** Below a producer's millisecond resolution. */
    private static final double NO_MOVEMENT_MILLIS = 0.1;

    /**
     * @param callSites a tool bucket is not scoped by call site, so this is every entry point that fed the window
     * @param curMillis the median, because {@code e^meanLog} is a geometric mean and not additive in time
     */
    public record Shift(
            String bucketKey, Set<String> callSites, Decision decision, double refMillis, double curMillis) {

        public double deltaMillis() {
            return curMillis - refMillis;
        }
    }

    /** @param covered above 1 is ordinary (the agent may also have dropped a step) and is not clamped */
    public record Explanation(Shift tool, double covered) {}

    /**
     * Direction is the sign of the deltas, not {@link Decision#direction()}: W₁ can fire on a tail shift while the
     * median moves the other way.
     */
    public static @Nullable Explanation explain(Shift turn, List<Shift> tools, double explainedByFraction) {
        double turnDelta = turn.deltaMillis();
        // Answered rather than propagated as a NaN comparison.
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
