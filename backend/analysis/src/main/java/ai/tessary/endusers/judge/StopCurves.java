// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.AU_MIN_GAPS;
import static ai.tessary.endusers.judge.JudgeConstants.AU_MIN_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.DAYS_PER_WEEK;
import static ai.tessary.endusers.judge.JudgeConstants.DEFAULT_GAP_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.GAP_FLOOR;
import static ai.tessary.endusers.judge.JudgeConstants.K1_MAX_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.K2_MAX_DAYS;

import ai.tessary.endusers.judge.Survival.Curve;
import ai.tessary.endusers.judge.Survival.Observation;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.PageStateResult;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.SilenceResult.ClockInterval;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.Stratum;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Nominal stop odds of spec 5: Kaplan-Meier curves of "the gap is longer than x" per stratum, built from the IDs' own
 * gaps on the clock of spec 4, and each ID's silence read on the curve of its stratum. The past check runs the same
 * machinery again at each anchor on the series cut before it.
 */
public final class StopCurves {

    private static final double MIN_GAP = 1e-6; // [ref]
    private static final int TERCILE_MIN_IDS = 150; // [ref]
    private static final int E_RECENT_GAPS = 10; // [ref]
    private static final int AUTO_ALIVE_MIN_DAYS = 7; // [ref]
    private static final double AUTO_GAP_SHARE = 0.5; // [ref]
    private static final Curve EMPTY = new Curve(List.of(), List.of(), List.of());

    private StopCurves() {}

    /** The IDs the curves are fitted on: the covered IDs when the page is usable and any is covered, else every ID. */
    public static Set<Integer> fit(Series series, Identity identity, PageStateResult page) {
        boolean usable = page.state() == PageState.OK || page.state() == PageState.PARTIAL_COVERAGE;
        Set<Integer> covered = new TreeSet<>();
        series.byId().keySet().forEach(u -> {
            if (identity.byId().get(u).covered()) covered.add(u);
        });
        return usable && !covered.isEmpty()
                ? covered
                : new TreeSet<>(series.byId().keySet());
    }

    /** The odds at {@code D} on the clock of spec 4, with ID-loss and ID-change silences ending at their censor day. */
    public static Nominal now(
            Facts facts,
            Series series,
            Identity identity,
            IdChannelResult channel,
            SilenceResult silence,
            PageStateResult page) {
        return compute(
                facts.asOfDay(),
                days(series),
                fit(series, identity, page),
                silence.clock(),
                channel.censoredFrom(),
                series.byId(),
                channel.bornInChange());
    }

    static Map<Integer, List<Integer>> days(Series series) {
        Map<Integer, List<Integer>> days = new TreeMap<>();
        series.byId().forEach((u, s) -> days.put(u, s.days()));
        return days;
    }

