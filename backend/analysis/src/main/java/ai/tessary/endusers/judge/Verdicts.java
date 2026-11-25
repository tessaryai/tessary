// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.CONTRADICTED_MIN_TRIALS;
import static ai.tessary.endusers.judge.JudgeConstants.CONTRADICTED_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.FEW_ACCOUNTS;
import static ai.tessary.endusers.judge.JudgeConstants.F_MAX;
import static ai.tessary.endusers.judge.JudgeConstants.HISTORY_FLOOR_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.ID_HISTORY_FLOOR_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.LEAST_CLAIM_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.LEAST_CLAIM_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.MATCHED_FLOOR;
import static ai.tessary.endusers.judge.JudgeConstants.MATCHED_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.NOT_COMPARABLE_SES;
import static ai.tessary.endusers.judge.JudgeConstants.NO_LOSS_MATCHED;
import static ai.tessary.endusers.judge.JudgeConstants.NO_LOSS_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.NO_LOSS_VISIBLE;

import ai.tessary.endusers.judge.Estimate.Numbers;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.IssueEstimate.LineageEstimate;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.Stratum;
import ai.tessary.endusers.model.TracerVerdict;
import ai.tessary.endusers.model.Verdict;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The verdict of spec 8.7 for one issue, first rule that applies, with the level 2 guards "hits not complete", "history
 * too short" and "cannot tell yet" after "page refused". There is no "symptom, not cause": the product has no
 * declaration input. A verdict that makes no claim shows a lower limit of 0; a verdict that is not shown has no numbers,
 * except the upper limit that the words of "cannot measure yet" give.
 */
public final class Verdicts {

    private Verdicts() {}

    /**
     * @param groupBanner group-hint and aggregate IDs hold 5% or more of the ID-days (spec 3.5)
     * @param horizonDays how long facts are kept; null when there is no limit
     * @param idHistoryDays {@code span}: days from the first trace with a user ID to {@code D}
     * @param f the return cycle {@code F}
     * @param metricShift the issue shifts a measure for all traffic and has no hit user IDs
     * @param estimate null when no hit ID can be compared
     * @param ageHit see {@link #ageHit}
     * @param tVis see {@link #tVis}
     * @param clustersExist the IDs name account clusters
     * @param pop the second contrast of the estimate the verdict reads; null when not read
     */
    public record Input(
            PageState page,
            boolean groupBanner,
            Guards guards,
            @Nullable Integer horizonDays,
            int idHistoryDays,
            int f,
            boolean metricShift,
            LineageHits hits,
            @Nullable LineageEstimate estimate,
            double ageHit,
            int tVis,
            boolean clustersExist,
            PopulationReading.@Nullable Result pop,
            TracerVerdict tracer) {}

    /**
     * Numbers are null where the verdict shows none.
     *
     * @param compared {@code n_m}; null when the estimate was not reached, 0 when no hit ID can be compared
     * @param upper the most hit IDs lost if every hit ID silent today stays away; null when the estimate was not
     *     reached
     * @param visible {@code vis}: the share of the possible loss that can be seen today
     * @param rankLost the users lost the rank reads: the shown number, else the estimate's for a hidden verdict;
     *     null when the estimate was not reached
     * @param used the estimate the numbers come from: with few accounts, the rerun without the account cell
     */
    public record Decision(
            Verdict verdict,
            boolean shown,
            @Nullable Integer compared,
            @Nullable Double lost,
            @Nullable Double low,
            @Nullable Double high,
            @Nullable Double upper,
            double z,
            double visible,
            boolean contradicted,
            @Nullable Double pop,
            @Nullable Double popHigh,
            @Nullable Double rankLost,
            @Nullable LineageEstimate used) {}

    private static final Set<PageState> USABLE = Set.of(PageState.OK, PageState.PARTIAL_COVERAGE);

    public static Decision decide(Input in) {
        LineageEstimate first = in.estimate();
        if (first == null) return decide(in, null);
        LineageEstimate e = used(in, first);
        double seTot = Estimate.seTot(e.se(), e.s0());
        double z = Estimate.z(e.nM(), first.accEff());
        double rateLo = e.rateLo() - e.b0();
        double rateHi = e.rateHi() - e.b0();
        int nEst = first.nEst();
        return decide(
                in,
                new Numbers(
                        seTot,
                        z,
                        rateLo,
                        rateHi,
                        Math.max(0, rateLo) * nEst,
                        Math.max(0, rateLo - z * seTot) * nEst,
                        Math.max(0, rateHi + z * seTot) * nEst));
    }

