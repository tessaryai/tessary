// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.CURVE_WEEKS;
import static ai.tessary.endusers.judge.JudgeConstants.DAYS_PER_WEEK;

import ai.tessary.endusers.judge.Cells.Match;
import ai.tessary.endusers.judge.Cells.Pool;
import ai.tessary.endusers.judge.Cells.Pools;
import ai.tessary.endusers.judge.Trials.Arm;
import ai.tessary.endusers.judge.Trials.Trial;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.IssueEstimate.LineageEstimate;
import ai.tessary.endusers.judge.model.IssueResult.CurvePoint;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.StateResult;
import ai.tessary.endusers.judge.model.StateResult.IdState;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.Verdict;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The retention curve "hit against not hit" of spec 8.9 (reading 26), and the hit IDs the drawer lists. The treated are
 * the compared hit IDs, each from its hit day, in the pool of its first matched exposed trial; the controls are the rows
 * of those pools, up to about 150 per pool, weighted to the treated mix, each at risk until its own hit. An ID's event is
 * its last series day with mass {@code p_stop}. A step where no mass is at risk keeps the level of the step before, so
 * the curve never rises.
 */
public final class RetentionCurve {

    private static final int CONTROL_ROWS_PER_POOL = 150; // [ref]
    private static final double BAND_Z = 1.96; // [ref] Greenwood interval

    private static final Set<Verdict> CLAIMS = Set.of(Verdict.EVIDENCE_OF_LOSS, Verdict.FEW_ACCOUNTS);

    private RetentionCurve() {}

    private record Treated(int id, int hitDay, Pool pool) {}

    /**
     * The weekly steps 1 to 8 of a shown issue, as many as whole weeks have passed since its first hit.
     *
     * @param trials the trials the estimate was fitted on
     * @param estimate the estimate the verdict reads
     * @param idChangeWeeks the weeks the estimate leaves out for an ID change not solved by aliases
     */
    public static List<CurvePoint> curve(
            List<Trial> trials,
            LineageEstimate estimate,
            Set<Integer> idChangeWeeks,
            LineageHits hits,
            Series series,
            StopOdds odds,
            int asOfDay) {
        Pools pools = new Pools(trials);
        Map<Integer, Trial> firstTrial = new LinkedHashMap<>();
        Map<Integer, Pool> poolOf = new LinkedHashMap<>();
        for (Trial t : trials) {
            if (t.arm() != Arm.EXPOSED || !estimate.estimateIds().contains(t.id()) || idChangeWeeks.contains(t.week()))
                continue;
            Match m = pools.match(t.week(), t.cell());
            if (m == null) continue;
            Trial prev = firstTrial.get(t.id());
            if (prev == null || t.day() < prev.day()) {
                firstTrial.put(t.id(), t);
                poolOf.put(t.id(), m.pool());
            }
        }
        List<Treated> treated = new ArrayList<>();
        Map<Pool, Integer> perPool = new LinkedHashMap<>();
        int firstHit = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Pool> e : poolOf.entrySet()) {
            int hitDay = hits.hitDays().get(e.getKey()).getFirst();
            treated.add(new Treated(e.getKey(), hitDay, e.getValue()));
            perPool.merge(e.getValue(), 1, Integer::sum);
            firstHit = Math.min(firstHit, hitDay);
        }
        int steps = treated.isEmpty() ? 0 : Math.min(CURVE_WEEKS, (asOfDay - firstHit) / DAYS_PER_WEEK);

        double hitLevel = 1;
        double similarLevel = 1;
        double variance = 0;
        List<CurvePoint> out = new ArrayList<>();
        for (int t = 1; t <= steps; t++) {
            int from = DAYS_PER_WEEK * (t - 1);
            int to = DAYS_PER_WEEK * t;
            double event = 0;
            double atRisk = 0;
            for (Treated x : treated) {
                if (asOfDay - x.hitDay() < to) continue;
                double p = pStop(odds, x.id());
                int e = Math.max(1, lastDay(series, x.id()) - x.hitDay());
                atRisk += (1 - p) + (e > from ? p : 0);
                if (e > from && e <= to) event += p;
            }
            double num = 0;
            double den = 0;
            int controlRows = 0;
            for (Map.Entry<Pool, Integer> pe : perPool.entrySet()) {
                double controlEvent = 0;
                double controlAtRisk = 0;
                for (Trial c : thinned(pe.getKey().rows())) {
                    List<Integer> own = hits.hitDays().get(c.id());
                    if (asOfDay - c.day() < to || (own != null && own.getFirst() <= c.day() + to)) continue;
                    double p = pStop(odds, c.id());
                    int e = Math.max(1, lastDay(series, c.id()) - c.day());
                    controlRows++;
                    controlAtRisk += c.weight() * ((1 - p) + (e > from ? p : 0));
                    if (e > from && e <= to) controlEvent += c.weight() * p;
                }
                if (controlAtRisk > 0) {
                    num += pe.getValue() * controlEvent / controlAtRisk;
                    den += pe.getValue();
                }
            }
            if (atRisk > 0) {
                double h = event / atRisk;
                hitLevel *= 1 - h;
                variance += h / (Math.max(atRisk, 1e-9) * Math.max(1 - h, 1e-9));
            }
            if (den > 0) similarLevel *= 1 - num / den;
            double half = BAND_Z * hitLevel * Math.sqrt(variance);
            out.add(new CurvePoint(
                    t,
                    hitLevel,
                    similarLevel,
                    Math.max(0, hitLevel - half),
                    Math.min(1, hitLevel + half),
                    (int) Math.round(atRisk),
                    controlRows));
        }
        return out;
    }

    /**
     * Covered hit IDs whose stage is silent or stopped, ascending; empty for every verdict but evidence of loss and few
     * accounts.
     */
    public static List<Integer> silentHitIds(Verdict verdict, LineageHits hits, StateResult states) {
        if (!CLAIMS.contains(verdict)) return List.of();
        List<Integer> out = new ArrayList<>();
        for (int u : hits.hitDays().keySet()) {
            IdState s = states.byId().get(u);
            if (s != null && (s.stage() == Stage.SILENT || s.stage() == Stage.STOPPED)) out.add(u);
        }
        out.sort(null);
        return out;
    }

    /** Every {@code n / 150}-th row from the first, as the reference. */
    private static List<Trial> thinned(List<Trial> rows) {
        if (rows.size() <= CONTROL_ROWS_PER_POOL) return rows;
        int step = Math.max(1, rows.size() / CONTROL_ROWS_PER_POOL);
        List<Trial> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += step) out.add(rows.get(i));
        return out;
    }

    private static double pStop(StopOdds odds, int id) {
        return odds.byId().get(id).pStop();
    }

    private static int lastDay(Series series, int id) {
        return series.byId().get(id).days().getLast();
    }
}
