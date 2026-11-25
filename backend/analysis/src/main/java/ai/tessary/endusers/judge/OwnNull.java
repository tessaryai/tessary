// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.NULL_DRAWS;
import static ai.tessary.endusers.judge.JudgeConstants.NULL_DRAWS_LARGE;
import static ai.tessary.endusers.judge.JudgeConstants.NULL_DRAWS_LARGE_ABOVE_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.NULL_DRAWS_MIN;

import ai.tessary.endusers.judge.Cells.Match;
import ai.tessary.endusers.judge.Cells.Pool;
import ai.tessary.endusers.judge.Cells.Pools;
import ai.tessary.endusers.judge.Trials.Arm;
import ai.tessary.endusers.judge.Trials.Trial;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import org.jspecify.annotations.Nullable;

/**
 * The issue's own null of spec 8.5 and reading 21: per week as many false first hits as the week has real first hits,
 * drawn by weight from the unexposed trials of that week (the days before a real hit included), one per ID. A false hit
 * opens a window of {@code H} days over that ID's unexposed trials, which are taken off their pools and compared with
 * the rest. Weeks run ascending and candidates by key index and day; the reference reads them in file order.
 */
public final class OwnNull {

    private static final double EMPTY_POOL = 1e-9;
    private static final int TRIES_PER_HIT = 6;
    private static final int TRIES_EXTRA = 20;

    private OwnNull() {}

    /**
     * @param b0 the mean of the draws' rates
     * @param s0 their standard deviation
     * @param nullMax the largest
     * @param draws the draws that placed a false hit; under 5, the three values are 0
     */
    public record Result(double b0, double s0, double nullMax, int draws) {}

    /** @param rate null when no false hit fell in a pool */
    record Draw(@Nullable Double rate, List<Trial> falseArm) {}

    /**
     * @param candidates the unexposed trials of each week with real first hits
     * @param cumulative their running weight
     * @param byId the unexposed trials of each ID, by day
     */
    record Frame(
            Map<Integer, List<Trial>> candidates, Map<Integer, double[]> cumulative, Map<Integer, List<Trial>> byId) {}

    /** {@code crc32("<issue>|<draw>")}, the seed of the reference. */
    public static long seed(String issue, int draw) {
        CRC32 crc = new CRC32();
        crc.update((issue + "|" + draw).getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    public static int drawCount(int estimatedIds) {
        return estimatedIds > NULL_DRAWS_LARGE_ABOVE_IDS ? NULL_DRAWS_LARGE : NULL_DRAWS;
    }

    /**
     * @param realFirstHits per week, the hit IDs of the estimate first hit in it, ID-change weeks left out
     * @param trials the trials of the issue; only unexposed ones are read
     */
    public static Result run(
            String issue,
            int estimatedIds,
            Map<Integer, Integer> realFirstHits,
            List<Trial> trials,
            Pools pools,
            int h) {
        Map<Integer, Integer> weeks = new TreeMap<>(realFirstHits);
        Frame frame = frame(trials, weeks.keySet());
        List<Double> rates = new ArrayList<>();
        for (int d = 0; d < drawCount(estimatedIds); d++) {
            Double rate =
                    draw(new PyRandom(seed(issue, d)), weeks, frame, pools, h).rate();
            if (rate != null) rates.add(rate);
        }
        if (rates.size() < NULL_DRAWS_MIN) return new Result(0, 0, 0, rates.size());
        double mean = 0;
        for (double r : rates) mean += r;
        mean /= rates.size();
        double ss = 0;
        double max = Double.NEGATIVE_INFINITY;
        for (double r : rates) {
            ss += (r - mean) * (r - mean);
            max = Math.max(max, r);
        }
        return new Result(mean, Math.sqrt(ss / (rates.size() - 1)), max, rates.size());
    }

    static Frame frame(List<Trial> trials, Set<Integer> weeks) {
        Map<Integer, List<Trial>> candidates = new TreeMap<>();
        Map<Integer, List<Trial>> byId = new TreeMap<>();
        for (Trial t : trials) {
            if (t.arm() != Arm.UNEXPOSED) continue;
            byId.computeIfAbsent(t.id(), u -> new ArrayList<>()).add(t);
            if (weeks.contains(t.week()))
                candidates.computeIfAbsent(t.week(), w -> new ArrayList<>()).add(t);
        }
        Map<Integer, double[]> cumulative = new TreeMap<>();
        candidates.forEach((w, rows) -> {
            double[] c = new double[rows.size()];
            double acc = 0;
            for (int i = 0; i < c.length; i++) {
                acc += rows.get(i).weight();
                c[i] = acc;
            }
            cumulative.put(w, c);
        });
        return new Frame(candidates, cumulative, byId);
    }

    static Draw draw(PyRandom rng, Map<Integer, Integer> realFirstHits, Frame frame, Pools pools, int h) {
        Map<Pool, Taken> taken = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        Set<Integer> falseHitIds = new HashSet<>();
        List<Trial> falseArm = new ArrayList<>();
        for (Map.Entry<Integer, Integer> week : realFirstHits.entrySet()) {
            List<Trial> rows = frame.candidates().get(week.getKey());
            if (rows == null) continue;
            double[] c = frame.cumulative().get(week.getKey());
            int wanted = week.getValue();
            int got = 0;
            int tries = 0;
            while (got < wanted && tries < wanted * TRIES_PER_HIT + TRIES_EXTRA) {
                tries++;
                int j = Math.min(firstAtOrAbove(c, rng.random() * c[c.length - 1]), rows.size() - 1);
                Trial pick = rows.get(j);
                if (!used.add(pick.id())) continue;
                got++;
                for (Trial x : frame.byId().get(pick.id())) {
                    if (x.day() < pick.day() || x.day() > pick.day() + h) continue;
                    Match match = pools.match(x.week(), x.cell());
                    if (match == null) continue;
                    taken.computeIfAbsent(match.pool(), p -> new Taken()).add(x);
                    falseHitIds.add(x.id());
                    falseArm.add(x);
                }
            }
        }
        if (falseHitIds.isEmpty()) return new Draw(null, falseArm);
        double total = 0;
        for (Map.Entry<Pool, Taken> e : taken.entrySet()) {
            Pool pool = e.getKey();
            Taken t = e.getValue();
            double rest = pool.weight() - t.weight;
            if (rest <= EMPTY_POOL) continue;
            total += t.sumLo - t.n * (pool.sumLo() - t.weightedLo) / rest;
        }
        return new Draw(total / falseHitIds.size(), falseArm);
    }

    /** Python's {@code bisect_left}: the first index whose running weight is at or above {@code x}. */
    private static int firstAtOrAbove(double[] c, double x) {
        int lo = 0;
        int hi = c.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (c[mid] < x) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** The false-hit trials taken off one pool. */
    private static final class Taken {
        int n;
        double sumLo;
        double weight;
        double weightedLo;

        void add(Trial x) {
            n++;
            sumLo += x.yLo();
            weight += x.weight();
            weightedLo += x.weight() * x.yLo();
        }
    }
}