    /**
     * The odds of every ID in {@code days} with its silence open at {@code day}. {@code full} gives the automation days,
     * which are never cut; an ID born in an ID change with fewer than 4 series days fits no curve.
     */
    static Nominal compute(
            int day,
            Map<Integer, List<Integer>> days,
            Set<Integer> fit,
            Map<Integer, List<ClockInterval>> clock,
            Map<Integer, Integer> censoredFrom,
            Map<Integer, IdSeries> full,
            Set<Integer> bornInChange) {
        Map<Integer, double[]> gaps = new HashMap<>();
        Map<Integer, boolean[]> autoGaps = new HashMap<>();
        List<Double> means = new ArrayList<>();
        days.forEach((u, sr) -> {
            List<ClockInterval> iv = clock.getOrDefault(u, List.of());
            List<Integer> auto = full.get(u).autoDays();
            double[] g = new double[sr.size() - 1];
            boolean[] ga = new boolean[g.length];
            double sum = 0;
            for (int i = 0; i < g.length; i++) {
                g[i] = Math.max(SilenceClock.length(iv, sr.get(i), sr.get(i + 1)), MIN_GAP);
                ga[i] = gapAuto(auto, sr.get(i), sr.get(i + 1));
                sum += g[i];
            }
            gaps.put(u, g);
            autoGaps.put(u, ga);
            if (g.length >= K2_MAX_DAYS && fit.contains(u)) means.add(sum / g.length);
        });
        Double mApp = upperMiddle(means);
        double ma = mApp != null ? mApp : DEFAULT_GAP_DAYS;
        Map<Integer, Double> mU = new HashMap<>();
        List<Double> fitMu = new ArrayList<>();
        gaps.forEach((u, g) -> {
            if (g.length < K2_MAX_DAYS) return;
            double m = mU(g, ma);
            mU.put(u, m);
            if (fit.contains(u)) fitMu.add(m);
        });
        Collections.sort(fitMu);
        int nMu = fitMu.size();
        List<Double> cuts = nMu >= TERCILE_MIN_IDS ? List.of(fitMu.get(nMu / 3), fitMu.get(2 * nMu / 3)) : List.of();

        List<Observation> k1 = new ArrayList<>();
        List<Observation> k2 = new ArrayList<>();
        List<Observation> au = new ArrayList<>();
        Map<Integer, List<Observation>> est = new TreeMap<>();
        Map<Integer, Double> open = new HashMap<>();
        Map<Integer, Boolean> openAuto = new HashMap<>();
        int auEvents = 0;
        int auIds = 0;
        for (Map.Entry<Integer, List<Integer>> e : days.entrySet()) {
            int u = e.getKey();
            List<Integer> sr = e.getValue();
            IdSeries s = full.get(u);
            Integer ce = censoredFrom.get(u);
            int end = ce == null || ce > day ? day : ce;
            double so = Math.max(0.0, SilenceClock.length(clock.getOrDefault(u, List.of()), sr.getLast(), end));
            boolean oa = !s.autoDays().isEmpty() && autoAlive(s, day);
            open.put(u, so);
            openAuto.put(u, oa);
            if (!fit.contains(u)) continue;
            if (!s.autoDays().isEmpty()) auIds++;
            int k = sr.size();
            if (k <= K2_MAX_DAYS && bornInChange.contains(u)) continue;
            double[] g = gaps.get(u);
            boolean[] ga = autoGaps.get(u);
            for (int i = 0; i < g.length; i++) {
                if (ga[i]) {
                    au.add(new Observation(g[i], true));
                    auEvents++;
                }
            }
            if (oa) au.add(new Observation(so, false));
            if (g.length > 0) {
                if (!ga[0]) k1.add(new Observation(g[0], true));
            } else if (!oa) {
                k1.add(new Observation(so, false));
            }
            for (int i = 1; i < Math.min(g.length, K2_MAX_DAYS); i++) {
                if (!ga[i]) k2.add(new Observation(g[i], true));
            }
            if (k > K1_MAX_DAYS && k <= K2_MAX_DAYS && !oa) k2.add(new Observation(so, false));
            if (g.length >= K2_MAX_DAYS) {
                double m = mU.get(u);
                List<Observation> obs = est.computeIfAbsent(bisectRight(cuts, m), x -> new ArrayList<>());
                for (int i = Math.max(K2_MAX_DAYS, g.length - E_RECENT_GAPS); i < g.length; i++) {
                    if (!ga[i]) obs.add(new Observation(g[i] / m, true));
                }
                if (!oa) obs.add(new Observation(so / m, false));
            }
        }
        Map<Integer, Curve> ke = new TreeMap<>();
        est.forEach((st, obs) -> ke.put(st, Survival.km(obs)));
        boolean useAu = auEvents >= AU_MIN_GAPS && auIds >= AU_MIN_IDS;
        Curves curves = new Curves(Survival.km(k1), Survival.km(k2), ke, useAu ? Survival.km(au) : null, cuts);

        Map<Integer, IdNominal> byId = new TreeMap<>();
        days.forEach((u, sr) -> {
            int k = sr.size();
            double so = open.get(u);
            boolean oa = openAuto.get(u);
            Curve auCurve = curves.au();
            if (oa && auCurve != null) {
                byId.put(u, new IdNominal(Stratum.AU, Survival.pNominal(auCurve, so), so, false));
            } else if (k <= K2_MAX_DAYS && bornInChange.contains(u)) {
                byId.put(u, new IdNominal(Stratum.E, curves.e(ma, so / ma), so, oa));
            } else if (k == K1_MAX_DAYS) {
                byId.put(u, new IdNominal(Stratum.K1, Survival.pNominal(curves.k1(), so), so, oa));
            } else if (k <= K2_MAX_DAYS) {
                byId.put(u, new IdNominal(Stratum.K2, Survival.pNominal(curves.k2(), so), so, oa));
            } else {
                double m = mU.get(u);
                byId.put(u, new IdNominal(Stratum.E, curves.e(m, so / m), so, oa));
            }
        });
        return new Nominal(byId, mApp, curves);
    }

