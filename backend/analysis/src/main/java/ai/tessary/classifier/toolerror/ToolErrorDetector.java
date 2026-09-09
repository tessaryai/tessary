// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import org.jspecify.annotations.Nullable;

/**
 * A Bernoulli CUSUM over one tool's calls: in-control rate and running state in, an alarm or a reason
 * for silence out. Design contract: {@code classifiers/tool_error/PROGRAM.md} §4.
 *
 * <p><b>Pure by design, and the design depends on it.</b> No database, no Spring, no clock — the caller
 * supplies event times. PROGRAM.md §12's null run replays a real corpus through this class directly, and
 * it is that run, not a review, that replaces {@link ToolErrorConfig#ARL_FIT_INTERCEPT}.
 *
 * <h2>Why sequential, and not two windows compared</h2>
 *
 * <p>An earlier draft of this classifier closed fixed windows and compared them on effect size, mirroring
 * metric drift. That was wrong for a rate, for a reason that is structural rather than statistical: a
 * window is a boundary, and a boundary both delays and dilutes. At a window of two thousand calls a tool
 * called two hundred times a day is judged every ten days, and a regression that begins mid-window is
 * averaged against its own healthy first half — so the very first window after a break is the one least
 * likely to show it.
 *
 * <p>A CUSUM has no boundary. It accumulates evidence call by call and alarms as soon as the evidence is
 * sufficient, which for a sustained doubling is a thousand to two thousand calls whenever they arrive.
 * It also knows <b>when the shift began</b> — the last moment the statistic sat at zero — which is what a
 * case's onset should say and what a window scheme can only approximate to its own resolution.
 *
 * <p>The precedent is in the repo: {@code trend/CusumDetector} already runs a CUSUM on grader pass rate,
 * which is the same shape of quantity. Metric drift's §4.5 rejects CUSUM for duration and cost, and that
 * argument does not reach here — it turns on those measures being heavy-tailed, where a mean-shift test
 * misses a p95 move that leaves the median still. A failure rate is a bounded proportion and has no tail
 * to miss.
 *
 * <h2>The score</h2>
 *
 * <p>Each call contributes the log-likelihood ratio of the shifted rate against the in-control one:
 *
 * <pre>
 *   failure -> ln(p1 / p0)                 positive, large when failures are rare in control
 *   success -> ln((1 - p1) / (1 - p0))     negative, small
 *   S = max(0, S + score);  alarm when S >= h
 * </pre>
 *
 * <p>Scoring this way gets most of the way to one threshold across every tool, but not all of it. Holding
 * the false-alarm run length at 250,000 calls, the required threshold is 5.8 at a 0.5% in-control rate,
 * 8.1 at 5% and 9.6 at 20%. A detector scored in percentage points would need a table; this one needs a
 * line, which is {@link ToolErrorConfig#decisionIntervalFor(double)}.
 *
 * <h2>Two questions, and only one of them gates</h2>
 *
 * <p>The accumulator answers <em>did this change, and when did it start</em>. How big the change is, and
 * how much damage it has done, are separate questions answered by {@link #criticality(double)} and
 * {@link #cohensH(double, double)} — <b>reported, never a trigger</b>. An earlier draft gated alarms on
 * Cohen's h and measured it over every call since the reference was pinned, which on a tool with history
 * is the baseline by construction; it silenced every real outage. The small-shift leak that gate was
 * covering for now lives in the threshold, where a rate below the accumulator's own break-even point
 * cannot reach the line at all.
 */
public final class ToolErrorDetector {

    private ToolErrorDetector() {}

    /** Which arm alarmed. Both run, and both are worth hearing about. */
    public enum Direction {
        /** Failures rose. */
        UP,
        /**
         * Failures fell. Watched because a tool that stopped reporting errors has either been fixed or
         * stopped reporting, and only one of those is good news. Runs only above
         * {@link ToolErrorConfig#downArmMinRate()} — a halving of a rate that was already negligible is
         * neither interesting nor, at any sane run length, detectable.
         */
        DOWN;

        public String wire() {
            return this == UP ? "up" : "down";
        }
    }

    /** Why no alarm. Every silent decision carries exactly one, so silence is never mistaken for absence. */
    public enum Silence {
        /** No in-control reference has been pinned yet. Ordinary on a tool nobody has called much. */
        NO_BASELINE,

        /**
         * The reference holds fewer than {@link ToolErrorConfig#minBaselineCalls()} calls. A <b>wait</b>,
         * not a skip: the tool keeps accumulating until it has enough, so a rare tool is watched on a
         * slower clock rather than never.
         */
        BELOW_MIN_BASELINE,

        /** Both accumulators are below the decision interval. The overwhelmingly common outcome. */
        IN_CONTROL
    }

