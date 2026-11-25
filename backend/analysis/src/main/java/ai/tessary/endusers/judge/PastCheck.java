// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.ANCHORS;
import static ai.tessary.endusers.judge.JudgeConstants.ANCHOR_FOLLOW_UP_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.ANCHOR_MIN_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.CALIBRATION_BLOCKS;
import static ai.tessary.endusers.judge.JudgeConstants.CALIBRATION_BLOCK_MIN;
import static ai.tessary.endusers.judge.JudgeConstants.CALIBRATION_PRIOR;
import static ai.tessary.endusers.judge.JudgeConstants.DAYS_PER_WEEK;
import static ai.tessary.endusers.judge.JudgeConstants.F_MAX;
import static ai.tessary.endusers.judge.JudgeConstants.F_MIN;
import static ai.tessary.endusers.judge.JudgeConstants.F_QUANTILE;
import static ai.tessary.endusers.judge.JudgeConstants.K2_MAX_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.NO_ANCHOR_CAP;
import static ai.tessary.endusers.judge.JudgeConstants.RELIABILITY_MIN_ANCHORS;
import static ai.tessary.endusers.judge.JudgeConstants.STOP_CUT;

import ai.tessary.endusers.judge.Isotonic.Block;
import ai.tessary.endusers.judge.Isotonic.Point;
import ai.tessary.endusers.judge.StopCurves.Curves;
import ai.tessary.endusers.judge.StopCurves.IdNominal;
import ai.tessary.endusers.judge.StopCurves.Nominal;
import ai.tessary.endusers.judge.Survival.Curve;
import ai.tessary.endusers.judge.Survival.Observation;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.IdChannelResult.IdLoss;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.PageStateResult;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.SilenceResult.SilenceEvent;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.CalibrationBlock;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.model.SilenceState;
import ai.tessary.endusers.model.Stratum;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Past check of spec 6.1 and 6.2, readings 10 to 13 of spec 5: the nominal odds rebuilt at up to 12 past week-grid
 * dates, calibrated on what each ID did after its date, and tested out of sample. It also reads the calibrated odds of
 * every ID now, with the ID-loss factor, and the calendar odds of the members of an open shared silence.
 */
public final class PastCheck {

    private static final int BEYOND_GROUP_MIN = 100; // [ref]
    private static final int T_MAX_AT_RISK = 20; // [ref]
    private static final int BLEND_ANCHORS = 3; // [ref]
    private static final int VIS_SCAN_DAYS = 60; // [ref]

    private PastCheck() {}

