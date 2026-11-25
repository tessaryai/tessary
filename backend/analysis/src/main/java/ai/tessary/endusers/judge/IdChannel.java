// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.CLUSTER_MIN_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.DAYS_PER_WEEK;
import static ai.tessary.endusers.judge.JudgeConstants.GROUP_MIN_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.ID_CHANGE_ALIAS_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.ID_CHANGE_BIRTHS;
import static ai.tessary.endusers.judge.JudgeConstants.ID_CHANGE_BIRTHS_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.ID_CHANGE_MIN_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.ID_CHANGE_VANISH;
import static ai.tessary.endusers.judge.JudgeConstants.ID_CHANGE_VETERAN_QUANTILE;
import static ai.tessary.endusers.judge.JudgeConstants.ID_CHANGE_VOLUME;
import static ai.tessary.endusers.judge.JudgeConstants.ID_CHANNEL_NORMAL_WEEKS;
import static ai.tessary.endusers.judge.JudgeConstants.ID_LOSS_HOLD;
import static ai.tessary.endusers.judge.JudgeConstants.ID_LOSS_RISE;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.NullDay;
import ai.tessary.endusers.judge.model.Facts.Version;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChange;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChangeEvent;
import ai.tessary.endusers.judge.model.IdChannelResult.IdLoss;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.Scope;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.endusers.model.ScopeKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * ID loss and ID change of spec 3.4, ported from the reference {@code logic.py}. The weekly tables read routes not of
 * class C; a week is a whole week ({@code day / 7}) from the first whole week after {@code t0} to the week before the
 * one that holds {@code D}. ID loss has no gate on {@code kappa} (reading 5 of spec 5). Every event that the aliases do
 * not solve holds its vanished IDs; when the aliases solve every event, no ID change is reported.
 */
public final class IdChannel {

    private static final int SLICE_MIN_TRACES = 200; // [ref]
    private static final double ID_LOSS_SES = 4; // [S]
    private static final int NORMAL_WINDOW_WEEKS = 8; // [ref]
    private static final double SHARE_CLAMP = 0.005; // [ref]
    private static final int SLICE_LABELS = 6; // [ref]
    private static final int RECENT_DAYS = 28; // [ref]
    private static final int FIRST_DAYS = 14; // [ref]
    private static final int VANISH_MIN_DAYS = 3; // [ref]
    private static final int CHANGE_MIN_COVERED = 30; // [ref]
    private static final int CHANGE_MIN_WEEKS = 5; // [ref]
    private static final String APP = "app";
    private static final String APP_GROUP = "(app)";

    private IdChannel() {}

    public static IdChannelResult detect(Facts facts, Series series, Identity identity) {
        int wkFirst = Math.ceilDiv(facts.t0(), DAYS_PER_WEEK);
        int wkLast = week(facts.asOfDay()) - 1;
        List<Integer> covered = identity.byId().entrySet().stream()
                .filter(e -> e.getValue().covered())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        Map<Integer, int[]> rows = DaySeries.rowsByRepresentative(facts.idDays(), identity.aliasOf());
        Set<Integer> nullVersions = facts.versions().stream()
                .filter(v -> v.projectVersionId() == null)
                .map(Version::versionNo)
                .collect(Collectors.toSet());
        Map<String, TreeMap<Integer, long[]>> slices = slices(facts, nullVersions);

        Map<String, Loss> activeLoss = new TreeMap<>();
        slices.forEach((label, byWeek) -> {
            Loss loss = loss(byWeek, wkFirst, wkLast);
            if (loss != null && loss.active()) activeLoss.put(label, loss);
        });
        IdLoss idLoss = activeLoss.isEmpty()
                ? null
                : idLoss(facts, series, covered, rows, nullVersions, slices.get(APP), activeLoss, wkFirst, wkLast);

        List<IdChangeEvent> events = covered.size() >= CHANGE_MIN_COVERED && wkLast - wkFirst >= CHANGE_MIN_WEEKS
                ? changeEvents(facts, series, identity, covered, rows, wkFirst, wkLast)
                : List.of();

        Map<Integer, Integer> censoredFrom = new TreeMap<>();
        Set<Integer> cannotFollow = new TreeSet<>();
        Set<Integer> bornInChange = new TreeSet<>();
        for (IdChangeEvent event : events) {
            if (event.solvedByAliases()) continue;
            for (int u : event.vanished()) {
                cannotFollow.add(u);
                censoredFrom.put(u, event.day());
            }
            bornInChange.addAll(event.born());
        }
        IdChange idChange = cannotFollow.isEmpty() && bornInChange.isEmpty()
                ? null
                : new IdChange(
                        events,
                        events.stream()
                                .filter(e -> !e.solvedByAliases())
                                .mapToInt(IdChangeEvent::day)
                                .min()
                                .getAsInt());
        if (idLoss != null) {
            for (int u : idLoss.lostShare().keySet()) censoredFrom.putIfAbsent(u, idLoss.onsetDay());
        }
        return new IdChannelResult(idLoss, idChange, censoredFrom, cannotFollow, bornInChange);
    }