    /** The rules over given numbers; {@code n} is null exactly when {@code in.estimate()} is. */
    static Decision decide(Input in, @Nullable Numbers n) {
        if (!USABLE.contains(in.page())) return blank(Verdict.PAGE_REFUSED);
        if (in.metricShift()) return blank(Verdict.NOT_APPLICABLE);
        Guards g = in.guards();
        LineageHits hits = in.hits();
        if (g.hitsNotComplete() && !hits.complete()) return blank(Verdict.HITS_NOT_COMPLETE);
        Integer horizon = in.horizonDays();
        boolean shortHorizon = horizon != null && horizon < HISTORY_FLOOR_DAYS;
        if (g.historyTooShort() && (shortHorizon || in.idHistoryDays() < ID_HISTORY_FLOOR_DAYS)) {
            return blank(Verdict.HISTORY_TOO_SHORT);
        }
        if (g.cannotTellYet() && in.f() >= F_MAX) return blank(Verdict.CANNOT_TELL_YET);
        LineageEstimate first = in.estimate();
        if (first == null || n == null) {
            return new Decision(
                    Verdict.TOO_FEW_IDS, false, 0, null, null, null, null, 0, 0, false, null, null, null, null);
        }

        LineageEstimate e = used(in, first);
        int nM = e.nM();
        int nEst = first.nEst();
        double vis = e.sumHi() > 0 ? e.sumLo() / e.sumHi() : 0;
        PopulationReading.Result pop = in.pop();
        boolean contradicted =
                pop != null && pop.before() >= CONTRADICTED_MIN_TRIALS && pop.popHigh() < CONTRADICTED_SHARE * n.lost();
        Out out = new Out(e, n, vis, contradicted, pop);

        if (in.ageHit() < in.tVis()) return out.hidden(Verdict.CANNOT_MEASURE_YET);
        if (nM < MATCHED_FLOOR || (double) nM / Math.max(1, nEst) < MATCHED_SHARE) {
            return out.hidden(Verdict.TOO_FEW_IDS);
        }
        if (e.controlIds() < nM) {
            if (pop == null) return out.hidden(Verdict.REACHES_NEARLY_ALL);
            return out.shown(Verdict.REACHES_NEARLY_ALL, Math.max(0, pop.pop()), 0, Math.max(0, pop.popHigh()));
        }
        if (n.rateLo() <= -NOT_COMPARABLE_SES * n.seTot()) return out.hidden(Verdict.NOT_COMPARABLE);
        if (hits.alwaysPresent()) return out.shown(Verdict.ALWAYS_PRESENT, n.lost(), 0, n.high());
        if (fewAccounts(in, first)) return out.shown(Verdict.FEW_ACCOUNTS, n.lost(), 0, n.high());
        boolean claim = n.low() > 0
                && n.rateLo() > e.nullMax() - e.b0()
                && n.lost() >= Math.max(LEAST_CLAIM_IDS, LEAST_CLAIM_SHARE * nEst)
                && in.tracer() != TracerVerdict.SOME_IDS_REPLACED;
        if (claim && !contradicted) return out.shown(Verdict.EVIDENCE_OF_LOSS, n.lost(), n.low(), n.high());
        if (claim) return out.shown(Verdict.MOVED_NOT_ADDED, n.lost(), 0, n.high());
        if (n.high() <= NO_LOSS_SHARE * nEst && nM >= NO_LOSS_MATCHED && vis >= NO_LOSS_VISIBLE && !in.groupBanner()) {
            return out.shown(Verdict.NO_MEASURABLE_LOSS, n.lost(), n.low(), n.high());
        }
        return out.shown(Verdict.NOT_ENOUGH_EVIDENCE, n.lost(), 0, n.high());
    }

    /** Median days from the first hit to {@code D} over the covered hit IDs; 0 with none. */
    public static double ageHit(LineageHits hits, Set<Integer> covered, int asOfDay) {
        double[] ages = hits.hitDays().entrySet().stream()
                .filter(x -> covered.contains(x.getKey()))
                .mapToDouble(x -> asOfDay - x.getValue().getFirst())
                .toArray();
        return ages.length == 0 ? 0 : Stats.median(ages);
    }

    /**
     * {@code t_vis} of the stratum most covered hit IDs are in, E with none; 0 when that stratum has none. A tie goes to
     * the stratum seen first in key order.
     */
    public static int tVis(LineageHits hits, Set<Integer> covered, StopOdds odds) {
        Map<Stratum, Integer> count = new LinkedHashMap<>();
        for (int u : hits.hitDays().keySet()) {
            IdOdds o = odds.byId().get(u);
            if (o != null && covered.contains(u)) count.merge(o.stratum(), 1, Integer::sum);
        }
        Stratum most = Stratum.E;
        int top = 0;
        for (Map.Entry<Stratum, Integer> c : count.entrySet()) {
            if (c.getValue() > top) {
                most = c.getKey();
                top = c.getValue();
            }
        }
        return odds.tVis().getOrDefault(most, 0);
    }

    /** Covered hit IDs; on a refused page, every hit ID. */
    public static int usersHit(LineageHits hits, Set<Integer> covered, PageState page) {
        if (!USABLE.contains(page)) return hits.hitDays().size();
        return (int) hits.hitDays().keySet().stream().filter(covered::contains).count();
    }

    private static boolean fewAccounts(Input in, LineageEstimate first) {
        return in.clustersExist() && first.accEff() < FEW_ACCOUNTS;
    }

    /** With few accounts the numbers come from the rerun without the account cell, when it compared any hit ID. */
    private static LineageEstimate used(Input in, LineageEstimate first) {
        LineageEstimate without = first.withoutAccounts();
        return fewAccounts(in, first) && without != null ? without : first;
    }

    private static Decision blank(Verdict v) {
        return new Decision(v, false, null, null, null, null, null, 0, 0, false, null, null, null, null);
    }

    private record Out(
            LineageEstimate e, Numbers n, double vis, boolean contradicted, PopulationReading.@Nullable Result pop) {

        Decision hidden(Verdict v) {
            return decision(v, false, null, null, null);
        }

        Decision shown(Verdict v, double lost, double low, double high) {
            return decision(v, true, lost, low, high);
        }

        private Decision decision(
                Verdict v, boolean shown, @Nullable Double lost, @Nullable Double low, @Nullable Double high) {
            return new Decision(
                    v,
                    shown,
                    e.nM(),
                    lost,
                    low,
                    high,
                    n.high(),
                    n.z(),
                    vis,
                    contradicted,
                    pop == null ? null : pop.pop(),
                    pop == null ? null : pop.popHigh(),
                    lost == null ? n.lost() : lost,
                    e);
        }
    }
}