    public static StopOdds check(
            Facts facts,
            Series series,
            Identity identity,
            IdChannelResult channel,
            SilenceResult silence,
            PageStateResult page,
            Nominal now) {
        int asOf = facts.asOfDay();
        Map<Integer, IdSeries> byId = series.byId();
        Set<Integer> fit = StopCurves.fit(series, identity, page);
        Set<Integer> covered = new TreeSet<>();
        byId.keySet().forEach(u -> {
            if (identity.byId().get(u).covered()) covered.add(u);
        });

        List<Integer> coveredFirstSeen = new ArrayList<>();
        List<Integer> calendarGaps = new ArrayList<>();
        List<Double> calendarMeans = new ArrayList<>();
        for (int u : fit) {
            List<Integer> sr = byId.get(u).days();
            if (covered.contains(u)) coveredFirstSeen.add(byId.get(u).firstSeen());
            for (int i = 0; i + 1 < sr.size(); i++) calendarGaps.add(sr.get(i + 1) - sr.get(i));
            if (sr.size() > K2_MAX_DAYS) calendarMeans.add((double) (sr.getLast() - sr.getFirst()) / (sr.size() - 1));
        }
        coveredFirstSeen.sort(null);
        int f = returnCycle(calendarGaps);
        List<Integer> anchors = anchors(series.t0Id(), asOf, coveredFirstSeen);
        int nAnchors = anchors.size();

        List<AnchorRecord> records = new ArrayList<>();
        for (int ai = 0; ai < nAnchors; ai++) {
            records.addAll(anchorRecords(ai, anchors.get(ai), asOf, byId, fit, covered, channel, silence));
        }
        Map<Group, List<PooledBlock>> fitted = nAnchors > 0 ? fitCalibration(records) : Map.of();
        Map<Group, List<Block>> cal = curves(fitted);
        Reliability reliability = reliability(records, nAnchors, asOf, f);

        Curves curves = now.curves();
        Map<Stratum, Double> plateau = new EnumMap<>(Stratum.class);
        Map<Stratum, Integer> tVis = new EnumMap<>(Stratum.class);
        Map<Stratum, Integer> t50 = new EnumMap<>(Stratum.class);
        for (Stratum s : Stratum.values()) {
            if (s == Stratum.AU && curves.au() == null) continue;
            double level = calibrate(cal, nAnchors, s, curves.pNominal(s, f, now.mApp()), false);
            plateau.put(s, level);
            for (int x = 1; x < Math.max(2 * f, VIS_SCAN_DAYS); x++) {
                double pv = calibrate(cal, nAnchors, s, curves.pNominal(s, x, now.mApp()), false);
                if (!tVis.containsKey(s) && pv >= level / 2) tVis.put(s, x);
                if (!t50.containsKey(s) && pv >= STOP_CUT) t50.put(s, x);
                if (tVis.containsKey(s) && t50.containsKey(s)) break;
            }
        }

        Map<Integer, Boolean> beyondNow = new HashMap<>();
        byId.forEach((u, s) -> beyondNow.put(u, beyond(s.days(), asOf)));
        Map<Integer, Double> calendar =
                calendarOdds(asOf, series, fit, covered, channel, silence, cal, nAnchors, beyondNow);
        IdLoss idLoss = channel.idLoss();
        Map<Integer, IdOdds> odds = new TreeMap<>();
        byId.forEach((u, s) -> {
            IdNominal n = now.byId().get(u);
            boolean bey = beyondNow.get(u);
            double pCalibrated = calibrate(cal, nAnchors, n.stratum(), n.pNominal(), bey);
            double pStop = pCalibrated;
            if (idLoss != null && idLoss.lostShare().containsKey(u)) {
                pStop *= 1 - idLoss.phi() * idLoss.lostShare().get(u);
            }
            odds.put(
                    u,
                    new IdOdds(
                            n.stratum(),
                            n.pNominal(),
                            pCalibrated,
                            pStop,
                            calendar.get(u),
                            n.unattended(),
                            bey,
                            asOf - s.days().getLast(),
                            n.clockSilence()));
        });

        return new StopOdds(
                odds,
                now.mApp(),
                StopCurves.upperMiddle(calendarMeans),
                f,
                anchors,
                printed(fitted),
                reliability.namedRight(),
                reliability.namedFound(),
                plateau,
                tVis,
                t50);
    }

    /**
     * The records of one anchor: the odds rebuilt on the series cut before {@code a} with the final clock and no censor
     * day, and the return time read on the full series (reading 11). Members of a shared silence that covers {@code a}
     * are left out (reading 10); an ID censored on or before {@code a} is dropped, one censored later is censored at its
     * censor day (reading 12).
     */
    static List<AnchorRecord> anchorRecords(
            int ai,
            int a,
            int asOf,
            Map<Integer, IdSeries> byId,
            Set<Integer> fit,
            Set<Integer> covered,
            IdChannelResult channel,
            SilenceResult silence) {
        Map<Integer, List<Integer>> cut = new TreeMap<>();
        for (int u : fit) {
            List<Integer> sr = byId.get(u).days();
            int i = StopCurves.lowerBound(sr, a);
            if (i > 0) cut.put(u, sr.subList(0, i));
        }
        Set<Integer> excluded = SilenceClock.coveredAt(silence, a);
        Nominal at =
                StopCurves.compute(a - 1, cut, cut.keySet(), silence.clock(), Map.of(), byId, channel.bornInChange());
        int followUp = asOf - a + 1;
        List<AnchorRecord> records = new ArrayList<>();
        cut.forEach((u, sra) -> {
            if (excluded.contains(u) || !covered.contains(u)) return;
            List<Integer> sr = byId.get(u).days();
            int i0 = StopCurves.lowerBound(sr, a);
            boolean returned = i0 < sr.size();
            int time = returned ? sr.get(i0) - a : followUp;
            Integer cd = channel.censoredFrom().get(u);
            if (cd != null) {
                if (cd <= a) return;
                if (!returned || time > cd - a) {
                    returned = false;
                    time = cd - a;
                }
            }
            IdNominal n = at.byId().get(u);
            records.add(new AnchorRecord(
                    ai,
                    a,
                    n.stratum(),
                    n.pNominal(),
                    beyond(sra, a - 1),
                    time,
                    returned,
                    !returned && time == followUp));
        });
        return records;
    }

