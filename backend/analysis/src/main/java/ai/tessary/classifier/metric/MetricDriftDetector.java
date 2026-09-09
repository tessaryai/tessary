// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import org.jspecify.annotations.Nullable;

/**
 * Compare-and-decide for metric drift: two sketches of the same bucket plus the operating point
 * in, one decision out.
 *
 * <p><b>Pure by design, and the design depends on it.</b> No database, no Spring, no project
 * concepts, no clock. An eval replays a real corpus through this class directly, injecting a
 * 1.2x / 1.5x / 2.0x shift on one bucket and splitting unmodified traffic in half for the null
 * case, and it is that null run, not a review, that sets {@link MetricDriftConfig#w1Floor()}. An
 * eval that had to stand up a schema to ask "would this have fired" is an eval nobody runs often
 * enough to tune with.
 *
 * <p><b>Effect size, never significance.</b> The whole decision is {@code |signedW1| >= floor} on
 * a sample the caller has already checked is large enough. There is deliberately no test
 * statistic and no p-value: with 100k traces in a window a KS test calls a three-millisecond
 * shift significant, because significance inflates with sample size while effect size does not.
 * The floor is on the same scale the finding reports, so "we only want to hear about 15% moves"
 * is one edit.
 *
 * <p><b>The floor is scaled to the sample, not flat.</b> Effect size does not inflate with sample
 * size, but the noise in measuring it deflates with it, and a bucket that closes on a 24-hour
 * clock can be a fifth the size of the one it is compared against. {@link #effectiveFloor} holds
 * the false-alarm rate roughly constant across that range by raising the bar on thin windows,
 * never lowering it on thick ones, so the configured number stays a promise about the smallest
 * move that will ever be reported.
 *
 * <p><b>Both directions, same bar.</b> Faster and cheaper fire exactly as loudly as slower and
 * more expensive. That costs a trickle of "yes, we optimized that" dismissals and buys the one
 * regression that reads as a win everywhere else: an agent that quietly stopped doing its
 * verification step.
 *
 * <p>Silence is a first-class outcome and always carries a {@link Silence} reason. A detector
 * that returns "did not fire" without saying why is indistinguishable, in a log or an eval
 * report, from a detector that was never asked.
 */
public final class MetricDriftDetector {

    private MetricDriftDetector() {}

    /**
     * Which earlier window the current one was compared against. Both run on every close, because
     * each is provably blind in one direction on its own: {@link #PREVIOUS} catches sudden breaks
     * and never notices a slow boil, since every week looks like the last one; {@link #PINNED}
     * catches cumulative creep from a known-good point and then screams forever once something
     * legitimately changed.
     *
     * <p>The lowercase {@link #wire()} form is what the evidence blob carries and what the end of
     * a {@code cause_key} reads, e.g. {@code turn_duration:discover-sales-prospects:slower:pinned}.
     * That key is rendered verbatim in mono on the Classifiers page, so these are user-visible
     * words rather than an internal enum.
     */
    public enum Reference {
        /**
         * The bucket's own recent normal: the rolling control of {@link MetricControl}, a weighted
         * merge of the closed windows of the last three weeks with the days a confirmed regression
         * ran through left out. Catches sudden breaks.
         *
         * <p><b>The wire word stays {@code previous}.</b> It was one previously-closed window until
         * the control replaced it, and it is the last segment of every {@code cause_key} ever
         * written, so a rename would split one bucket's history into two causes and reopen
         * everything already resolved. Persisted strings are never renamed.
         */
        PREVIOUS,

        /**
         * The window pinned after the last deploy, moved only by a human pressing
         * <em>Legitimate, absorb</em>. Catches cumulative creep, which the control by construction
         * cannot: a change slow enough to be absorbed over a fortnight never looks like a break against a
         * fortnight of memory.
         */
        PINNED;

        public String wire() {
            return this == PREVIOUS ? "previous" : "pinned";
        }
    }

    /**
     * Which way the current window moved relative to its reference. The wire words are deliberately
     * measure-neutral: the same {@code up} is "slower" for a duration and "dearer" for a cost, and the
     * sentence a human reads is built where the measure is known.
     */
    public enum Direction {
        UP,
        DOWN;

        public String wire() {
            return this == UP ? "up" : "down";
        }
    }

    /** Why a comparison did not produce a finding. Every silent decision carries exactly one. */
    public enum Silence {
        /**
         * There is nothing to compare against: no window has closed yet ({@link Reference#PREVIOUS}) or
         * nothing has ever been pinned ({@link Reference#PINNED}). Ordinary on a young bucket; the
         * caller's first armed close is what establishes the reference.
         */
        NO_REFERENCE,

