// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.DAYS_PER_WEEK;
import static ai.tessary.endusers.judge.JudgeConstants.HOLD_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.PANEL_DAYS_PER_WEEK;
import static ai.tessary.endusers.judge.JudgeConstants.PANEL_MIN_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.RETURN_WAVE;
import static ai.tessary.endusers.judge.JudgeConstants.RHO_HIT_MIN_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.SILENCE_EXCESS;
import static ai.tessary.endusers.judge.JudgeConstants.SILENCE_SIGMAS;
import static ai.tessary.endusers.judge.JudgeConstants.TRAFFIC_ENDED_MIN_WEEKLY;
import static ai.tessary.endusers.judge.JudgeConstants.TRAFFIC_ENDED_SHARE;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.HitSet;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.PageStateResult;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.Scope;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.SilenceResult.ClockInterval;
import ai.tessary.endusers.judge.model.SilenceResult.SilenceEvent;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.ScopeKind;
import ai.tessary.endusers.model.SilenceState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Shared silence of spec 4, reading 9 of spec 5: weeks in which many regular IDs of a scope go silent together, the
 * hold of their silence and the clock intervals it gives. Weeks are {@code day / 7}. Each whole week from the fifth
 * week of history is tested, then the provisional window of the 7 days that end at {@code D} against shifted 7-day
 * blocks. Events are found again on every run; nothing is carried over.
 */
public final class SharedSilence {

    private static final int SCOPE_MIN_IDS = 20; // [ref]
    private static final int CLUSTER_PANEL_MIN_IDS = 20; // [ref]
    private static final int FIRST_TEST_WEEK = 4; // [ref]
    private static final int PANEL_MIN_DAYS = 3; // [ref]
    private static final int RATE_WEEKS = 8; // [ref]
    private static final int RATE_MIN_WEEKS = 2; // [ref]
    private static final int USUAL_WEEKS = 12; // [ref]
    private static final int USUAL_MIN_WEEKS = 3; // [ref]
    private static final double VARIANCE_FLOOR = 1e-4; // [ref]
    private static final double HIT_EXCESS_FLOOR = 1e-9; // [ref]
    private static final int FRESH_HIT_DAYS = 28; // [ref]
    private static final int MEMBER_BEFORE_DAYS = 4; // [ref]
    private static final int MEMBER_AFTER_DAYS = 6; // [ref]
    private static final int SILENT_DAYS = 7; // [ref]
    private static final int TRAFFIC_BEFORE_DAYS = 28; // [ref]
    private static final int TRAFFIC_AFTER_DAYS = 14; // [ref]
    private static final int TRAFFIC_MIN_DAYS = 7; // [ref]
    private static final int BLOCKS = RATE_WEEKS + 2;
    private static final String APP_GROUP = "(app)";
    private static final SilenceResult NONE = new SilenceResult(List.of(), Map.of());

    private SharedSilence() {}

