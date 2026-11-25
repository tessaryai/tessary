// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.DAYS_PER_WEEK;
import static ai.tessary.endusers.judge.JudgeConstants.FEW_ACCOUNTS;
import static ai.tessary.endusers.judge.JudgeConstants.LATE_HIT_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.TAU;
import static ai.tessary.endusers.judge.JudgeConstants.Z;
import static ai.tessary.endusers.judge.JudgeConstants.Z_WIDE;
import static ai.tessary.endusers.judge.JudgeConstants.Z_WIDE_BELOW_ACCOUNTS;
import static ai.tessary.endusers.judge.JudgeConstants.Z_WIDE_BELOW_MATCHED;

import ai.tessary.endusers.judge.Cells.Cell;
import ai.tessary.endusers.judge.Cells.Match;
import ai.tessary.endusers.judge.Cells.Pools;
import ai.tessary.endusers.judge.Trials.Arm;
import ai.tessary.endusers.judge.Trials.Person;
import ai.tessary.endusers.judge.Trials.Trial;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.HitSet;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChange;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChangeEvent;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.IssueEstimate;
import ai.tessary.endusers.judge.model.IssueEstimate.LineageEstimate;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.StopOdds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The estimate of spec 8.5 for each issue lineage: the residual "stops seen in exposed time minus stops expected in that
 * time from like IDs not yet hit", per compared hit ID, over {@code n_m}, with the issue's own null. There is no pooled
 * estimate over issues and no count of IDs slowed.
 */
public final class Estimate {

    private Estimate() {}

    /**
     * The shown numbers, before the verdict: rates with the null's mean taken off, and counts scaled by {@code n_est}.
     *
     * @param seTot max({@code se}, {@code s0}, {@code tau})
     */
    public record Numbers(double seTot, double z, double rateLo, double rateHi, double lost, double low, double high) {}

    record Group(int week, Cell cell) {}

    /**
     * @param trials matched exposed trials
     * @param unmatched hit IDs with an exposed trial but none matched
     */
    record Fit(
            int nM,
            double rateLo,
            double rateHi,
            double se,
            double accEff,
            double sumLo,
            double sumHi,
            double expLo,
            int trials,
            int unmatched) {}

    /** The residual sum of a cluster, or of an ID with no cluster: the two never share a key. */
    private record AccountKey(boolean cluster, int value) {}

    public static IssueEstimate estimate(
            Facts facts, Series series, Identity identity, IdChannelResult channel, HitSet hits, StopOdds odds) {
        Population pop = Population.of(facts, series, identity, channel, hits, odds);
        Map<String, LineageEstimate> byLineage = new TreeMap<>();
        hits.byLineage().forEach((key, lineage) -> {
            LineageEstimate e = lineage(key, lineage, pop, false);
            if (e == null) return;
            if (pop.clustersExist && e.accEff() < FEW_ACCOUNTS) {
                e = withAccountsDropped(e, lineage(key, lineage, pop, true));
            }
            byLineage.put(key, e);
        });
        return new IssueEstimate(byLineage, pop.h);
    }

    public static Numbers numbers(LineageEstimate e) {
        double seTot = seTot(e.se(), e.s0());
        double z = z(e.nM(), e.accEff());
        double rateLo = e.rateLo() - e.b0();
        double rateHi = e.rateHi() - e.b0();
        return new Numbers(
                seTot,
                z,
                rateLo,
                rateHi,
                Math.max(0, rateLo) * e.nEst(),
                Math.max(0, rateLo - z * seTot) * e.nEst(),
                Math.max(0, rateHi + z * seTot) * e.nEst());
    }

    static double seTot(double se, double s0) {
        return Math.max(se, Math.max(s0, TAU));
    }

    static double z(int nM, double accEff) {
        return nM < Z_WIDE_BELOW_MATCHED || accEff < Z_WIDE_BELOW_ACCOUNTS ? Z_WIDE : Z;
    }

    /** The weeks left out for an ID change not solved by aliases: the week of each event and the week after it. */
    static Set<Integer> idChangeWeeks(IdChannelResult channel) {
        Set<Integer> weeks = new HashSet<>();
        IdChange change = channel.idChange();
        if (change == null) return weeks;
        for (IdChangeEvent ev : change.events()) {
            if (ev.solvedByAliases()) continue;
            int w = Math.floorDiv(ev.day(), DAYS_PER_WEEK);
            weeks.add(w);
            weeks.add(w + 1);
        }
        return weeks;
    }

