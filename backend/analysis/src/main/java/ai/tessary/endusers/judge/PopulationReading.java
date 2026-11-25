// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.CONTROL_FLOOR;
import static ai.tessary.endusers.judge.JudgeConstants.POPULATION_BEFORE_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.POPULATION_HIGH_SES;

import ai.tessary.endusers.judge.Cells.Cell;
import ai.tessary.endusers.judge.Trials.Trial;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The second contrast of spec 8.6 (reading 22): the trials of all users of the reach, exposed or not, in
 * [{@code first_ev}, min({@code D - 1}, {@code last_ev + H})] against the trials of the 28 days before {@code first_ev},
 * in the cells of 8.4 without the week and without "other issue", each trial with weight 1. A real cause adds stops, so
 * the total rises; a detector that only marks IDs about to stop moves stops from "not hit" to "hit", and the total does
 * not rise.
 */
public final class PopulationReading {

    private PopulationReading() {}

    /**
     * @param pop extra stops among all users of the reach after the start
     * @param se its standard error, with the account sum
     * @param popHigh {@code pop + 2 se}
     * @param before trials in the 28 days before the first evidence
     * @param after trials after the start that found a pool
     */
    public record Result(double pop, double se, double popHigh, int before, int after) {}

    /** A pool key: 0 the cell without week and other issue; 1 no account; 2 age, earlier and dose; 3 age and dose. */
    private record Key(
            int level,
            int age,
            int earlier,
            int run,
            int dose,
            @Nullable Integer account) {}

    /** The residual sum of a cluster, or of an ID with no cluster: the two never share a key. */
    private record AccountKey(boolean cluster, int value) {}

    /**
     * @param trials every trial of the issue as {@link Trials#build} gives them, the account cell dropped when the
     *     estimate dropped it
     * @param accountOf key index to its account cluster, for the residual sum
     * @return null for an always-present finding, with fewer than 5 trials before, or with none after
     */
    public static @Nullable Result read(
            LineageHits lineage, List<Trial> trials, int h, int asOfDay, Map<Integer, Integer> accountOf) {
        Integer fe = lineage.firstEvidenceDay();
        Integer le = lineage.lastEvidenceDay();
        if (lineage.alwaysPresent() || fe == null || le == null) return null;
        int end = Math.min(asOfDay - 1, le + h);
        List<Trial> before = new ArrayList<>();
        List<Trial> after = new ArrayList<>();
        for (Trial t : trials) {
            if (t.day() >= fe - POPULATION_BEFORE_DAYS && t.day() < fe) before.add(t);
            else if (t.day() >= fe && t.day() <= end) after.add(t);
        }
        if (before.size() < CONTROL_FLOOR || after.isEmpty()) return null;

        Map<Key, List<Trial>> pools = new HashMap<>();
        for (Trial t : before) {
            for (Key k : keys(t.cell()))
                pools.computeIfAbsent(k, x -> new ArrayList<>()).add(t);
        }
        Map<Key, List<Trial>> groups = new LinkedHashMap<>();
        for (Trial t : after) {
            for (Key k : keys(t.cell())) {
                if (pools.getOrDefault(k, List.of()).size() >= CONTROL_FLOOR) {
                    groups.computeIfAbsent(k, x -> new ArrayList<>()).add(t);
                    break;
                }
            }
        }
        Map<AccountKey, Double> residual = new LinkedHashMap<>();
        double vg = 0;
        int matched = 0;
        for (Map.Entry<Key, List<Trial>> g : groups.entrySet()) {
            List<Trial> pool = pools.get(g.getKey());
            double m = pool.size();
            double hc = 0;
            for (Trial t : pool) hc += t.yLo();
            hc /= m;
            double n = g.getValue().size();
            vg += n * n * Math.max(hc * (1 - hc), 0.25 / m) / m;
            for (Trial t : g.getValue()) {
                Integer cluster = accountOf.get(t.id());
                AccountKey a = cluster != null ? new AccountKey(true, cluster) : new AccountKey(false, t.id());
                residual.merge(a, t.yLo() - hc, Double::sum);
                matched++;
            }
        }
        double pop = 0;
        double squares = 0;
        for (double r : residual.values()) {
            pop += r;
            squares += r * r;
        }
        double se = Math.sqrt(squares + vg);
        return new Result(pop, se, pop + POPULATION_HIGH_SES * se, before.size(), matched);
    }

    private static List<Key> keys(Cell c) {
        return List.of(
                new Key(0, c.age(), c.earlier(), c.run(), c.dose(), c.account()),
                new Key(1, c.age(), c.earlier(), c.run(), c.dose(), null),
                new Key(2, c.age(), c.earlier(), -1, c.dose(), null),
                new Key(3, c.age(), -1, -1, c.dose(), null));
    }
}
