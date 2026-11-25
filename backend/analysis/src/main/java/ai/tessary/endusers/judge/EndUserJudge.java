// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.FEW_ACCOUNTS;
import static ai.tessary.endusers.judge.JudgeConstants.PAGE_IDENT_RECENT_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.PAGE_IDENT_SHARE_REFUSE;
import static ai.tessary.endusers.judge.JudgeConstants.TOO_EARLY_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.TOO_EARLY_IDS;

import ai.tessary.endusers.judge.IdentityFlags.Flags;
import ai.tessary.endusers.judge.StopCurves.Nominal;
import ai.tessary.endusers.judge.Trials.Person;
import ai.tessary.endusers.judge.Trials.Trial;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IssueInput;
import ai.tessary.endusers.judge.model.Facts.NullDay;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.HitSet;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.IssueEstimate;
import ai.tessary.endusers.judge.model.IssueEstimate.LineageEstimate;
import ai.tessary.endusers.judge.model.IssueResult;
import ai.tessary.endusers.judge.model.IssueResult.AccountLine;
import ai.tessary.endusers.judge.model.IssueResult.CurvePoint;
import ai.tessary.endusers.judge.model.IssueResult.LineageResult;
import ai.tessary.endusers.judge.model.PageStateResult;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.StateResult;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.TracerResult;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.endusers.model.Verdict;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * One judge run over the facts of the judge window, in the fixed order of spec 3: routes, aliases, day series, flags, ID
 * groups, ID channel, tracer, page state, issue hits, shared silence, stop odds, past check, states, issues. No stage
 * reads a later one. Aliases run before the day series, as in the reference: an alias member's days are its
 * representative's. A refused page skips the estimate, as the reference does, so every issue is {@code page_refused}.
 */
public final class EndUserJudge {

    public static final String LOGIC_VERSION = "v3";

    private EndUserJudge() {}

    /** Told after each stage: its name, the size of what it gave, and how long it took. */
    @FunctionalInterface
    public interface StageLog {
        void stage(String name, long rows, long durationMs);
    }

    /**
     * Everything the snapshot is written from.
     *
     * @param estimate the estimates of the issues; empty on a refused page
     * @param decisions the verdict of each issue with the numbers its words read, by lineage key
     */
    public record Judgment(
            Series series,
            Identity identity,
            PageStateResult page,
            HitSet hits,
            SilenceResult silence,
            StopOdds odds,
            StateResult states,
            IssueEstimate estimate,
            Map<String, Verdicts.Decision> decisions,
            IssueResult issues) {}

    public static Judgment judge(Facts facts, Guards guards, StageLog log) {
        Timer t = new Timer(log);
        t.run("routes", facts::storedRouteClasses, r -> r.byRoute().size());
        Map<Integer, Integer> aliasOf = t.run("aliases", () -> Aliases.representatives(facts.aliasEdges()), Map::size);
        Series series = t.run(
                "day_series",
                () -> DaySeries.build(facts, aliasOf),
                s -> s.byId().size());
        Map<Integer, Flags> flags = t.run("flags", () -> IdentityFlags.flags(facts, aliasOf, series), Map::size);
        Identity identity = t.run(
                "groups",
                () -> IdGroups.identity(facts, aliasOf, series, flags),
                i -> i.groups().size());
        IdChannelResult channel = t.run(
                "id_channel",
                () -> IdChannel.detect(facts, series, identity),
                c -> c.censoredFrom().size());
        TracerResult tracer = t.run("tracer", () -> Tracer.test(facts, series, identity), TracerResult::ended);
        PageStateResult page = t.run(
                "page_state",
                () -> PageStateRule.decide(facts, series, identity, flags, channel, tracer),
                p -> p.reasonNumbers().size());
        HitSet hits = t.run(
                "issue_hits",
                () -> IssueHits.build(facts, identity.aliasOf()),
                h -> h.byLineage().size());
        SilenceResult silence = t.run(
                "shared_silence",
                () -> SharedSilence.find(facts, series, identity, channel, hits, page),
                s -> s.events().size());
        Nominal now = t.run(
                "stop_odds",
                () -> StopCurves.now(facts, series, identity, channel, silence, page),
                n -> n.byId().size());
        StopOdds odds = t.run(
                "past_check",
                () -> PastCheck.check(facts, series, identity, channel, silence, page, now),
                o -> o.anchors().size());
        StateResult states = t.run(
                "states",
                () -> States.judge(facts, series, identity, channel, tracer, page, hits, silence, odds, guards),
                s -> s.byId().size());
        Issues issues = t.run(
                "issues",
                () -> new IssueRun(facts, guards, series, identity, channel, tracer, page, hits, odds, states).run(),
                i -> i.result().byLineage().size());
        return new Judgment(
                series,
                identity,
                page,
                hits,
                silence,
                odds,
                states,
                issues.estimate(),
                issues.decisions(),
                issues.result());
    }