        /**
         * One of the two windows is below {@link MetricDriftConfig#minSample()}. A bucket here
         * <b>waits, it is not skipped</b>: a tool called thirty times a week keeps accumulating on
         * a slower clock until it has enough to compare, rather than being dropped as too rare to
         * watch.
         */
        BELOW_MIN_SAMPLE,

        /**
         * The windows differ, by less than the floor. The overwhelmingly common outcome and the one that
         * makes an unbudgeted findings stream survivable.
         */
        WITHIN_FLOOR,

        /**
         * The two sketches are laid out on different log grids, so no honest number exists. Reached
         * only by editing {@code hist_bins} on a project that already has persisted sketches: those
         * outlive the edit, and W1 across two grids would be a plausible number nothing downstream
         * would question. Handled here rather than by letting {@link MetricDistance} throw, because
         * a config edit is an event to survive: the sweep's next close writes a sketch on the new
         * grid and re-pins, and is not a reason to dead-letter a sweep.
         */
        GRID_MISMATCH
    }

    /**
     * One comparison's outcome, serialized directly into the evidence blob rather than recomputed
     * from it.
     *
     * <p>{@code w1Log} and {@code ratio} are both carried on purpose. The ratio is what a human
     * reads ("1.4x slower"); the raw log-space value is what the floor was set on. An eval forced
     * to re-derive one from the other is an eval that can disagree with the detector it is
     * measuring.
     *
     * @param w1Log signed W1 in log units; positive means the current window sits above its reference.
     * @param ratio {@code e^w1Log}: the multiplicative shift, above 1 for up and below 1 for down.
     * @param direction which way it moved; meaningful whether or not it fired.
     * @param floor the bar this comparison was actually held to, after {@link #effectiveFloor}. Carried
     *     rather than left to be recomputed because it is the answer to "why did that not fire": on a
     *     thin window it is not the number in the config, and a reader who assumed it was would conclude
     *     the detector was broken.
     * @param silence why it did not fire, or null when it did.
     */
    public record Decision(
            boolean fired,
            String measure,
            Reference reference,
            double w1Log,
            double ratio,
            Direction direction,
            long nRef,
            long nCur,
            double floor,
            @Nullable Silence silence) {

        /** Magnitude of the shift regardless of sign: what the floor is compared against. */
        public double magnitude() {
            return Math.abs(w1Log);
        }
    }

    /**
     * The bar this comparison is held to: the configured move, raised when the windows are too
     * thin to measure it reliably.
     *
     * <pre>{@code   floor = w1Floor * max(1, sqrt(windowTargetCount / nEff))}</pre>
     *
     * <p>Two windows drawn from the same distribution do not produce a W1 of zero; they produce
     * sampling noise that grows as the windows thin, falling off as {@code 1/sqrt(nEff)} with
     * {@code nEff} the harmonic mean of the two counts, not the arithmetic mean: a 500-sample
     * reference cannot rescue a 100-sample current window the way an arithmetic mean of 300 would
     * suggest. Holding the bar flat while windows thin takes the false-alarm rate from about 1% at
     * 500-against-500 to 20% at 500-against-100; scaling by {@code sqrt(windowTargetCount / nEff)}
     * holds it near 1% across that whole range, which is what lets a bucket close on a 24-hour
     * clock and still be judged honestly.
     *
     * <p>{@code max(1, ...)} means the floor only ever tightens: a bucket thicker than the target
     * could support a smaller bar, but then {@link MetricDriftConfig#w1Floor()} would stop meaning
     * "the smallest move we will tell you about" and start meaning "on a bucket of average
     * thickness". A thin window simply has to clear more, since it can't support that promise.
     *
     * <p>Deliberately does not read how spread out the bucket's traffic is. Noise scales with that
     * too, so holding one false-alarm rate across every call site would mean deriving the bar from
     * each bucket's own sigma, and the reportable move would then differ per bucket. A
     * sigma-derived bar also misreads a bimodal bucket (a fast cached path and a slow uncached one
     * produce a wide sigma that's structure, not noise) and inflates the bar exactly where a shift
     * matters most. Sigma is measured ({@link MetricSketch#stdDevLog()}) and shown on the tuning
     * surface, but never decides anything here.
     */
    public static double effectiveFloor(long nRef, long nCur, MetricDriftConfig config) {
        if (nRef <= 0 || nCur <= 0) return config.w1Floor();
        double nEff = 2.0 / (1.0 / nRef + 1.0 / nCur);
        return config.w1Floor() * Math.max(1.0, Math.sqrt(config.windowTargetCount() / nEff));
    }