    /**
     * Covered IDs in {@code V} of an ID loss or cut by an ID change are in no scope. Hit keys are alias representatives,
     * as in {@code series}.
     */
    public static SilenceResult find(
            Facts facts, Series series, Identity identity, IdChannelResult channel, HitSet hits, PageStateResult page) {
        if (page.state() != PageState.OK && page.state() != PageState.PARTIAL_COVERAGE) return NONE;
        Set<Integer> excluded = new HashSet<>(channel.cannotFollow());
        if (channel.idLoss() != null)
            excluded.addAll(channel.idLoss().lostShare().keySet());
        List<Integer> covered = identity.byId().entrySet().stream()
                .filter(e -> e.getValue().covered() && !excluded.contains(e.getKey()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (covered.size() < SCOPE_MIN_IDS) return NONE;

        Run run = new Run(facts.asOfDay(), Math.floorDiv(facts.t0(), DAYS_PER_WEEK), firstHits(hits));
        for (int u : covered) run.add(u, series.byId().get(u).days());
        List<ScopeIds> scopes = scopes(identity, covered);

        List<Set<Integer>> eventWeeks = new ArrayList<>();
        List<Map<Integer, Double>> usual = new ArrayList<>();
        for (int s = 0; s < scopes.size(); s++) {
            eventWeeks.add(new HashSet<>());
            usual.add(new HashMap<>());
        }
        Event[] open = new Event[scopes.size()];
        List<Event> events = new ArrayList<>();
        for (int w = run.firstWeek + FIRST_TEST_WEEK; w <= run.lastWeek; w++) {
            for (int s = 0; s < scopes.size(); s++) {
                Window window = window(run, scopes.get(s), eventWeeks.get(s), usual.get(s), w, true);
                if (window == null) {
                    open[s] = null;
                    continue;
                }
                usual.get(s).put(w, window.i());
                if (!window.event()) {
                    open[s] = null;
                    continue;
                }
                eventWeeks.get(s).add(w);
                if (open[s] == null) {
                    open[s] = new Event(s, window);
                    events.add(open[s]);
                }
            }
        }
        if (run.asOfDay - (DAYS_PER_WEEK - 1) >= DAYS_PER_WEEK * (run.firstWeek + FIRST_TEST_WEEK)) {
            for (int s = 0; s < scopes.size(); s++) {
                Window window = window(run, scopes.get(s), eventWeeks.get(s), usual.get(s), run.lastWeek + 1, false);
                if (window == null || !window.event()) continue;
                if (open[s] == null) {
                    open[s] = new Event(s, window);
                    events.add(open[s]);
                }
                open[s].provisional = true;
            }
        }

        Map<Integer, int[]> rows = DaySeries.rowsByRepresentative(facts.idDays(), identity.aliasOf());
        Map<Integer, Map<Span, Double>> weights = new TreeMap<>();
        List<SilenceEvent> out = new ArrayList<>();
        for (Event event : events) {
            out.add(resolve(run, facts, rows, scopes.get(event.scope), event, weights));
        }
        Map<Integer, List<ClockInterval>> clock = new TreeMap<>();
        weights.forEach((u, spans) -> clock.put(
                u,
                spans.entrySet().stream()
                        .map(e ->
                                new ClockInterval(e.getKey().from(), e.getKey().to(), e.getValue()))
                        .toList()));
        return new SilenceResult(out, clock);
    }

    /** Step 3: enough IDs, an excess of 0.15 or more, and an excess of 3 standard errors or more. */
    static boolean eventWeek(double i, @Nullable Double i0, int n, int need) {
        return i0 != null
                && n >= need
                && i - i0 >= SILENCE_EXCESS
                && i - i0 >= SILENCE_SIGMAS * Math.sqrt(Math.max(i0 * (1 - i0), VARIANCE_FLOOR) / n);
    }

    /** Step 5: a break explains only as much silence among freshly hit IDs as it makes among the others. */
    static double rhoHit(double rho, int freeIds, double xFree, int hitIds, double xHit) {
        if (freeIds < RHO_HIT_MIN_IDS || hitIds < RHO_HIT_MIN_IDS) return rho;
        return rho * Math.min(1.0, Math.max(0.0, xFree) / Math.max(xHit, HIT_EXCESS_FLOOR));
    }

    private static SilenceEvent resolve(
            Run run,
            Facts facts,
            Map<Integer, int[]> rows,
            ScopeIds scope,
            Event event,
            Map<Integer, Map<Span, Double>> weights) {
        Window first = event.first;
        double[] lastDays = first.newlySilent().stream()
                .mapToDouble(u -> {
                    int[] days = run.days.get(u);
                    return days[bisectLeft(days, first.start()) - 1] + 1;
                })
                .toArray();
        int start = (int) Math.rint(Stats.median(lastDays));
        double i0 = first.i0();
        double rho = (first.i() - i0) / first.i();
        List<Integer> free = new ArrayList<>();
        List<Integer> hit = new ArrayList<>();
        for (int u : first.panel()) (run.fresh(u, first.start()) ? hit : free).add(u);
        double rhoHit = rhoHit(
                rho,
                free.size(),
                silentShare(free, first.silent()) - i0,
                hit.size(),
                silentShare(hit, first.silent()) - i0);

        List<Integer> members = new ArrayList<>();
        List<Double> returns = new ArrayList<>();
        for (int u : scope.ids()) {
            int[] days = run.days.get(u);
            int j = bisectRight(days, start + MEMBER_AFTER_DAYS) - 1;
            if (j < 0 || days[j] < start - MEMBER_BEFORE_DAYS) continue;
            int last = days[j];
            boolean returned = j + 1 < days.length;
            if (returned ? days[j + 1] - last < SILENT_DAYS : run.asOfDay - last < SILENT_DAYS) continue;
            members.add(u);
            if (returned) returns.add((double) days[j + 1]);
        }

        SilenceState state = SilenceState.OPEN;
        Integer end = null;
        if (returns.size() >= RETURN_WAVE * members.size()) {
            int median = (int) Math.rint(Stats.median(
                    returns.stream().mapToDouble(Double::doubleValue).toArray()));
            if (median > start) {
                state = SilenceState.PROVEN_BREAK;
                end = median;
            }
        }
        if (state == SilenceState.OPEN) {
            if (run.asOfDay - start >= TRAFFIC_MIN_DAYS && trafficEnded(run.asOfDay, facts, rows, members, start)) {
                state = SilenceState.TRAFFIC_ENDED;
            } else if (run.asOfDay - start >= HOLD_DAYS) {
                state = SilenceState.NOT_A_BREAK;
            }
        }

        int coverEnd =
                switch (state) {
                    case PROVEN_BREAK -> end;
                    case OPEN -> run.asOfDay + 1;
                    case NOT_A_BREAK, TRAFFIC_ENDED -> start + HOLD_DAYS;
                };
        if (state == SilenceState.PROVEN_BREAK || state == SilenceState.OPEN) {
            Span span = new Span(start, coverEnd);
            for (int u : members) {
                weights.computeIfAbsent(u, k -> new TreeMap<>(Span.ORDER))
                        .merge(span, run.fresh(u, first.start()) ? rhoHit : rho, Math::max);
            }
        }
        return new SilenceEvent(
                scope.scope(),
                start,
                end,
                state,
                event.provisional,
                first.n(),
                first.i(),
                i0,
                rho,
                rhoHit,
                List.copyOf(members),
                coverEnd);
    }

    private static @Nullable Window window(
            Run run, ScopeIds scope, Set<Integer> eventWeeks, Map<Integer, Double> usual, int w, boolean whole) {
        int start = whole ? w * DAYS_PER_WEEK : run.asOfDay - (DAYS_PER_WEEK - 1);
        List<Integer> panel = new ArrayList<>();
        Set<Integer> silent = new HashSet<>();
        for (int u : scope.ids()) {
            int[] days = run.days.get(u);
            if (bisectLeft(days, start) < PANEL_MIN_DAYS) continue;
            int weeks = 0;
            int count = 0;
            int current;
            if (whole) {
                int[] byWeek = run.weekCounts.get(u);
                if (byWeek[w - 1 - run.firstWeek] == 0) continue;
                int from = Math.max(w - RATE_WEEKS, Math.floorDiv(days[0], DAYS_PER_WEEK) + 1);
                for (int x = from; x < w; x++) {
                    if (eventWeeks.contains(x)) continue;
                    weeks++;
                    count += byWeek[x - run.firstWeek];
                }
                current = byWeek[w - run.firstWeek];
            } else {
                int[] blocks = run.blockCounts.get(u);
                if (blocks[1] == 0) continue;
                for (int j = 1; j <= RATE_WEEKS; j++) {
                    int blockStart = start - DAYS_PER_WEEK * j;
                    if (days[0] > blockStart - DAYS_PER_WEEK
                            || eventWeeks.contains(Math.floorDiv(blockStart, DAYS_PER_WEEK))) continue;
                    weeks++;
                    count += blocks[j];
                }
                current = blocks[0];
            }
            if (weeks < RATE_MIN_WEEKS || (double) count / weeks < PANEL_DAYS_PER_WEEK) continue;
            panel.add(u);
            if (current == 0) silent.add(u);
        }
        if (panel.isEmpty()) return null;
        List<Integer> free = panel.stream().filter(u -> !run.fresh(u, start)).toList();
        List<Integer> tested = free.size() >= scope.need() ? free : panel;
        List<Integer> newlySilent = tested.stream().filter(silent::contains).toList();
        double i = (double) newlySilent.size() / tested.size();
        double[] history = usual.entrySet().stream()
                .filter(e -> e.getKey() >= w - USUAL_WEEKS && e.getKey() < w && !eventWeeks.contains(e.getKey()))
                .mapToDouble(Map.Entry::getValue)
                .toArray();
        Double i0 = history.length >= USUAL_MIN_WEEKS ? Stats.median(history) : null;
        return new Window(
                start, panel, silent, newlySilent, tested.size(), i, i0, eventWeek(i, i0, tested.size(), scope.need()));
    }

    /**
     * Step 6, evidence against a break: the members' traffic on routes that are not paced, 14 days after the start
     * against the 28 days before, as traces per week over the days there are.
     */
    private static boolean trafficEnded(
            int asOfDay, Facts facts, Map<Integer, int[]> rows, List<Integer> members, int start) {
        IdDays idDays = facts.idDays();
        RouteClasses classes = facts.storedRouteClasses();
        long before = 0;
        long after = 0;
        for (int u : members) {
            for (int r : rows.getOrDefault(u, new int[0])) {
                if (DaySeries.isPaced(DaySeries.classOf(classes, idDays.routeNo()[r]), classes.lenient())) continue;
                int day = idDays.day()[r];
                if (day >= start - TRAFFIC_BEFORE_DAYS && day < start) before += idDays.traces()[r];
                else if (day >= start && day < start + TRAFFIC_AFTER_DAYS) after += idDays.traces()[r];
            }
        }
        double weeklyBefore = before / ((double) TRAFFIC_BEFORE_DAYS / DAYS_PER_WEEK);
        double daysAfter = Math.min(TRAFFIC_AFTER_DAYS, asOfDay - start + 1);
        return weeklyBefore >= TRAFFIC_ENDED_MIN_WEEKLY
                && after / (daysAfter / DAYS_PER_WEEK) < TRAFFIC_ENDED_SHARE * weeklyBefore;
    }

    /** The whole app, each shape group of 20 or more IDs that is not all of them, and each account of 20 or more. */
    private static List<ScopeIds> scopes(Identity identity, List<Integer> covered) {
        Map<String, List<Integer>> byGroup = new LinkedHashMap<>();
        Map<Integer, List<Integer>> byAccount = new LinkedHashMap<>();
        for (int u : covered) {
            IdIdentity id = identity.byId().get(u);
            byGroup.computeIfAbsent(id.groupKey(), g -> new ArrayList<>()).add(u);
            if (id.account() != null)
                byAccount.computeIfAbsent(id.account(), k -> new ArrayList<>()).add(u);
        }
        List<ScopeIds> scopes = new ArrayList<>();
        scopes.add(new ScopeIds(new Scope(ScopeKind.APP, null), covered, PANEL_MIN_IDS));
        byGroup.forEach((g, us) -> {
            if (!g.equals(APP_GROUP) && us.size() >= SCOPE_MIN_IDS && us.size() < covered.size())
                scopes.add(new ScopeIds(new Scope(ScopeKind.SHAPE, g), us, PANEL_MIN_IDS));
        });
        byAccount.forEach((k, us) -> {
            if (us.size() >= SCOPE_MIN_IDS)
                scopes.add(new ScopeIds(new Scope(ScopeKind.CLUSTER, String.valueOf(k)), us, CLUSTER_PANEL_MIN_IDS));
        });
        return scopes;
    }

    /** Per ID, the first hit day of each issue that is not always present. */
    private static Map<Integer, List<Integer>> firstHits(HitSet hits) {
        Map<Integer, List<Integer>> firstHits = new HashMap<>();
        for (LineageHits lineage : hits.byLineage().values()) {
            if (lineage.alwaysPresent()) continue;
            lineage.hitDays()
                    .forEach((u, days) ->
                            firstHits.computeIfAbsent(u, k -> new ArrayList<>()).add(days.getFirst()));
        }
        return firstHits;
    }

    private static double silentShare(List<Integer> ids, Set<Integer> silent) {
        return (double) ids.stream().filter(silent::contains).count() / ids.size();
    }

    private static int bisectLeft(int[] sorted, int x) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < x) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static int bisectRight(int[] sorted, int x) {
        return bisectLeft(sorted, x + 1);
    }

    private record ScopeIds(Scope scope, List<Integer> ids, int need) {}

    /**
     * One tested week of one scope.
     *
     * @param start the first day of the week or of the provisional window
     * @param silent panel IDs with no series day in the window
     * @param newlySilent the silent IDs of the tested part: the free part, or the whole panel when the free part is
     *     too small
     * @param n the size of the tested part
     */
    private record Window(
            int start,
            List<Integer> panel,
            Set<Integer> silent,
            List<Integer> newlySilent,
            int n,
            double i,
            @Nullable Double i0,
            boolean event) {}

    /** Event weeks in a row of one scope; {@code first} is the window of the first week. */
    private static final class Event {
        final int scope;
        final Window first;
        boolean provisional;

        Event(int scope, Window first) {
            this.scope = scope;
            this.first = first;
        }
    }

    private record Span(int from, int to) {
        static final Comparator<Span> ORDER =
                Comparator.comparingInt(Span::from).thenComparingInt(Span::to);
    }

    /** Series days, week counts and provisional block counts of the IDs in a scope, and their first hits. */
    private static final class Run {
        final int asOfDay;
        final int firstWeek;
        final int lastWeek;
        final Map<Integer, List<Integer>> firstHits;
        final Map<Integer, int[]> days = new HashMap<>();
        final Map<Integer, int[]> weekCounts = new HashMap<>();
        final Map<Integer, int[]> blockCounts = new HashMap<>();

        Run(int asOfDay, int firstWeek, Map<Integer, List<Integer>> firstHits) {
            this.asOfDay = asOfDay;
            this.firstWeek = firstWeek;
            this.lastWeek = Math.floorDiv(asOfDay, DAYS_PER_WEEK) - 1;
            this.firstHits = firstHits;
        }

        void add(int u, List<Integer> seriesDays) {
            int[] sorted = seriesDays.stream().mapToInt(Integer::intValue).toArray();
            int[] byWeek = new int[lastWeek + 2 - firstWeek];
            for (int d : sorted) byWeek[Math.floorDiv(d, DAYS_PER_WEEK) - firstWeek]++;
            int[] blocks = new int[BLOCKS];
            for (int j = 0; j < BLOCKS; j++) {
                int to = asOfDay - DAYS_PER_WEEK * j;
                blocks[j] = bisectRight(sorted, to) - bisectLeft(sorted, to - (DAYS_PER_WEEK - 1));
            }
            days.put(u, sorted);
            weekCounts.put(u, byWeek);
            blockCounts.put(u, blocks);
        }

        /** The ID's first hit of an issue that is not always present lies in the 28 days before {@code start}. */
        boolean fresh(int u, int start) {
            for (int d : firstHits.getOrDefault(u, List.of())) {
                if (d >= start - FRESH_HIT_DAYS && d < start) return true;
            }
            return false;
        }
    }
}