    /**
     * The page state of a window with no user-ID day row: the first rules of spec 3.5 over the null-day rows alone.
     * Those rows still count the traces of an ID that was erased, so either share can fire; when neither does, the
     * page is too early. Each is a refusal, so the memory of earlier runs cannot loosen it.
     *
     * @param t0 the stored history start day, the first day with a user ID when there is none
     */
    public static PageStateResult withoutIds(int asOfDay, int t0, List<NullDay> nullDays, RouteClasses classes) {
        double identShare = identShare(nullDays, classes, Integer.MIN_VALUE);
        double identShare28 = identShare(nullDays, classes, asOfDay - PAGE_IDENT_RECENT_DAYS + 1);
        if (identShare < PAGE_IDENT_SHARE_REFUSE) {
            return refusal(PageState.NO_USER_ID, PageStateReason.LOW_ID_SHARE, Map.of("ident_share", identShare));
        }
        if (identShare28 < PAGE_IDENT_SHARE_REFUSE) {
            return refusal(
                    PageState.NO_USER_ID, PageStateReason.LOW_ID_SHARE_28, Map.of("ident_share_28", identShare28));
        }
        int span = asOfDay - t0;
        return refusal(
                PageState.TOO_EARLY,
                span < TOO_EARLY_DAYS ? PageStateReason.SHORT_HISTORY : PageStateReason.FEW_IDS,
                Map.of(
                        "span_days", (double) span,
                        "ids", 0.0,
                        "span_days_needed", (double) TOO_EARLY_DAYS,
                        "ids_needed", (double) TOO_EARLY_IDS));
    }

    /** The size budget of plan 3.8: a window above {@code maxIdDays} user-ID days is not computed. */
    public static boolean overBudget(long idDays, long maxIdDays) {
        return idDays > maxIdDays;
    }

    private static PageStateResult refusal(PageState state, PageStateReason reason, Map<String, Double> numbers) {
        return new PageStateResult(state, state, reason, numbers, false, List.of());
    }

    private static double identShare(List<NullDay> nullDays, RouteClasses classes, int fromDay) {
        long traces = 0;
        long noUserId = 0;
        for (NullDay row : nullDays) {
            if (row.day() < fromDay || DaySeries.classOf(classes, row.routeNo()) == RouteClass.C) continue;
            traces += row.traces();
            noUserId += row.tracesNoUserId();
        }
        return traces == 0 ? 1.0 : 1 - (double) noUserId / traces;
    }

    private record Issues(IssueEstimate estimate, Map<String, Verdicts.Decision> decisions, IssueResult result) {}

    /**
     * Spec 8.5 to 8.9 for every issue. The second contrast and the curve read the trials of the estimate the verdict
     * uses, rebuilt here as {@link Estimate} builds them: with few accounts, the rerun without the account cell.
     */
    private static final class IssueRun {
        private final Facts facts;
        private final Guards guards;
        private final Series series;
        private final Identity identity;
        private final IdChannelResult channel;
        private final TracerResult tracer;
        private final PageStateResult page;
        private final HitSet hits;
        private final StopOdds odds;
        private final StateResult states;
        private final int h;
        private final Set<Integer> covered = new TreeSet<>();
        private final Map<Integer, Integer> accountOf = new HashMap<>();
        private final Set<Integer> idChangeWeeks;
        private final Map<Integer, List<Integer>> allEvidence = new HashMap<>();

        IssueRun(
                Facts facts,
                Guards guards,
                Series series,
                Identity identity,
                IdChannelResult channel,
                TracerResult tracer,
                PageStateResult page,
                HitSet hits,
                StopOdds odds,
                StateResult states) {
            this.facts = facts;
            this.guards = guards;
            this.series = series;
            this.identity = identity;
            this.channel = channel;
            this.tracer = tracer;
            this.page = page;
            this.hits = hits;
            this.odds = odds;
            this.states = states;
            this.h = Trials.h(odds.mAppCalendar());
            identity.byId().forEach((u, id) -> {
                if (id.covered()) covered.add(u);
                Integer account = id.account();
                if (account != null) accountOf.put(u, account);
            });
            this.idChangeWeeks = Estimate.idChangeWeeks(channel);
            hits.byLineage()
                    .values()
                    .forEach(l -> l.hitDays()
                            .forEach((u, ds) -> allEvidence
                                    .computeIfAbsent(u, k -> new ArrayList<>())
                                    .addAll(ds)));
            allEvidence.values().forEach(ds -> ds.sort(null));
        }

