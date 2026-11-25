// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import java.util.Arrays;

/**
 * Helpers of the reference {@code logic.py}: Wilson limits, the empirical quantile and the circular pair count. Each
 * keeps the reference operation order, so a value at a cut falls on the same side as in the reference.
 */
public final class Stats {

    private static final int MINUTES_PER_DAY = 1440;
    private static final int PAIR_WIDE_MINUTES = 60;
    private static final int PAIR_NARROW_MINUTES = 2;

    private Stats() {}

    public static double wilsonLow(int k, int n, double z) {
        if (n <= 0) {
            return 0.0;
        }
        return Math.max(0.0, (wilsonCentre(k, n, z) - wilsonRadius(k, n, z)) / wilsonScale(n, z));
    }

    public static double wilsonUp(int k, int n, double z) {
        if (n <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (wilsonCentre(k, n, z) + wilsonRadius(k, n, z)) / wilsonScale(n, z));
    }

    private static double wilsonScale(int n, double z) {
        return 1 + z * z / n;
    }

    private static double wilsonCentre(int k, int n, double z) {
        return (double) k / n + z * z / (2.0 * n);
    }

    private static double wilsonRadius(int k, int n, double z) {
        double p = (double) k / n;
        return z * Math.sqrt(Math.max(0.0, p * (1 - p) / n + z * z / (4.0 * n * n)));
    }

    /** The empirical quantile with linear interpolation between ranks; the input array is not changed. */
    public static double quantile(double[] xs, double p) {
        if (xs.length == 0) {
            throw new IllegalArgumentException("quantile of no values");
        }
        double[] sorted = xs.clone();
        Arrays.sort(sorted);
        double i = p * (sorted.length - 1);
        int lo = (int) Math.floor(i);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo);
    }

    public static double median(double[] xs) {
        return quantile(xs, 0.5);
    }

    /** Unordered pairs of minutes of day within 60 and within 2 minutes of each other, measured round the clock. */
    public static CircPairs circPairs(int[] minutesOfDay) {
        int n = minutesOfDay.length;
        if (n < 2) {
            return new CircPairs(0, 0);
        }
        int[] ms = minutesOfDay.clone();
        Arrays.sort(ms);
        double[] ext = new double[3 * n];
        for (int i = 0; i < n; i++) {
            ext[i] = ms[i] - MINUTES_PER_DAY;
            ext[n + i] = ms[i];
            ext[2 * n + i] = ms[i] + MINUTES_PER_DAY;
        }
        int t60 = 0;
        int t2 = 0;
        for (int x : ms) {
            t60 += bisectRight(ext, x + PAIR_WIDE_MINUTES) - bisectLeft(ext, x - PAIR_WIDE_MINUTES) - 1;
            t2 += bisectRight(ext, x + PAIR_NARROW_MINUTES) - bisectLeft(ext, x - PAIR_NARROW_MINUTES) - 1;
        }
        return new CircPairs(t60 / 2, t2 / 2);
    }

    /** Python's {@code bisect_left}: the first index whose value is not below {@code x}. */
    static int bisectLeft(double[] sorted, double x) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < x) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Python's {@code bisect_right}: the first index whose value is above {@code x}. */
    static int bisectRight(double[] sorted, double x) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (x < sorted[mid]) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    public record CircPairs(int within60, int within2) {}
}
