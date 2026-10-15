// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import org.jspecify.annotations.Nullable;

/**
 * A Bernoulli CUSUM over one tool's calls (tool-error.md §4), pure. Sequential rather than windowed, so a mid-window
 * regression is not diluted and the onset is known. Each failure adds {@code ln(p1/p0)}, each success
 * {@code ln((1-p1)/(1-p0))}, floored at zero; it alarms at the per-tool threshold from
 * {@link ToolErrorConfig#decisionIntervalFor(double)}. Effect size is reported, never a trigger: gating on it silenced
 * real outages.
 */
public final class ToolErrorDetector {

    private ToolErrorDetector() {}

    public enum Direction {
        UP,
        /**
         * A tool that stopped reporting errors was either fixed or stopped reporting. Runs only above
         * {@link ToolErrorConfig#downArmMinRate()}.
         */
        DOWN;

        public String wire() {
            return this == UP ? "up" : "down";
        }
    }

    public enum Silence {
        NO_BASELINE,

        /** A wait, not a skip: a rare tool is watched on a slower clock. */
        BELOW_MIN_BASELINE,

        IN_CONTROL
    }

    /**
     * Carried between sweeps in {@link CarriedState}. {@code onsetUpAt} is when {@code sUp} last left zero, what a case
     * reports as its onset, and the one value nothing else can reconstruct. The call counts die with their run.
     */
    public record State(
            double sUp,
            double sDown,
            @Nullable String onsetUpAt,
            @Nullable String onsetDownAt,
            long callsSinceOnsetUp,
            long callsSinceOnsetDown) {

        public static final State EMPTY = new State(0, 0, null, null, 0, 0);
    }

    /**
     * Every rate is over the run since onset, never the tool's history: a 5% tool in an 80% outage reads 83% over the
     * run and 5.001% over its lifetime. {@code failuresSinceOnset} is derived; see {@link #failuresFromS}.
     */
    public record Decision(
            boolean fired,
            Direction direction,
            double statistic,
            double threshold,
            double criticality,
            double baselineRate,
            double currentRate,
            double deltaPp,
            double effectSize,
            long callsSinceOnset,
            long failuresSinceOnset,
            long baselineCalls,
            @Nullable String onsetAt,
            @Nullable Silence silence) {}

    /**
     * Jeffreys-smoothed {@code (failures + 0.5) / (calls + 1)}: a tool that never failed has raw rate zero, and
     * {@code ln(p1 / 0)} would leave the most alarming case unscoreable.
     */
    public static double baselineRate(ToolErrorRate pinned) {
        return (pinned.failures() + 0.5) / (pinned.calls() + 1.0);
    }

    /** The floor keeps a clean tool watchable, since twice nearly-zero is still nearly-zero. Capped below 1. */
    public static double shiftedUp(double p0, ToolErrorConfig config) {
        return Math.min(Math.max(p0 * config.shiftMultiple(), p0 + config.shiftFloor()), 0.99);
    }

    /** Floored above zero so the log stays finite. */
    public static double shiftedDown(double p0, ToolErrorConfig config) {
        return Math.max(p0 / config.shiftMultiple(), 1e-6);
    }

    /** The reported effect size, never the trigger. */
    public static double cohensH(double refRate, double curRate) {
        return 2.0 * (Math.asin(Math.sqrt(clamp01(curRate))) - Math.asin(Math.sqrt(clamp01(refRate))));
    }

    /**
     * {@link #failuresFromS} is exact only against one reference; a reference that learns while judging
     * ({@link ToolErrorConfig#learnsWhileJudging()}) needs the counted witnesses instead.
     */
    public static Decision counted(Decision d, long failures) {
        long calls = d.callsSinceOnset();
        long counted = Math.max(0, Math.min(calls, failures));
        double current = calls == 0 ? d.baselineRate() : (double) counted / calls;
        return new Decision(
                d.fired(),
                d.direction(),
                d.statistic(),
                d.threshold(),
                d.criticality(),
                d.baselineRate(),
                current,
                (current - d.baselineRate()) * 100.0,
                cohensH(d.baselineRate(), current),
                calls,
                counted,
                d.baselineCalls(),
                d.onsetAt(),
                d.silence());
    }

    /**
     * The ranking weight and badge, read after the crossing because at the alarm the statistic always reads about the
     * threshold. Natural log, since the statistic spans single digits to six figures; a case opens near 18-25.
     */
    public static double criticality(double s) {
        return s <= 1.0 ? 0.0 : 10.0 * Math.log(s);
    }

    /**
     * {@code f = (S − n·w_ok) / (w_fail − w_ok)}, exact within a run since the accumulator never touched its floor. Not
     * stored, so it cannot disagree with the accumulator.
     */
    public static long failuresFromS(double s, long calls, double p0, double p1) {
        if (calls <= 0) return 0;
        double wFail = Math.log(p1 / p0);
        double wOk = Math.log((1 - p1) / (1 - p0));
        double denominator = wFail - wOk;
        if (Math.abs(denominator) < 1e-12) return 0;
        long f = Math.round((s - calls * wOk) / denominator);
        return Math.max(0, Math.min(calls, f));
    }