    private static LineageEstimate withAccountsDropped(LineageEstimate e, @Nullable LineageEstimate without) {
        return new LineageEstimate(
                e.usersHit(),
                e.nEst(),
                e.nM(),
                e.rateLo(),
                e.rateHi(),
                e.se(),
                e.b0(),
                e.s0(),
                e.nullMax(),
                e.draws(),
                e.accEff(),
                e.sumLo(),
                e.sumHi(),
                e.expLo(),
                e.trials(),
                e.controlIds(),
                e.lateHits(),
                e.unmatched(),
                e.estimateIds(),
                without);
    }

    /** {@code run_issue}: null when no hit ID can be compared. */
    private static @Nullable LineageEstimate lineage(
            String key, LineageHits lineage, Population pop, boolean dropAccounts) {
        Map<Integer, List<Integer>> covered = new TreeMap<>();
        lineage.hitDays().forEach((u, ds) -> {
            if (pop.covered.contains(u)) covered.put(u, ds);
        });
        Map<Integer, Integer> firstHit = new TreeMap<>();
        int late = 0;
        for (Map.Entry<Integer, List<Integer>> e : covered.entrySet()) {
            Person p = pop.people.get(e.getKey());
            if (p == null) continue;
            int first = e.getValue().getFirst();
            if (first <= p.series().days().getLast() + LATE_HIT_DAYS) firstHit.put(e.getKey(), first);
            else late++;
        }
        Integer fe = lineage.firstEvidenceDay();
        Integer le = lineage.lastEvidenceDay();
        if (firstHit.isEmpty() || fe == null || le == null) return null;

        List<Trial> trials = Trials.build(pop.peopleOf(lineage), fe, le, pop.h, pop.asOfDay);
        if (dropAccounts) trials = trials.stream().map(Trial::withoutAccount).toList();
        Set<Integer> hitWeeks = new TreeSet<>();
        firstHit.values().forEach(d -> hitWeeks.add(Math.floorDiv(d, DAYS_PER_WEEK)));
        Set<Integer> controlIds = new HashSet<>();
        Map<Group, List<Trial>> groups = new LinkedHashMap<>();
        for (Trial t : trials) {
            if (t.arm() == Arm.UNEXPOSED && hitWeeks.contains(t.week()) && !firstHit.containsKey(t.id())) {
                controlIds.add(t.id());
            } else if (t.arm() == Arm.EXPOSED
                    && firstHit.containsKey(t.id())
                    && !pop.idChangeWeeks.contains(t.week())) {
                groups.computeIfAbsent(new Group(t.week(), t.cell()), g -> new ArrayList<>())
                        .add(t);
            }
        }
        Pools pools = new Pools(trials);
        Fit fit = fit(groups, pools, pop.accountOf);
        if (fit == null) return null;

        Map<Integer, Integer> realFirstHits = new TreeMap<>();
        firstHit.values().forEach(d -> {
            int w = Math.floorDiv(d, DAYS_PER_WEEK);
            if (!pop.idChangeWeeks.contains(w)) realFirstHits.merge(w, 1, Integer::sum);
        });
        OwnNull.Result own = OwnNull.run(key, firstHit.size(), realFirstHits, trials, pools, pop.h);
        return new LineageEstimate(
                covered.size(),
                firstHit.size(),
                fit.nM(),
                fit.rateLo(),
                fit.rateHi(),
                fit.se(),
                own.b0(),
                own.s0(),
                own.nullMax(),
                own.draws(),
                fit.accEff(),
                fit.sumLo(),
                fit.sumHi(),
                fit.expLo(),
                fit.trials(),
                controlIds.size(),
                late,
                fit.unmatched(),
                Set.copyOf(firstHit.keySet()),
                null);
    }