        Issues run() {
            boolean usable = States.usable(page.state());
            IssueEstimate estimate = usable
                    ? Estimate.estimate(facts, series, identity, channel, hits, odds)
                    : new IssueEstimate(Map.of(), h);
            boolean clustersExist = !accountOf.isEmpty();
            Map<String, Verdicts.Decision> decisions = new LinkedHashMap<>();
            Map<String, List<Trial>> trialsOf = new HashMap<>();
            for (IssueInput issue : facts.issues()) {
                String key = issue.lineageKey();
                LineageHits lineage = hits.byLineage().get(key);
                LineageEstimate first = estimate.byLineage().get(key);
                PopulationReading.Result pop = null;
                if (first != null) {
                    boolean dropAccounts =
                            clustersExist && first.accEff() < FEW_ACCOUNTS && first.withoutAccounts() != null;
                    List<Trial> trials = trials(lineage, dropAccounts);
                    trialsOf.put(key, trials);
                    pop = PopulationReading.read(lineage, trials, h, facts.asOfDay(), accountOf);
                }
                decisions.put(
                        key,
                        Verdicts.decide(new Verdicts.Input(
                                page.state(),
                                page.groupBanner(),
                                guards,
                                facts.horizonDays(),
                                facts.asOfDay() - series.t0Id(),
                                odds.f(),
                                issue.metricShift(),
                                lineage,
                                first,
                                Verdicts.ageHit(lineage, covered, facts.asOfDay()),
                                Verdicts.tVis(lineage, covered, odds),
                                clustersExist,
                                pop,
                                tracer.verdict())));
            }
            Set<String> shown = new TreeSet<>();
            Map<String, IssueRank.Ranked> ranked = new TreeMap<>();
            decisions.forEach((key, d) -> {
                if (d.shown()) shown.add(key);
                ranked.put(key, new IssueRank.Ranked(d.verdict(), d.rankLost(), d.low()));
            });
            Map<String, String> groups = CoFiring.groups(facts, shown, guards);
            Map<String, Integer> rank = IssueRank.rank(ranked, groups);
            Map<String, LineageResult> byLineage = new LinkedHashMap<>();
            decisions.forEach((key, d) -> byLineage.put(
                    key,
                    result(
                            hits.byLineage().get(key),
                            d,
                            estimate.byLineage().get(key),
                            trialsOf.get(key),
                            rank.get(key),
                            groups.get(key))));
            return new Issues(estimate, decisions, new IssueResult(byLineage));
        }

        private LineageResult result(
                LineageHits lineage,
                Verdicts.Decision d,
                @Nullable LineageEstimate first,
                @Nullable List<Trial> trials,
                int rank,
                @Nullable String groupId) {
            LineageEstimate used = d.used();
            List<CurvePoint> curve = d.shown() && used != null && trials != null
                    ? RetentionCurve.curve(trials, used, idChangeWeeks, lineage, series, odds, facts.asOfDay())
                    : List.of();
            boolean fewAccounts = d.verdict() == Verdict.FEW_ACCOUNTS && first != null;
            List<AccountLine> accountLines =
                    fewAccounts ? AccountLines.lines(lineage, estimable(), identity, odds, series) : List.of();
            return new LineageResult(
                    d.verdict(),
                    d.shown(),
                    Verdicts.usersHit(lineage, covered, page.state()),
                    d.lost(),
                    d.low(),
                    d.high(),
                    rank,
                    groupId,
                    Verdicts.ageHit(lineage, covered, facts.asOfDay()),
                    Verdicts.tVis(lineage, covered, odds),
                    d.z(),
                    d.visible(),
                    d.pop(),
                    d.popHigh(),
                    d.contradicted(),
                    curve,
                    RetentionCurve.silentHitIds(d.verdict(), lineage, states),
                    lineage.firstEvidenceDay(),
                    used == null || first == null ? null : first.nEst(),
                    fewAccounts ? (int) Math.round(first.accEff()) : null,
                    accountLines);
        }

        /** The covered IDs that can be followed: those an estimate can enter. */
        private Set<Integer> estimable() {
            Set<Integer> out = new TreeSet<>(covered);
            out.removeAll(channel.cannotFollow());
            return out;
        }

        /** The trials of an issue that has an estimate, so evidence days, built as {@link Estimate} builds them. */
        private List<Trial> trials(LineageHits lineage, boolean dropAccounts) {
            Map<Integer, NavigableMap<Integer, Integer>> dose =
                    IssueHits.dose(facts, identity.aliasOf(), lineage.reachRoutes(), estimable());
            Map<Integer, Person> people = new TreeMap<>();
            for (int u : estimable()) {
                NavigableMap<Integer, Integer> perDay = dose.get(u);
                if (perDay == null) continue;
                people.put(
                        u,
                        new Person(
                                series.byId().get(u),
                                channel.bornInChange().contains(u),
                                odds.byId().get(u).pStop(),
                                accountOf.get(u),
                                perDay,
                                lineage.hitDays().getOrDefault(u, List.of()),
                                allEvidence.getOrDefault(u, List.of())));
            }
            int fe = Objects.requireNonNull(lineage.firstEvidenceDay());
            int le = Objects.requireNonNull(lineage.lastEvidenceDay());
            List<Trial> trials = Trials.build(people, fe, le, h, facts.asOfDay());
            return dropAccounts ? trials.stream().map(Trial::withoutAccount).toList() : trials;
        }
    }

    private static final class Timer {
        private final StageLog log;

        Timer(StageLog log) {
            this.log = log;
        }

        <T> T run(String name, Supplier<T> stage, ToLongFunction<T> rows) {
            long began = System.nanoTime();
            T out = stage.get();
            log.stage(name, rows.applyAsLong(out), (System.nanoTime() - began) / 1_000_000);
            return out;
        }
    }
}