    private static double clamp01(double p) {
        return p < 0 ? 0 : Math.min(p, 1);
    }

    /**
     * The grouped CUSUM in closed form, so a month of a busy tool stays cheap to recompute on read. The floor applies
     * once per bucket, so it forgets a burst slightly slower than per-call; it is never more sensitive.
     */
    public static State advanceBucket(
            State state, ToolErrorRate pinned, ToolErrorConfig config, long calls, long failures, String eventAt) {
        long f = Math.max(0, Math.min(failures, calls));
        long ok = Math.max(0, calls) - f;
        if (calls <= 0) return state;

        double p0 = baselineRate(pinned);
        double up = bucketStep(state.sUp(), p0, shiftedUp(p0, config), f, ok);
        double down =
                p0 >= config.downArmMinRate() ? bucketStep(state.sDown(), p0, shiftedDown(p0, config), f, ok) : 0.0;
        return new State(
                up,
                down,
                onset(state.onsetUpAt(), state.sUp(), up, eventAt),
                onset(state.onsetDownAt(), state.sDown(), down, eventAt),
                run(state.callsSinceOnsetUp(), state.sUp(), up, calls),
                run(state.callsSinceOnsetDown(), state.sDown(), down, calls));
    }

    /** Kept in lockstep with {@link #onset}, or the rate's denominator would not span the stretch the onset names. */
    private static long run(long current, double before, double after, long calls) {
        if (after <= 0.0) return 0;
        return (before <= 0.0 ? 0 : current) + calls;
    }

    private static double bucketStep(double s, double p0, double p1, long failures, long successes) {
        return floor(s + failures * Math.log(p1 / p0) + successes * Math.log((1 - p1) / (1 - p0)));
    }

    /**
     * Floored at zero: otherwise a clean month builds a deficit an outage takes weeks to climb out of, and there is no
     * onset. No ceiling, so {@link #criticality(double)} keeps separating bad outages from mild ones.
     */
    private static double floor(double s) {
        return Math.max(0.0, s);
    }

    /** Set when the accumulator lifts off zero and cleared when it returns, so a washed-out spell leaves no trace. */
    private static @Nullable String onset(@Nullable String current, double before, double after, String eventAt) {
        if (after <= 0.0) return null;
        return before <= 0.0 ? eventAt : current;
    }

    /** The up arm is checked first: a rise is the one somebody needs to hear about. */
    public static Decision decide(State state, ToolErrorRate pinned, ToolErrorConfig config) {
        if (pinned.calls() == 0) return silent(state, pinned, config, Silence.NO_BASELINE);
        if (pinned.calls() < config.minBaselineCalls()) {
            return silent(state, pinned, config, Silence.BELOW_MIN_BASELINE);
        }

        double h = config.decisionIntervalFor(baselineRate(pinned));
        boolean up = state.sUp() >= h;
        boolean down = state.sDown() >= h;
        if (!up && !down) return silent(state, pinned, config, Silence.IN_CONTROL);

        return up
                ? alarm(state, pinned, config, Direction.UP, state.sUp(), state.callsSinceOnsetUp(), state.onsetUpAt())
                : alarm(
                        state,
                        pinned,
                        config,
                        Direction.DOWN,
                        state.sDown(),
                        state.callsSinceOnsetDown(),
                        state.onsetDownAt());
    }

    private static Decision alarm(
            State state,
            ToolErrorRate pinned,
            ToolErrorConfig config,
            Direction direction,
            double statistic,
            long callsSinceOnset,
            @Nullable String onsetAt) {
        double p0 = baselineRate(pinned);
        double p1 = direction == Direction.UP ? shiftedUp(p0, config) : shiftedDown(p0, config);
        long failures = failuresFromS(statistic, callsSinceOnset, p0, p1);
        double cur = callsSinceOnset == 0 ? p0 : (double) failures / callsSinceOnset;
        return new Decision(
                true,
                direction,
                statistic,
                config.decisionIntervalFor(p0),
                criticality(statistic),
                p0,
                cur,
                (cur - p0) * 100.0,
                cohensH(p0, cur),
                callsSinceOnset,
                failures,
                pinned.calls(),
                onsetAt,
                null);
    }

    private static Decision silent(State state, ToolErrorRate pinned, ToolErrorConfig config, Silence silence) {
        double p0 = pinned.calls() == 0 ? 0.0 : baselineRate(pinned);
        double s = Math.max(state.sUp(), state.sDown());
        return new Decision(
                false,
                Direction.UP,
                s,
                pinned.calls() == 0 ? 0.0 : config.decisionIntervalFor(p0),
                criticality(s),
                p0,
                p0,
                0.0,
                0.0,
                state.callsSinceOnsetUp(),
                0,
                pinned.calls(),
                null,
                silence);
    }
}
