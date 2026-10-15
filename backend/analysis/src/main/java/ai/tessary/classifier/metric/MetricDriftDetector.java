// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import org.jspecify.annotations.Nullable;

/**
 * Compare-and-decide for metric drift, pure so "would this have fired" needs no schema. The decision is effect size,
 * {@code |signedW1| >= floor}, never significance, which inflates with sample size. The floor only ever rises on thin
 * windows ({@link #effectiveFloor}). Both directions fire at the same bar: a regression can read as a win. Silence
 * always carries a {@link Silence} reason.
 */
public final class MetricDriftDetector {

    private MetricDriftDetector() {}

    /**
     * Both run on every close, since each is blind in one direction alone. {@link #wire()} words end every
     * {@code cause_key} and are shown to users.
     */
    public enum Reference {
        /**
         * The rolling control of {@link MetricControl}: catches sudden breaks. The wire word stays {@code previous}: it
         * ends every {@code cause_key} ever written, and persisted strings are never renamed.
         */
        PREVIOUS,

        /** Moved only by a human absorb. Catches creep slow enough that the control absorbs it. */
        PINNED;

        public String wire() {
            return this == PREVIOUS ? "previous" : "pinned";
        }
    }

    /** Measure-neutral: the sentence a human reads is built where the measure is known. */
    public enum Direction {
        UP,
        DOWN;

        public String wire() {
            return this == UP ? "up" : "down";
        }
    }

    public enum Silence {
        /** Ordinary on a young bucket; the first armed close establishes the reference. */
        NO_REFERENCE,

        /** A bucket here waits on a slower clock, it is not skipped. */
        BELOW_MIN_SAMPLE,

        /** The common outcome, and what makes an unbudgeted findings stream survivable. */
        WITHIN_FLOOR,

        /**
         * Different log grids, reached by editing {@code hist_bins} under persisted sketches. Silence rather than
         * letting {@link MetricDistance} throw: the next close re-pins on the new grid.
         */
        GRID_MISMATCH
    }

    /**
     * Serialized straight into the evidence blob. {@code w1Log} is what the floor was set on and {@code ratio} what a
     * human reads; both are carried so an eval cannot disagree with the detector. {@code floor} is the bar actually
     * held, which on a thin window is not the config value.
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
            @Nullable Silence silence) {}

    /**
     * {@code floor = w1Floor * max(1, sqrt(windowTargetCount / nEff))}, {@code nEff} the harmonic mean of the counts. A
     * flat bar takes false alarms from ~1% at 500/500 to 20% at 500/100; scaling holds ~1%. {@code max(1, ...)} keeps
     * w1Floor a promise about the smallest reported move. The bucket's spread deliberately does not move the bar.
     */
    public static double effectiveFloor(long nRef, long nCur, MetricDriftConfig config) {
        if (nRef <= 0 || nCur <= 0) return config.w1Floor();
        double nEff = 2.0 / (1.0 / nRef + 1.0 / nCur);
        return config.w1Floor() * Math.max(1.0, Math.sqrt(config.windowTargetCount() / nEff));
    }

    /**
     * Inverts the measured noise law {@code bar = c(rate) * sigma * sqrt(2/nEff)}, so the tuning dial shows its
     * consequence up front. Empty when sigma is unmeasured.
     */
    public static java.util.OptionalDouble impliedFalseAlarmRate(double bar, double sigma, long nEff) {
        // NaN fails every comparison, so `sigma <= 0` alone would let it through.
        if (Double.isNaN(sigma) || Double.isNaN(bar) || sigma <= 0 || bar <= 0 || nEff <= 0) {
            return java.util.OptionalDouble.empty();
        }
        double c = bar / (sigma * Math.sqrt(2.0 / nEff));
        return java.util.OptionalDouble.of(rateForCoefficient(c));
    }

    /**
     * The rate-to-c pairs below were measured from forty thousand null comparisons per point across four (sigma,
     * window-mix) settings agreeing within 1%. Linear in {@code log(rate)}, clamped to the measured span.
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

    /** {@code ref} is null when the bucket has no reference yet. The {@link Decision} always describes itself. */
    public static Decision decide(
            String measure,
            Reference reference,
            @Nullable MetricReading ref,
            MetricReading cur,
            MetricDriftConfig config) {
        if (ref == null) return silent(measure, reference, 0, cur.count(), Silence.NO_REFERENCE);
        if (!ref.gridId().equals(cur.gridId())) {
            return silent(measure, reference, ref.count(), cur.count(), Silence.GRID_MISMATCH);
        }
        // Both windows: a reference thinned by a quiet week would report its noise as a regression.
        if (ref.count() < config.minSample() || cur.count() < config.minSample()) {
            return silent(measure, reference, ref.count(), cur.count(), Silence.BELOW_MIN_SAMPLE);
        }

        double w1 = MetricDistance.signedW1(ref, cur);
        double ratio = MetricDistance.ratio(w1);
        // UP so the field is never null; the floor has already silenced a zero shift.
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