    /**
     * {@code estimate}: each group of exposed trials against its pool. The account sum of {@code se} reads the real
     * clusters even when the cell drops them.
     */
    static @Nullable Fit fit(Map<Group, List<Trial>> groups, Pools pools, Map<Integer, Integer> accountOf) {
        Map<Integer, Double> resLo = new LinkedHashMap<>();
        Map<Integer, Double> resHi = new LinkedHashMap<>();
        Set<Integer> unmatchedIds = new HashSet<>();
        double vg = 0;
        double sumLo = 0;
        double sumHi = 0;
        double expLo = 0;
        int matched = 0;
        for (Map.Entry<Group, List<Trial>> g : groups.entrySet()) {
            List<Trial> rows = g.getValue();
            Match match = pools.match(g.getKey().week(), g.getKey().cell());
            if (match == null) {
                rows.forEach(t -> unmatchedIds.add(t.id()));
                continue;
            }
            double n = rows.size();
            double m = match.pool().m();
            double hLo = match.pool().hLo();
            double hHi = match.pool().hHi();
            vg += n * n * Math.max(hLo * (1 - hLo), 0.25 / m) / m;
            for (Trial t : rows) {
                resLo.merge(t.id(), t.yLo() - hLo, Double::sum);
                resHi.merge(t.id(), t.yHi() - hHi, Double::sum);
                sumLo += t.yLo();
                sumHi += t.yHi();
                expLo += hLo;
                matched++;
            }
        }
        int nM = resLo.size();
        if (nM == 0) return null;
        Map<AccountKey, Double> byAccount = new HashMap<>();
        Map<AccountKey, Integer> idsByAccount = new HashMap<>();
        resLo.forEach((u, r) -> {
            Integer cluster = accountOf.get(u);
            AccountKey a = cluster != null ? new AccountKey(true, cluster) : new AccountKey(false, u);
            byAccount.merge(a, r, Double::sum);
            idsByAccount.merge(a, 1, Integer::sum);
        });
        double accountSq = 0;
        for (double r : byAccount.values()) accountSq += r * r;
        double idsSq = 0;
        for (int k : idsByAccount.values()) idsSq += (double) k * k;
        unmatchedIds.removeAll(resLo.keySet());
        return new Fit(
                nM,
                sum(resLo) / nM,
                sum(resHi) / nM,
                Math.sqrt(accountSq + vg) / nM,
                (double) nM * nM / idsSq,
                sumLo,
                sumHi,
                expLo,
                matched,
                unmatchedIds.size());
    }

    private static double sum(Map<Integer, Double> xs) {
        double s = 0;
        for (double x : xs.values()) s += x;
        return s;
    }

    /** What every issue reads: the IDs that can enter an estimate and their days, outcome, account and dose. */
    private static final class Population {
        final int asOfDay;
        final int h;
        final Set<Integer> covered = new HashSet<>();
        final Map<Integer, Integer> accountOf = new HashMap<>();
        final boolean clustersExist;
        final Set<Integer> idChangeWeeks;
        final Map<Integer, Person> people = new TreeMap<>();
        final Map<Integer, List<Integer>> allEvidence = new HashMap<>();
        final Map<Set<Integer>, Map<Integer, NavigableMap<Integer, Integer>>> doseByReach = new HashMap<>();
        final Facts facts;
        final Map<Integer, Integer> aliasOf;

        private Population(Facts facts, Identity identity, IdChannelResult channel, StopOdds odds) {
            this.facts = facts;
            this.idChangeWeeks = idChangeWeeks(channel);
            this.asOfDay = facts.asOfDay();
            this.h = Trials.h(odds.mAppCalendar());
            this.aliasOf = identity.aliasOf();
            boolean clusters = false;
            for (Map.Entry<Integer, IdIdentity> e : identity.byId().entrySet()) {
                if (e.getValue().covered()) covered.add(e.getKey());
                Integer account = e.getValue().account();
                if (account != null) {
                    accountOf.put(e.getKey(), account);
                    clusters = true;
                }
            }
            this.clustersExist = clusters;
        }

        static Population of(
                Facts facts, Series series, Identity identity, IdChannelResult channel, HitSet hits, StopOdds odds) {
            Population pop = new Population(facts, identity, channel, odds);
            hits.byLineage()
                    .values()
                    .forEach(l -> l.hitDays()
                            .forEach((u, ds) -> pop.allEvidence
                                    .computeIfAbsent(u, k -> new ArrayList<>())
                                    .addAll(ds)));
            pop.allEvidence.values().forEach(ds -> ds.sort(null));
            for (int u : pop.covered) {
                if (channel.cannotFollow().contains(u)) continue;
                Series.IdSeries s = series.byId().get(u);
                pop.people.put(
                        u,
                        new Person(
                                s,
                                channel.bornInChange().contains(u),
                                odds.byId().get(u).pStop(),
                                pop.accountOf.get(u),
                                new TreeMap<>(),
                                List.of(),
                                pop.allEvidence.getOrDefault(u, List.of())));
            }
            return pop;
        }

        /** The people with a trace on the issue's reach, with the issue's evidence days and their dose. */
        Map<Integer, Person> peopleOf(LineageHits lineage) {
            Map<Integer, NavigableMap<Integer, Integer>> dose = doseByReach.computeIfAbsent(
                    new HashSet<>(lineage.reachRoutes()),
                    r -> IssueHits.dose(facts, aliasOf, lineage.reachRoutes(), people.keySet()));
            Map<Integer, Person> out = new TreeMap<>();
            people.forEach((u, p) -> {
                NavigableMap<Integer, Integer> perDay = dose.get(u);
                if (perDay == null) return;
                out.put(
                        u,
                        new Person(
                                p.series(),
                                p.bornInChange(),
                                p.pStop(),
                                p.account(),
                                perDay,
                                lineage.hitDays().getOrDefault(u, List.of()),
                                p.allEvidence()));
            });
            return out;
        }
    }
}