    /**
     * The running state of both arms for one tool. Carried between sweeps as part of {@link CarriedState},
     * so a sweep resumes where the last one left off rather than starting the evidence over.
     *
     * @param sUp evidence accumulated that the rate has risen; never negative, and no longer capped
     * @param sDown evidence accumulated that it has fallen; never negative
     * @param onsetUpAt event time of the call at which {@code sUp} last left zero, or null while it sits
     *     there. <b>This is what a case reports as its onset</b> — the moment the run of evidence began,
     *     not the moment the sweep happened to notice, and the thing a window scheme cannot recover.
     *     It is also the one value here that cannot be reconstructed from anything else.
     * @param onsetDownAt the same for the improvement arm
     * @param callsSinceOnsetUp calls in the up arm's current run. Dies with the run, so it always spans
     *     exactly the stretch the accumulator is holding evidence about
     * @param callsSinceOnsetDown the same for the improvement arm
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
     * One alarm, with everything a finding needs to explain itself.
     *
     * <p>Every rate here is measured over the run since onset, never over the tool's history. The two are
     * wildly different on a tool with a large reference — a 5% tool in an 80% outage reads 83% over the
     * run and 5.001% over its lifetime — and only the first is an answer to "how is this tool doing now".
     *
     * @param statistic the accumulator at the moment of the decision, uncapped
     * @param threshold the decision interval in force for this tool's base rate, for the finding to quote
     * @param criticality {@link #criticality(double)} of the statistic; the ranking weight and the badge
     * @param failuresSinceOnset derived from the accumulator, not counted — see {@link #failuresFromS}
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
     * The in-control rate, Jeffreys-smoothed: {@code (failures + 0.5) / (calls + 1)}.
     *
     * <p>The smoothing is not a nicety. A tool that has never failed has a raw rate of exactly zero, and
     * {@code ln(p1 / 0)} is not a number — so the unsmoothed estimator makes the single most alarming
     * case in the product the one case the detector cannot score. Jeffreys is the standard answer and
     * costs nothing anywhere else: at the 500-call minimum it moves a 1% rate to 1.1%, and its influence
     * vanishes as the reference thickens.
     */
    public static double baselineRate(ToolErrorRate pinned) {
        return (pinned.failures() + 0.5) / (pinned.calls() + 1.0);
    }

    /**
     * The risen rate the up arm is tuned to catch quickly.
     *
     * <p>The floor is what makes a clean tool watchable: twice nearly-zero is still nearly-zero, so a
     * purely multiplicative target would tune the detector for a shift too small to tell from silence.
     * Capped below 1 because a rate cannot exceed certainty.
     */
    public static double shiftedUp(double p0, ToolErrorConfig config) {
        return Math.min(Math.max(p0 * config.shiftMultiple(), p0 + config.shiftFloor()), 0.99);
    }

    /** The fallen rate the down arm is tuned for. Floored above zero so the log stays finite. */
    public static double shiftedDown(double p0, ToolErrorConfig config) {
        return Math.max(p0 / config.shiftMultiple(), 1e-6);
    }

    /**
     * Cohen's h between two proportions — the REPORTED effect size, never the trigger.
     *
     * <p>Exposed because the eval harness prints it beside the run length, and a harness computing its
     * own copy is a harness that can disagree with the detector it is measuring.
     */
    public static double cohensH(double refRate, double curRate) {
        return 2.0 * (Math.asin(Math.sqrt(clamp01(curRate))) - Math.asin(Math.sqrt(clamp01(refRate))));
    }

    /**
     * How critical a spell is, from the accumulated evidence alone. The ranking weight and the badge.
     *
     * <p><b>The accumulator at the moment of the alarm carries no information about size</b> — it just
     * crossed the line from below, so it reads about the threshold whether the tool drifted a fraction of
     * a point or died completely. What separates them is how fast it got there and how far it keeps
     * going: a 5% tool at 80% adds 0.543 per call, the same tool at 7.5% adds 0.00194. So criticality is
     * read after the crossing, not at it, and it blends severity, duration and traffic into one number —
     * which is the honest shape for "how much has this cost me", and deliberately not an answer to "how
     * bad is it per call".
     *
     * <p>Log scale because the raw statistic spans single digits at the alarm to six figures a week into
     * an outage. Natural log, so every 10 points is 2.72x more accumulated evidence. Unnormalised: a case
     * opens near 18-25 rather than at a common zero, because a clean tool needs less evidence to be
     * believed than a noisy one, and dividing that away would hide a real difference between tools.
     */
    public static double criticality(double s) {
        return s <= 1.0 ? 0.0 : 10.0 * Math.log(s);
    }