    /**
     * {@code p_stop} on the calendar clock for the covered members of an open shared silence: the value with the silence
     * counted in full, which decides if the ID is held.
     */
    private static Map<Integer, Double> calendarOdds(
            int asOf,
            Series series,
            Set<Integer> fit,
            Set<Integer> covered,
            IdChannelResult channel,
            SilenceResult silence,
            Map<Group, List<Block>> cal,
            int nAnchors,
            Map<Integer, Boolean> beyondNow) {
        Set<Integer> members = new TreeSet<>();
        for (SilenceEvent event : silence.events()) {
            if (event.state() == SilenceState.OPEN) members.addAll(event.members());
        }
        Map<Integer, Double> calendar = new HashMap<>();
        if (members.isEmpty()) return calendar;
        Nominal full = StopCurves.compute(
                asOf,
                StopCurves.days(series),
                fit,
                Map.of(),
                channel.censoredFrom(),
                series.byId(),
                channel.bornInChange());
        for (int u : members) {
            IdNominal n = full.byId().get(u);
            if (n == null || !covered.contains(u)) continue;
            calendar.put(u, calibrate(cal, nAnchors, n.stratum(), n.pNominal(), beyondNow.get(u)));
        }
        return calendar;
    }

    /** Week-grid days {@code a} with {@code t0_id + 14 <= a <= D - 14} and 50 or more covered IDs first seen before it. */
    static List<Integer> anchors(int t0Id, int asOfDay, List<Integer> coveredFirstSeen) {
        List<Integer> candidates = new ArrayList<>();
        for (int d = t0Id + ANCHOR_FOLLOW_UP_DAYS; d <= asOfDay - ANCHOR_FOLLOW_UP_DAYS; d++) {
            if (d % DAYS_PER_WEEK == 0 && StopCurves.lowerBound(coveredFirstSeen, d) >= ANCHOR_MIN_IDS) {
                candidates.add(d);
            }
        }
        return List.copyOf(candidates.subList(Math.max(0, candidates.size() - ANCHORS), candidates.size()));
    }

    /** {@code F}: the 99th percentile completed calendar gap, cut to whole days and clamped to 14 to 42. */
    static int returnCycle(List<Integer> gaps) {
        if (gaps.isEmpty()) return F_MIN;
        double[] xs = gaps.stream().mapToDouble(Integer::doubleValue).toArray();
        return (int) Math.min(Math.max(Stats.quantile(xs, F_QUANTILE), F_MIN), F_MAX);
    }

    /** Four or more series days and a calendar silence up to {@code day} longer than the longest completed gap. */
    static boolean beyond(List<Integer> days, int day) {
        if (days.size() <= K2_MAX_DAYS) return false;
        int longest = 0;
        for (int i = 0; i + 1 < days.size(); i++) longest = Math.max(longest, days.get(i + 1) - days.get(i));
        return day - days.getLast() > longest;
    }

    /**
     * The share of a block that has not come back, read from its return curve {@code R}: {@code R(t_max)} less the fall
     * of the last third, {@code R(2 t_max / 3) - R(t_max)}, which is expected once more. {@code t_max} is the 20th
     * largest time.
     */
    static double blockValue(List<Observation> block) {
        Curve r = Survival.km(block);
        double[] times = block.stream().mapToDouble(Observation::time).sorted().toArray();
        int m = times.length;
        double tMax = m >= T_MAX_AT_RISK ? times[m - T_MAX_AT_RISK] : times[0];
        double rMax = Survival.surv(r, tMax);
        double tail = Math.max(0.0, Survival.surv(r, 2.0 * tMax / 3.0) - rMax);
        return Math.max(0.0, rMax - tail);
    }