    /**
     * The false-alarm rate a given move implies on traffic of a given spread: the number the
     * tuning surface shows beside the dial, so "tell me about 15% moves" is not a setting whose
     * consequence only shows up as noise a week later.
     *
     * <p>Inverts the measured noise law. The null W1 between two unchanged windows has a
     * {@code (1-rate)} quantile of {@code c(rate) * sigma * sqrt(2/nEff)}, with {@code c}
     * depending only on the rate (fitted across sigma from 0.30 to 1.10 and window mixes from
     * 500/500 to 100/100, it varied by +-1.3%). Given a bar, the rate is whatever {@code c} solves
     * {@code bar = c * sigma * sqrt(2/nEff)}.
     *
     * @param sigma the log-space spread of the traffic being watched, from {@link MetricSketch#stdDevLog()}
     * @return the implied rate, or empty when sigma is unmeasured: a caller must say "not enough
     *     traffic yet" rather than print a number it cannot stand behind
     */
    public static java.util.OptionalDouble impliedFalseAlarmRate(double bar, double sigma, long nEff) {
        // NaN is tested explicitly rather than left to fall out of a negated comparison: `sigma <= 0`
        // alone lets a NaN through (NaN fails every comparison), and this method's whole contract is
        // to return nothing rather than a number nobody can stand behind.
        if (Double.isNaN(sigma) || Double.isNaN(bar) || sigma <= 0 || bar <= 0 || nEff <= 0) {
            return java.util.OptionalDouble.empty();
        }
        double c = bar / (sigma * Math.sqrt(2.0 / nEff));
        return java.util.OptionalDouble.of(rateForCoefficient(c));
    }

    /**
     * The measured relationship between a false-alarm rate and the coefficient {@code c} that
     * produces it, read off forty thousand null comparisons per point at four different
     * (sigma, window-mix) combinations that agreed to within 1%:
     *
     * <pre>
     *   rate    c        rate     c
     *   20%     1.596    2%       2.458
     *   10%     1.880    1%       2.688
     *    5%     2.143    0.5%     2.900
     *                    0.2%     3.189
     * </pre>
     *
     * <p>Interpolated linearly in {@code log(rate)}, the axis these points fall on a straight
     * line in, and clamped to the span they cover: outside it the answer would be extrapolation
     * dressed as a measurement.
     */
    private static double rateForCoefficient(double c) {
        double[] rates = {0.20, 0.10, 0.05, 0.02, 0.01, 0.005, 0.002};
        double[] cs = {1.596, 1.880, 2.143, 2.458, 2.688, 2.900, 3.189};
        if (c <= cs[0]) return rates[0];
        for (int i = 1; i < cs.length; i++) {
            if (c <= cs[i]) {
                double t = (c - cs[i - 1]) / (cs[i] - cs[i - 1]);
                return Math.exp(Math.log(rates[i - 1]) + t * (Math.log(rates[i]) - Math.log(rates[i - 1])));
            }
        }
        return rates[rates.length - 1];
    }

    /**
     * Compare a bucket's just-closed window against one of its references.
     *
     * @param ref the pinned or previously-closed window, or null when the bucket has neither yet
     * @param cur the window that just closed
     * @return a {@link Decision} that always describes itself, firing or not
     */
    public static Decision decide(
            String measure,
            Reference reference,
            @Nullable MetricSketch ref,
            MetricSketch cur,
            MetricDriftConfig config) {
        if (ref == null) return silent(measure, reference, 0, cur.count(), Silence.NO_REFERENCE);
        if (!ref.gridId().equals(cur.gridId())) {
            return silent(measure, reference, ref.count(), cur.count(), Silence.GRID_MISMATCH);
        }
        // Checked on BOTH windows. A reference thinned by a quiet week is as unreliable a bar as a
        // current window nobody has filled yet, and comparing against it would report the sampling noise
        // of the quiet week as a regression in the busy one.
        if (ref.count() < config.minSample() || cur.count() < config.minSample()) {
            return silent(measure, reference, ref.count(), cur.count(), Silence.BELOW_MIN_SAMPLE);
        }

        double w1 = MetricDistance.signedW1(ref, cur);
        double ratio = MetricDistance.ratio(w1);
        // Sign is the direction, and a shift of exactly zero has no direction to report; call it UP so
        // the field is never null. Nothing reads it in that case: the floor has already silenced it.
        Direction direction = w1 < 0 ? Direction.DOWN : Direction.UP;
        double floor = effectiveFloor(ref.count(), cur.count(), config);
        if (Math.abs(w1) < floor) {
            return new Decision(
                    false,
                    measure,
                    reference,
                    w1,
                    ratio,
                    direction,
                    ref.count(),
                    cur.count(),
                    floor,
                    Silence.WITHIN_FLOOR);
        }
        return new Decision(true, measure, reference, w1, ratio, direction, ref.count(), cur.count(), floor, null);
    }

    private static Decision silent(String measure, Reference reference, long nRef, long nCur, Silence silence) {
        return new Decision(false, measure, reference, 0.0, 1.0, Direction.UP, nRef, nCur, 0.0, silence);
    }
}