    /** The chance that an ID of {@code V} only lost its label: {@code kappa * max(0, |V| - V0) / |V|}. */
    static double phi(double kappa, int ids, double vanishedNormal) {
        return ids == 0 ? 0.0 : kappa * Math.max(0.0, ids - vanishedNormal) / ids;
    }

    /**
     * All traces and traces with no user ID per week, for the app, each route, and each named version and (route,
     * version). A NULL version makes no version slice.
     */
    private static Map<String, TreeMap<Integer, long[]>> slices(Facts facts, Set<Integer> nullVersions) {
        Map<String, TreeMap<Integer, long[]>> slices = new TreeMap<>();
        for (NullDay row : facts.nullDays()) {
            if (nonC(facts.storedRouteClasses(), row.routeNo())) {
                List<String> labels = new ArrayList<>(List.of(APP, "r|" + row.routeNo()));
                if (!nullVersions.contains(row.versionNo())) {
                    labels.add("v|" + row.versionNo());
                    labels.add("rv|" + row.routeNo() + "|" + row.versionNo());
                }
                for (String label : labels) {
                    long[] week = slices.computeIfAbsent(label, k -> new TreeMap<>())
                            .computeIfAbsent(week(row.day()), w -> new long[2]);
                    week[0] += row.traces();
                    week[1] += row.tracesNoUserId();
                }
            }
        }
        return slices;
    }

    private record Loss(int onsetWeek, boolean active, boolean provisional) {}

    private record WeekShare(int week, double share) {}

    /**
     * The first week whose null share is 0.10 and 4 standard errors above the median of the normal weeks before it,
     * confirmed by the next whole week or provisional in the latest one. Weeks under 200 traces are skipped; a week
     * joins the normal weeks unless it is the onset or lies in a loss whose excess is above 0.05.
     */
    private static @Nullable Loss loss(TreeMap<Integer, long[]> byWeek, int wkFirst, int wkLast) {
        if (wkFirst > wkLast) return null;
        List<WeekShare> normal = new ArrayList<>();
        Integer onset = null;
        boolean provisional = false;
        double lastExcess = 0.0;
        for (Map.Entry<Integer, long[]> e :
                byWeek.subMap(wkFirst, true, wkLast, true).entrySet()) {
            long n = e.getValue()[0];
            if (n < SLICE_MIN_TRACES) continue;
            int w = e.getKey();
            double share = (double) e.getValue()[1] / n;
            double[] before = window(normal, w);
            if (before.length < ID_CHANNEL_NORMAL_WEEKS) {
                if (onset == null) normal.add(new WeekShare(w, share));
                continue;
            }
            double normalShare = Stats.median(before);
            double excess = share - normalShare;
            if (onset != null) {
                lastExcess = excess;
                if (excess <= ID_LOSS_HOLD) normal.add(new WeekShare(w, share));
                continue;
            }
            double p0 = Math.min(1 - SHARE_CLAMP, Math.max(SHARE_CLAMP, normalShare));
            if (excess >= ID_LOSS_RISE && excess >= ID_LOSS_SES * Math.sqrt(p0 * (1 - p0) / n)) {
                boolean confirmed;
                if (w + 1 > wkLast) {
                    confirmed = true;
                    provisional = true;
                } else {
                    long[] next = byWeek.get(w + 1);
                    double[] beforeNext = window(normal, w + 1);
                    confirmed = next != null
                            && next[0] >= SLICE_MIN_TRACES
                            && (double) next[1] / next[0] - Stats.median(beforeNext.length == 0 ? before : beforeNext)
                                    >= ID_LOSS_RISE;
                }
                if (confirmed) {
                    onset = w;
                    lastExcess = excess;
                    continue;
                }
            }
            normal.add(new WeekShare(w, share));
        }
        return onset == null ? null : new Loss(onset, lastExcess > ID_LOSS_HOLD, provisional);
    }

