// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The Kaplan-Meier curve of the reference {@code logic.py} and the nominal stop odds read from it (spec section 5). */
public final class Survival {

    private static final int PLATEAU_MIN_AT_RISK = 5;
    private static final double SURVIVAL_FLOOR = 1e-9;

    private Survival() {}

    /** One step for each time with at least one event; a censored time makes no step. */
    public static Curve km(List<Observation> observations) {
        List<Observation> obs = new ArrayList<>(observations);
        obs.sort(Comparator.comparingDouble(Observation::time));
        int n = obs.size();
        double s = 1.0;
        List<Double> times = new ArrayList<>();
        List<Double> survival = new ArrayList<>();
        List<Integer> atRisk = new ArrayList<>();
        int i = 0;
        while (i < n) {
            double t = obs.get(i).time();
            int d = 0;
            int j = i;
            // Sorted ascending, so "<= t" is the run of times equal to t.
            while (j < n && obs.get(j).time() <= t) {
                if (obs.get(j).event()) {
                    d++;
                }
                j++;
            }
            if (d > 0) {
                s *= 1.0 - (double) d / (n - i);
                times.add(t);
                survival.add(s);
                atRisk.add(n - i);
            }
            i = j;
        }
        return new Curve(List.copyOf(times), List.copyOf(survival), List.copyOf(atRisk));
    }

    /** {@code S(x)}: the value of the last step at or before {@code x}, 1 before the first step. */
    public static double surv(Curve curve, double x) {
        int i = bisectRight(curve.times(), x) - 1;
        return i < 0 ? 1.0 : curve.survival().get(i);
    }

    /**
     * {@code x_T}, the last event time with at least five observations at risk, and {@code S_inf = S(x_T)}. With fewer
     * at risk everywhere the first event is the plateau. The curve must have a step.
     */
    public static Plateau plateau(Curve curve) {
        int j = curve.times().size() - 1;
        while (j > 0 && curve.atRisk().get(j) < PLATEAU_MIN_AT_RISK) {
            j--;
        }
        return new Plateau(curve.times().get(j), curve.survival().get(j));
    }

    /** {@code p_nominal = min(1, S_inf / S(min(x, x_T)))}; 0 for a curve with no step. */
    public static double pNominal(Curve curve, double x) {
        if (curve.times().isEmpty()) {
            return 0.0;
        }
        Plateau p = plateau(curve);
        return Math.min(1.0, p.survival() / Math.max(surv(curve, Math.min(x, p.time())), SURVIVAL_FLOOR));
    }

    private static int bisectRight(List<Double> sorted, double x) {
        int lo = 0;
        int hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (x < sorted.get(mid)) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    public record Observation(double time, boolean event) {}

    /** The steps of a curve: event times ascending, {@code S} after each, and the observations at risk at each. */
    public record Curve(List<Double> times, List<Double> survival, List<Integer> atRisk) {}

    public record Plateau(double time, double survival) {}
}