    /**
     * Records sorted by {@code p_nominal} (stable, so ties keep anchor and ID order) in at most 8 equal blocks of 40 or
     * more; each block's value carries 3 prior records at its mean {@code p_nominal}; adjacent violators are pooled.
     * Empty under 40 records.
     */
    static List<PooledBlock> blocks(List<AnchorRecord> records) {
        int n = records.size();
        int nb = Math.min(CALIBRATION_BLOCKS, n / CALIBRATION_BLOCK_MIN);
        if (nb < 1) return List.of();
        List<AnchorRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator.comparingDouble(AnchorRecord::pNominal));
        List<Point> points = new ArrayList<>(nb);
        List<List<AnchorRecord>> raw = new ArrayList<>(nb);
        for (int j = 0; j < nb; j++) {
            List<AnchorRecord> blk = sorted.subList(j * n / nb, (j + 1) * n / nb);
            int m = blk.size();
            double sum = 0;
            List<Observation> obs = new ArrayList<>(m);
            for (AnchorRecord r : blk) {
                sum += r.pNominal();
                obs.add(new Observation(r.time(), r.returned()));
            }
            double mp = sum / m;
            double value = (m * blockValue(obs) + CALIBRATION_PRIOR * mp) / (m + CALIBRATION_PRIOR);
            points.add(new Point(mp, value, m + (double) CALIBRATION_PRIOR));
            raw.add(blk);
        }
        List<Block> pooled = Isotonic.pav(points);
        List<PooledBlock> out = new ArrayList<>(pooled.size());
        int start = 0;
        for (int j = 0; j < pooled.size(); j++) {
            int end = start + 1;
            // Pooling only merges at the top of the stack, so the points before a pooled block's end pool to exactly
            // the blocks before it, in the same operation order: the prefix whose blocks equal them is that end.
            if (j < pooled.size() - 1) {
                while (!Isotonic.pav(points.subList(0, end)).equals(pooled.subList(0, j + 1))) end++;
            } else {
                end = points.size();
            }
            List<AnchorRecord> first = raw.get(start);
            List<AnchorRecord> last = raw.get(end - 1);
            int count = 0;
            for (int i = start; i < end; i++) count += raw.get(i).size();
            Block b = pooled.get(j);
            out.add(new PooledBlock(
                    b.centre(),
                    b.value(),
                    first.getFirst().pNominal(),
                    last.getLast().pNominal(),
                    count));
            start = end;
        }
        return List.copyOf(out);
    }

    /**
     * Blocks per stratum, and per stratum and {@code beyond} for a group of 100 or more records (reading 11).
     * Iteration order is stratum, then fitted on both kinds, inside, beyond.
     */
    private static Map<Group, List<PooledBlock>> fitCalibration(List<AnchorRecord> records) {
        Map<Stratum, List<AnchorRecord>> byType = new EnumMap<>(Stratum.class);
        Map<Stratum, Map<Boolean, List<AnchorRecord>>> byGroup = new EnumMap<>(Stratum.class);
        for (AnchorRecord r : records) {
            byType.computeIfAbsent(r.stratum(), s -> new ArrayList<>()).add(r);
            byGroup.computeIfAbsent(r.stratum(), s -> new TreeMap<>())
                    .computeIfAbsent(r.beyond(), b -> new ArrayList<>())
                    .add(r);
        }
        Map<Group, List<PooledBlock>> cal = new LinkedHashMap<>();
        byType.forEach((s, list) -> {
            List<PooledBlock> b = blocks(list);
            if (!b.isEmpty()) cal.put(new Group(s, null), b);
            byGroup.get(s).forEach((bey, group) -> {
                if (group.size() < BEYOND_GROUP_MIN) return;
                List<PooledBlock> gb = blocks(group);
                if (!gb.isEmpty()) cal.put(new Group(s, bey), gb);
            });
        });
        return cal;
    }

    private static Map<Group, List<Block>> curves(Map<Group, List<PooledBlock>> fitted) {
        Map<Group, List<Block>> cal = new HashMap<>();
        fitted.forEach((g, blocks) -> cal.put(
                g, blocks.stream().map(b -> new Block(b.centre(), b.value())).toList()));
        return cal;
    }

    private static List<CalibrationBlock> printed(Map<Group, List<PooledBlock>> fitted) {
        List<CalibrationBlock> out = new ArrayList<>();
        fitted.forEach((g, blocks) -> blocks.forEach(
                b -> out.add(new CalibrationBlock(g.stratum(), g.beyond(), b.pLow(), b.pHigh(), b.value(), b.n()))));
        return List.copyOf(out);
    }

    /**
     * {@code p_stop}: with no anchor {@code min(p_nominal, 0.75)}; with 1 or 2 anchors the mean of that and the
     * calibrated value, weights {@code 3 - anchors} and {@code anchors}; else the calibrated value. The {@code beyond}
     * group is read when it was fitted, else the stratum; a stratum with no blocks keeps the capped value.
     */
    static double calibrate(
            Map<Group, List<Block>> cal, int anchors, Stratum stratum, double pNominal, boolean beyond) {
        double base = Math.min(pNominal, NO_ANCHOR_CAP);
        if (anchors == 0) return base;
        List<Block> blocks = cal.get(new Group(stratum, beyond));
        if (blocks == null) blocks = cal.get(new Group(stratum, null));
        if (blocks == null) return base;
        double v = Isotonic.interp(blocks, pNominal);
        if (anchors < BLEND_ANCHORS) return ((BLEND_ANCHORS - anchors) * base + anchors * v) / BLEND_ANCHORS;
        return v;
    }

    /**
     * Reliability out of sample (reading 13): the older half of the anchors fits, the newer half tests. Null with fewer
     * than 4 anchors, or when no test record counts.
     */
    static Reliability reliability(List<AnchorRecord> records, int anchors, int asOfDay, int f) {
        if (anchors < RELIABILITY_MIN_ANCHORS) return new Reliability(null, null);
        int half = anchors / 2;
        Map<Group, List<Block>> older = curves(
                fitCalibration(records.stream().filter(r -> r.anchor() < half).toList()));
        int named = 0;
        int namedAway = 0;
        int away = 0;
        int awayNamed = 0;
        for (AnchorRecord r : records) {
            if (r.anchor() < half) continue;
            boolean isNamed = calibrate(older, BLEND_ANCHORS, r.stratum(), r.pNominal(), r.beyond()) >= STOP_CUT;
            if (isNamed) {
                named++;
                if (r.stayedAway()) namedAway++;
            }
            if (r.stayedAway() && asOfDay - r.anchorDay() >= f) {
                away++;
                if (isNamed) awayNamed++;
            }
        }
        return new Reliability(
                named > 0 ? (double) namedAway / named : null, away > 0 ? (double) awayNamed / away : null);
    }

    /**
     * One anchor record.
     *
     * @param anchor the anchor's index, oldest first
     * @param time days from the anchor to the first series day at or after it, else to the censor day or {@code D + 1}
     * @param returned a series day at or after the anchor ends the time
     * @param stayedAway no series day in {@code [a, D]} and no censor day
     */
    record AnchorRecord(
            int anchor,
            int anchorDay,
            Stratum stratum,
            double pNominal,
            boolean beyond,
            double time,
            boolean returned,
            boolean stayedAway) {}

    /** A calibration group: a stratum, with {@code beyond} null for the group fitted on both kinds. */
    record Group(Stratum stratum, @Nullable Boolean beyond) {}

    /** A pooled block: its centre and value, the range of {@code p_nominal} of its records and their count. */
    record PooledBlock(double centre, double value, double pLow, double pHigh, int n) {}

    record Reliability(
            @Nullable Double namedRight, @Nullable Double namedFound) {}
}