    private static double[] window(List<WeekShare> normal, int w) {
        return normal.stream()
                .filter(x -> w - NORMAL_WINDOW_WEEKS <= x.week() && x.week() < w)
                .mapToDouble(WeekShare::share)
                .toArray();
    }

    private static IdLoss idLoss(
            Facts facts,
            Series series,
            List<Integer> covered,
            Map<Integer, int[]> rows,
            Set<Integer> nullVersions,
            TreeMap<Integer, long[]> app,
            Map<String, Loss> activeLoss,
            int wkFirst,
            int wkLast) {
        int asOfDay = facts.asOfDay();
        int onsetWeek =
                activeLoss.values().stream().mapToInt(Loss::onsetWeek).min().getAsInt();
        int onsetDay = onsetWeek * DAYS_PER_WEEK;
        Set<String> affected = activeLoss.keySet();
        IdDays days = facts.idDays();
        RouteClasses classes = facts.storedRouteClasses();

        Map<Integer, Double> lostShare = new TreeMap<>();
        double goneVolume = 0.0;
        for (int u : covered) {
            int last = series.byId().get(u).days().getLast();
            if (last < onsetDay - DAYS_PER_WEEK || last > asOfDay - DAYS_PER_WEEK) continue;
            long total = 0;
            long onAffected = 0;
            for (int i : rows.get(u)) {
                int day = days.day()[i];
                if (!nonC(classes, days.routeNo()[i]) || day < last - RECENT_DAYS + 1 || day > last) continue;
                total += days.traces()[i];
                if (affected(affected, days.routeNo()[i], days.versionNo()[i], nullVersions))
                    onAffected += days.traces()[i];
            }
            if (onAffected > 0) {
                lostShare.put(u, (double) onAffected / total);
                goneVolume += total / (double) (RECENT_DAYS / DAYS_PER_WEEK);
            }
        }

        Map<Integer, Integer> vanishWeeks = vanishWeeks(covered, series);
        double[] normalVanish = new double[Math.max(0, onsetWeek - 1 - (wkFirst + 1))];
        for (int i = 0; i < normalVanish.length; i++) normalVanish[i] = vanishWeeks.getOrDefault(wkFirst + 1 + i, 0);
        double vanishedNormal = (normalVanish.length >= ID_CHANNEL_NORMAL_WEEKS ? Stats.median(normalVanish) : 0.0)
                * Math.max(1.0, (asOfDay - (onsetDay - DAYS_PER_WEEK)) / (double) DAYS_PER_WEEK);

        double[] normalShares = app.headMap(onsetWeek, false).tailMap(wkFirst, true).values().stream()
                .filter(x -> x[0] > 0)
                .mapToDouble(x -> (double) x[1] / x[0])
                .toArray();
        double[] lastShares = Arrays.copyOfRange(
                normalShares, Math.max(0, normalShares.length - NORMAL_WINDOW_WEEKS), normalShares.length);
        double p0 = lastShares.length == 0 ? 0.0 : Stats.median(lastShares);
        double orphanPerWeek = app.subMap(onsetWeek, true, wkLast, true).values().stream()
                .mapToDouble(x -> Math.max(0.0, x[1] - p0 * x[0]))
                .average()
                .orElse(0.0);
        double kappa = goneVolume > 0 ? Math.min(1.0, Math.max(0.0, orphanPerWeek / goneVolume)) : 0.0;

        return new IdLoss(
                affected.stream().sorted().limit(SLICE_LABELS).toList(),
                onsetDay,
                activeLoss.values().stream().anyMatch(Loss::provisional),
                kappa,
                phi(kappa, lostShare.size(), vanishedNormal),
                lostShare,
                vanishedNormal);
    }

    private static boolean affected(Set<String> affected, int route, int version, Set<Integer> nullVersions) {
        return affected.contains(APP)
                || affected.contains("r|" + route)
                || (!nullVersions.contains(version)
                        && (affected.contains("v|" + version) || affected.contains("rv|" + route + "|" + version)));
    }