    /** {@code m_u = max(0.5, (sum g + 2 m_app) / (count g + 2))}: the ID's mean gap pulled toward the app's. */
    static double mU(double[] gaps, double mApp) {
        double sum = 0;
        for (double g : gaps) sum += g;
        return Math.max(GAP_FLOOR, (sum + 2 * mApp) / (gaps.length + 2));
    }

    /** The middle value, the upper of the two for an even count, as the reference reads {@code m_app}. */
    static @Nullable Double upperMiddle(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    /** Automation ran within {@code max(7, 2 * its median gap)} days before {@code day}. */
    private static boolean autoAlive(IdSeries s, int day) {
        List<Integer> auto = s.autoDays();
        int i = lowerBound(auto, day) - 1;
        if (i < 0) return false;
        Double medianGap = s.autoMedianGap();
        double window = Math.max(AUTO_ALIVE_MIN_DAYS, 2 * (medianGap == null ? 0 : medianGap));
        return day - auto.get(i) <= window;
    }

    /** A gap "with automation": automation ran in at least half of its 7-day blocks. */
    private static boolean gapAuto(List<Integer> auto, int from, int to) {
        if (auto.isEmpty() || to - from < 2) return false;
        int blocks = Math.ceilDiv(to - from - 1, DAYS_PER_WEEK);
        int withAuto = 0;
        for (int b = 0; b < blocks; b++) {
            int lo = from + 1 + DAYS_PER_WEEK * b;
            int hi = Math.min(to, lo + DAYS_PER_WEEK);
            int i = lowerBound(auto, lo);
            if (i < auto.size() && auto.get(i) < hi) withAuto++;
        }
        return withAuto >= AUTO_GAP_SHARE * blocks;
    }

    static int lowerBound(List<Integer> sorted, int x) {
        int lo = 0;
        int hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid) < x) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static int bisectRight(List<Double> cuts, double x) {
        int i = 0;
        while (i < cuts.size() && cuts.get(i) <= x) i++;
        return i;
    }

    /**
     * @param mApp the app's typical gap on the clock: the middle mean gap of the fitted IDs with four or more series days;
     *     null with none
     */
    public record Nominal(
            Map<Integer, IdNominal> byId, @Nullable Double mApp, Curves curves) {}

    /**
     * @param clockSilence the open gap on the clock, ended at the censor day for an ID of an ID loss or ID change
     * @param unattended silent while automation runs, judged on its normal stratum because no automation curve exists
     */
    public record IdNominal(Stratum stratum, double pNominal, double clockSilence, boolean unattended) {}

    /**
     * @param e the E curves by tercile of {@code m_u}, read in units of {@code m_u}; one curve under 150 IDs
     * @param au the automation curve; null when it has fewer than 50 gaps or 30 IDs
     * @param cuts the two tercile cuts of {@code m_u}; empty under 150 IDs
     */
    public record Curves(
            Curve k1,
            Curve k2,
            Map<Integer, Curve> e,
            @Nullable Curve au,
            List<Double> cuts) {

        /** {@code p_nominal} of a silence of {@code x} clock days in a stratum; E reads it in units of the app gap. */
        public double pNominal(Stratum stratum, double x, @Nullable Double mApp) {
            if (stratum == Stratum.K1) return Survival.pNominal(k1, x);
            if (stratum == Stratum.K2) return Survival.pNominal(k2, x);
            if (stratum == Stratum.AU && au != null) return Survival.pNominal(au, x);
            double m = mApp != null ? mApp : DEFAULT_GAP_DAYS;
            return e(m, x / m);
        }

        double e(double m, double x) {
            return Survival.pNominal(e.getOrDefault(bisectRight(cuts, m), EMPTY), x);
        }
    }
}