    /**
     * The failures behind an accumulator reading, recovered rather than counted.
     *
     * <p>{@code S = f·w_fail + (n − f)·w_ok}, so {@code f = (S − n·w_ok) / (w_fail − w_ok)}. <b>Exact
     * within a run</b>, because a run is by definition a stretch over which the accumulator never touched
     * its floor — touching it is what ends the run — so no information was clipped away.
     *
     * <p>This is why the failure count is not a stored column. Keeping it would be storing a second copy
     * of something the accumulator already determines, and a second copy is a thing that can disagree.
     */
    public static long failuresFromS(double s, long calls, double p0, double p1) {
        if (calls <= 0) return 0;
        double wFail = Math.log(p1 / p0);
        double wOk = Math.log((1 - p1) / (1 - p0));
        double denominator = wFail - wOk;
        if (Math.abs(denominator) < 1e-12) return 0; // p1 == p0: a detector that cannot fire anyway
        long f = Math.round((s - calls * wOk) / denominator);
        return Math.max(0, Math.min(calls, f));
    }

    private static double clamp01(double p) {
        return p < 0 ? 0 : Math.min(p, 1);
    }

    /**
     * Fold one tool call into both arms.
     *
     * <p>Called once per call in event order. Cheap by construction — two logs and two adds — because at
     * real ingest volume this runs on every tool call the platform sees.
     *
     * @param eventAt the call's event time, recorded as the onset when an arm leaves zero
     */
    public static State advance(
            State state, ToolErrorRate pinned, ToolErrorConfig config, boolean failed, String eventAt) {
        return advanceBucket(state, pinned, config, 1, failed ? 1 : 0, eventAt);
    }

    /**
     * Fold a whole bucket of calls into both arms in one step — the grouped Bernoulli CUSUM.
     *
     * <p>Closed form rather than a loop over {@link #advance}, and that is not only an optimization: a
     * busy tool over a month is millions of calls, and a per-call loop would make recompute-per-read
     * unaffordable for precisely the tools most worth watching.
     *
     * <p><b>It is a real approximation, in one direction.</b> The accumulator's floor at zero now applies
     * once per bucket rather than once per call, so within an hour a run of failures followed by successes
     * cannot dip to zero and restart. A grouped replay is therefore very slightly slower to forget a burst
     * than a per-call one; it is never more sensitive. §4.3's run lengths were computed per call, so the
     * null run against a real corpus is the authority over them, not this method.
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

    /**
     * The run's call count, on exactly the rule {@link #onset} uses for the run's start.
     *
     * <p>Kept in lockstep with the onset deliberately: the denominator of the reported rate has to span
     * the same stretch the onset names, or a finding says "83% since 14:03" about a window that does not
     * start at 14:03.
     */
    private static long run(long current, double before, double after, long calls) {
        if (after <= 0.0) return 0;
        return (before <= 0.0 ? 0 : current) + calls;
    }

    private static double bucketStep(double s, double p0, double p1, long failures, long successes) {
        return floor(s + failures * Math.log(p1 / p0) + successes * Math.log((1 - p1) / (1 - p0)));
    }

    /**
     * Floored at zero, a CUSUM's reset. <b>No ceiling.</b>
     *
     * <p>The floor is load-bearing and the ceiling was not. Without the floor, a tool that ran clean for a
     * month would build a deficit so deep that a real outage would take weeks to dig out of, and there
     * would be no such thing as an onset — the statistic returning to zero IS the statement that whatever
     * happened is over.
     *
     * <p>There used to be a ceiling at three times the threshold, because an uncapped accumulator recovers
     * in proportion to the damage rather than to the fix: a 400,000-call outage needs three million clean
     * calls to drain, so a fixed tool stayed accused for months. That mattered while a draining
     * accumulator was how a case closed itself. Cases now stay open until a human closes them, and closing
     * one resets the accumulator outright, so the ceiling has nothing left to protect — and removing it is
     * what lets {@link #criticality(double)} keep separating a bad outage from a mild one instead of
     * pinning both to the same number within a day.
     */
    private static double floor(double s) {
        return Math.max(0.0, s);
    }

    /**
     * When this arm's current run of evidence began.
     *
     * <p>Set on the call that lifts the accumulator off zero, cleared when it returns — so a spell of
     * failures that the following successes wash out leaves no trace, and a spell that survives to alarm
     * reports the call it actually started on. That is the whole reason a case's onset can be honest here:
     * the statistic returning to zero IS the statement that whatever happened is over.
     */
    private static @Nullable String onset(@Nullable String current, double before, double after, String eventAt) {
        if (after <= 0.0) return null;
        return before <= 0.0 ? eventAt : current;
    }

    /**
     * Whether either arm has crossed its decision interval.
     *
     * <p>The up arm is checked first: when a tool's rate has both risen against its reference and, on some
     * other reading, fallen, the rise is the one somebody needs to hear about.
     *
     * <p>A crossing is now sufficient. Nothing downstream re-judges it — see the class note on the two
     * questions, and {@link ToolErrorConfig#DEFAULT_MIN_EFFECT_SIZE} for what used to sit here.
     */
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