    /** IDs with 3 or more series days, counted by the week after their last series day: their first silent week. */
    private static Map<Integer, Integer> vanishWeeks(List<Integer> ids, Series series) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int u : ids) {
            List<Integer> days = series.byId().get(u).days();
            if (days.size() >= VANISH_MIN_DAYS) counts.merge(week(days.getLast()) + 1, 1, Integer::sum);
        }
        return counts;
    }

    private record ScopeIds(Scope scope, List<Integer> ids) {}

    private static List<IdChangeEvent> changeEvents(
            Facts facts,
            Series series,
            Identity identity,
            List<Integer> covered,
            Map<Integer, int[]> rows,
            int wkFirst,
            int wkLast) {
        List<ScopeIds> scopes = new ArrayList<>();
        scopes.add(new ScopeIds(new Scope(ScopeKind.APP, null), covered));
        Map<String, List<Integer>> byGroup = new TreeMap<>();
        Map<Integer, List<Integer>> byAccount = new TreeMap<>();
        for (int u : covered) {
            IdIdentity id = identity.byId().get(u);
            byGroup.computeIfAbsent(id.groupKey(), g -> new ArrayList<>()).add(u);
            Integer account = id.account();
            if (account != null)
                byAccount.computeIfAbsent(account, k -> new ArrayList<>()).add(u);
        }
        byGroup.forEach((g, us) -> {
            if (!g.equals(APP_GROUP) && us.size() >= GROUP_MIN_IDS)
                scopes.add(new ScopeIds(new Scope(ScopeKind.SHAPE, g), us));
        });
        byAccount.forEach((k, us) -> {
            if (us.size() >= CLUSTER_MIN_IDS)
                scopes.add(new ScopeIds(new Scope(ScopeKind.CLUSTER, String.valueOf(k)), us));
        });

        Map<Integer, Map<Integer, Long>> weeklyTraces = new HashMap<>();
        IdDays days = facts.idDays();
        for (int u : covered) {
            Map<Integer, Long> byWeek = new HashMap<>();
            for (int i : rows.get(u)) {
                if (nonC(facts.storedRouteClasses(), days.routeNo()[i]))
                    byWeek.merge(week(days.day()[i]), (long) days.traces()[i], Long::sum);
            }
            weeklyTraces.put(u, byWeek);
        }
        List<Integer> aliasLastWeeks = identity.aliasOf().keySet().stream()
                .map(x -> week(facts.ids().get(x).lastDay()))
                .toList();

        List<IdChangeEvent> events = new ArrayList<>();
        for (ScopeIds scope : scopes) {
            ScopeTables t = tables(scope.ids(), series, weeklyTraces);
            Set<Integer> flagged = new HashSet<>();
            int w = wkFirst + ID_CHANNEL_NORMAL_WEEKS;
            while (w + 1 <= wkLast) {
                List<Integer> normal = new ArrayList<>();
                for (int x = w - NORMAL_WINDOW_WEEKS; x < w; x++) {
                    if (x >= wkFirst && !flagged.contains(x)) normal.add(x);
                }
                if (normal.size() >= ID_CHANNEL_NORMAL_WEEKS) {
                    IdChangeEvent event = event(scope, t, series, aliasLastWeeks, w, normal);
                    if (event != null) {
                        events.add(event);
                        flagged.add(w);
                        flagged.add(w + 1);
                        w += 2;
                        continue;
                    }
                }
                w++;
            }
        }
        return events;
    }

    /** The weekly counts of one scope; {@code bornWeek} holds the IDs that are not left-censored. */
    private record ScopeTables(
            Map<Integer, Integer> births,
            Map<Integer, Integer> vanish,
            Map<Integer, Integer> active,
            Map<Integer, Long> established,
            Map<Integer, Long> fresh,
            Map<Integer, Integer> bornWeek) {}

    private static ScopeTables tables(List<Integer> ids, Series series, Map<Integer, Map<Integer, Long>> weeklyTraces) {
        Map<Integer, Integer> births = new HashMap<>();
        Map<Integer, Integer> active = new HashMap<>();
        Map<Integer, Long> established = new HashMap<>();
        Map<Integer, Long> fresh = new HashMap<>();
        Map<Integer, Integer> bornWeek = new HashMap<>();
        for (int u : ids) {
            IdSeries s = series.byId().get(u);
            int fw = week(s.firstSeen());
            if (!s.leftCensored()) {
                births.merge(fw, 1, Integer::sum);
                bornWeek.put(u, fw);
            }
            s.days().stream().map(IdChannel::week).distinct().forEach(w -> active.merge(w, 1, Integer::sum));
            weeklyTraces.get(u).forEach((w, c) -> {
                if (fw < w - 1) established.merge(w, c, Long::sum);
                else if (fw == w - 1 || fw == w) fresh.merge(w, c, Long::sum);
            });
        }
        return new ScopeTables(births, vanishWeeks(ids, series), active, established, fresh, bornWeek);
    }

    /**
     * Week {@code w} with {@code w + 1}: births and vanished IDs above normal, the fall of established traffic matched
     * by new IDs, and new IDs more active in their first 14 days than normal new IDs. {@code linked} counts every alias
     * member whose last day falls before the two weeks, in any scope, as the reference does.
     */
    private static @Nullable IdChangeEvent event(
            ScopeIds scope, ScopeTables t, Series series, List<Integer> aliasLastWeeks, int w, List<Integer> normal) {
        double activeNormal = medianOf(t.active(), normal);
        double birthsExcess = twoWeeks(t.births(), w) - 2 * medianOf(t.births(), normal);
        if (birthsExcess < Math.max(ID_CHANGE_BIRTHS, ID_CHANGE_BIRTHS_SHARE * activeNormal)) return null;
        double vanishExcess = twoWeeks(t.vanish(), w) - 2 * medianOf(t.vanish(), normal);
        if (vanishExcess < ID_CHANGE_VANISH * birthsExcess) return null;
        double fall = 2 * medianOf(t.established(), normal) - twoWeeks(t.established(), w);
        double rise = twoWeeks(t.fresh(), w) - 2 * medianOf(t.fresh(), normal);
        if (fall <= 0 || rise < ID_CHANGE_VOLUME * fall) return null;

        Predicate<Integer> inEvent = x -> x == w || x == w + 1;
        Set<Integer> normalWeeks = new HashSet<>(normal);
        double[] newActive = firstDaysActive(scope.ids(), t, series, inEvent);
        double[] oldActive = firstDaysActive(scope.ids(), t, series, normalWeeks::contains);
        if (newActive.length < ID_CHANGE_MIN_IDS
                || oldActive.length < ID_CHANGE_MIN_IDS
                || Stats.median(newActive) < Stats.quantile(oldActive, ID_CHANGE_VETERAN_QUANTILE)) return null;

        List<Integer> vanished = scope.ids().stream()
                .filter(u -> {
                    List<Integer> days = series.byId().get(u).days();
                    return days.size() >= VANISH_MIN_DAYS && inEvent.test(week(days.getLast()) + 1);
                })
                .toList();
        List<Integer> born = scope.ids().stream()
                .filter(u ->
                        t.bornWeek().containsKey(u) && inEvent.test(t.bornWeek().get(u)))
                .toList();
        int linked =
                (int) aliasLastWeeks.stream().filter(x -> inEvent.test(x + 1)).count();
        boolean solved = linked > 0 && linked >= ID_CHANGE_ALIAS_SHARE * Math.max(1, linked + vanished.size());
        return new IdChangeEvent(
                scope.scope(),
                w * DAYS_PER_WEEK,
                birthsExcess,
                vanishExcess,
                activeNormal,
                linked,
                solved,
                vanished,
                born);
    }

    /** Series days in the first 14 days of each ID born in a week that {@code bornIn} accepts. */
    private static double[] firstDaysActive(
            List<Integer> ids, ScopeTables t, Series series, Predicate<Integer> bornIn) {
        return ids.stream()
                .filter(u ->
                        t.bornWeek().containsKey(u) && bornIn.test(t.bornWeek().get(u)))
                .mapToDouble(u -> {
                    IdSeries s = series.byId().get(u);
                    return s.days().stream()
                            .filter(d -> d >= s.firstSeen() && d < s.firstSeen() + FIRST_DAYS)
                            .count();
                })
                .toArray();
    }

    private static <N extends Number> double medianOf(Map<Integer, N> counts, List<Integer> weeks) {
        return Stats.median(weeks.stream()
                .mapToDouble(x -> counts.containsKey(x) ? counts.get(x).doubleValue() : 0.0)
                .toArray());
    }

    private static <N extends Number> double twoWeeks(Map<Integer, N> counts, int w) {
        double sum = 0.0;
        for (int x = w; x <= w + 1; x++)
            sum += counts.containsKey(x) ? counts.get(x).doubleValue() : 0.0;
        return sum;
    }

    private static boolean nonC(RouteClasses classes, int routeNo) {
        return DaySeries.classOf(classes, routeNo) != RouteClass.C;
    }

    private static int week(int day) {
        return Math.floorDiv(day, DAYS_PER_WEEK);
    }
}
