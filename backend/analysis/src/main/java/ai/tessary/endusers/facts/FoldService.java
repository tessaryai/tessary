// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.retention.RetentionResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stage 1 of the daily job: folds closed UTC days of settled traces into fact rows (plan 3.2). A run folds the last 7
 * closed days, every day after the last fold, and each older live day that holds a trace created or rolled up since the
 * last run; the first run backfills from the first live trace day. Each day is replaced whole in one transaction. Days
 * that aged out of the trace TTL before any run folded them are marked as gaps.
 */
@Service
public class FoldService {

    private static final Logger log = LoggerFactory.getLogger(FoldService.class);

    /** Writers commit late, so a run re-reads this far behind the last run's start. */
    static final Duration LOOKBACK = Duration.ofHours(2);

    /** Alias edges read the session or thread this many days on each side of the folded day. */
    static final int GROUP_DAYS = 7;

    private static final Comparator<@Nullable String> NULLS_FIRST =
            Comparator.nullsFirst(Comparator.<String>naturalOrder());

    private final FoldTraceRepository traces;
    private final DayFactsRepository dayFacts;
    private final RouteRegistryRepository registry;
    private final EndUserProjectRepository projects;
    private final RetentionResolver retention;
    private final TransactionTemplate dayTransaction;
    private final Clock clock;

    @Autowired
    public FoldService(
            FoldTraceRepository traces,
            DayFactsRepository dayFacts,
            RouteRegistryRepository registry,
            EndUserProjectRepository projects,
            RetentionResolver retention,
            PlatformTransactionManager transactions) {
        this(traces, dayFacts, registry, projects, retention, transactions, Clock.systemUTC());
    }

    FoldService(
            FoldTraceRepository traces,
            DayFactsRepository dayFacts,
            RouteRegistryRepository registry,
            EndUserProjectRepository projects,
            RetentionResolver retention,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.traces = traces;
        this.dayFacts = dayFacts;
        this.registry = registry;
        this.projects = projects;
        this.retention = retention;
        this.dayTransaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    public FoldResult fold(String projectId) {
        Instant began = Instant.now();
        Instant runStart = clock.instant();
        int today = EndUserKeys.dayIndex(runStart);
        Integer oldest = FoldPlan.oldestReplaceableDay(today, traceTtlDays(projectId));
        EndUserProjectRepository.State state = projects.state(projectId);
        byte[] salt = projects.salt(projectId);
        Optional<Integer> lastFolded = dayFacts.lastFoldedDay(projectId);
        Integer startDay = lastFolded
                .map(d -> d + 1)
                .orElseGet(() -> traces.firstTraceDay(projectId, oldest).orElse(null));
        Set<Integer> changed = changedDays(projectId, state, oldest, today);
        List<Integer> days = FoldPlan.days(today, oldest, startDay, changed);
        List<Integer> gaps = FoldPlan.gapDays(lastFolded.orElse(null), oldest);
        dayFacts.markGaps(projectId, gaps, runStart);

        int traceCount = 0;
        int idDays = 0;
        int edges = 0;
        int users = 0;
        int outages = 0;
        Integer firstWithTraces = null;
        for (int day : days) {
            Instant dayBegan = Instant.now();
            DayFacts facts = foldDay(projectId, day, salt);
            boolean outage = write(projectId, facts, runStart);
            traceCount += facts.traces();
            idDays += facts.idDays().size();
            edges += facts.aliasEdges().size();
            users += facts.users().size();
            outages += outage ? 1 : 0;
            if (firstWithTraces == null && facts.traces() > 0) firstWithTraces = day;
            StructuredLog.debug(log, "endusers.fold.day")
                    .message(
                            "folded day %d: %d traces, %d ID-day rows, outage %s",
                            day, facts.traces(), facts.idDays().size(), outage)
                    .field("project", projectId)
                    .field("day", day)
                    .field("traces", facts.traces())
                    .field("idDays", facts.idDays().size())
                    .field("outage", outage)
                    .durationMs(dayBegan)
                    .log();
        }
        if (firstWithTraces != null) projects.fixHistoryStartDay(projectId, firstWithTraces);
        projects.setFoldWatermarks(projectId, runStart, runStart);

        FoldResult result = new FoldResult(days.size(), traceCount, idDays, edges, users, gaps.size(), outages);
        StructuredLog.info(log, Markers.OPS, "endusers.fold")
                .message(
                        "folded %d days (%d traces, %d ID-day rows, %d alias edges), %d gap days, %d outage days",
                        result.days(),
                        result.traces(),
                        result.idDays(),
                        result.aliasEdges(),
                        result.gapDays(),
                        result.outageDays())
                .field("project", projectId)
                .field("days", result.days())
                .field("traces", result.traces())
                .field("idDays", result.idDays())
                .field("aliasEdges", result.aliasEdges())
                .field("users", result.users())
                .field("gapDays", result.gapDays())
                .field("outageDays", result.outageDays())
                .durationMs(began)
                .log();
        return result;
    }

    private Set<Integer> changedDays(
            String projectId, EndUserProjectRepository.State state, @Nullable Integer oldest, int today) {
        Instant created = state.foldCreatedWatermark();
        if (created == null) return Set.of();
        Instant rolledUp = Objects.requireNonNullElse(state.foldRollupWatermark(), created);
        return traces.changedDays(
                projectId, oldest, today - FoldPlan.REFOLD_DAYS, created.minus(LOOKBACK), rolledUp.minus(LOOKBACK));
    }

    private DayFacts foldDay(String projectId, int day, byte[] salt) {
        List<FoldTrace> dayTraces = traces.dayTraces(projectId, day);
        Set<String> sessions = new TreeSet<>();
        Set<String> threads = new TreeSet<>();
        Map<Route, Integer> routes = new TreeMap<>();
        Map<String, Integer> versions = new TreeMap<>(NULLS_FIRST);
        for (FoldTrace t : dayTraces) {
            routes.put(Route.of(t), 0);
            versions.put(t.projectVersionId(), 0);
            if (EndUserKeys.normalise(t.userId()) == null) continue;
            if (t.sessionId() != null) sessions.add(t.sessionId());
            if (t.threadId() != null) threads.add(t.threadId());
        }
        routes.replaceAll((route, no) -> registry.routeNo(projectId, route.name(), route.callSiteId()));
        versions.replaceAll((version, no) -> registry.versionNo(projectId, version, day));
        List<FoldTrace> groupTraces =
                traces.groupTraces(projectId, sessions, threads, day - GROUP_DAYS, day + GROUP_DAYS + 1);
        return FoldFacts.build(
                day,
                dayTraces,
                groupTraces,
                salt,
                t -> Objects.requireNonNull(routes.get(Route.of(t))),
                t -> Objects.requireNonNull(versions.get(t.projectVersionId())));
    }

    /** Replaces the day and writes its fold row in one transaction; returns the outage flag. */
    private boolean write(String projectId, DayFacts facts, Instant foldedAt) {
        Boolean outage = dayTransaction.execute(tx -> {
            dayFacts.replaceDay(projectId, facts);
            boolean down = FoldOutage.outage(
                    facts.tracesByRoute(), dayFacts.routeTracesBefore(projectId, facts.day(), FoldOutage.MEDIAN_DAYS));
            dayFacts.writeFoldDay(projectId, facts.day(), foldedAt, facts.traces(), down);
            return down;
        });
        return Boolean.TRUE.equals(outage);
    }

    private int traceTtlDays(String projectId) {
        return retention.resolve(projectId).stream()
                .filter(r -> r.dataClass() == RetentionResolver.DataClass.TRACES)
                .findFirst()
                .orElseThrow()
                .ttlDays();
    }

    /** A route as the registry keys it: a NULL call site is {@link RouteRegistryRepository#UNATTRIBUTED}. */
    private record Route(@Nullable String name, String callSiteId) implements Comparable<Route> {

        static Route of(FoldTrace t) {
            return new Route(t.name(), t.callSiteId() == null ? RouteRegistryRepository.UNATTRIBUTED : t.callSiteId());
        }

        @Override
        public int compareTo(Route o) {
            int byName = NULLS_FIRST.compare(name, o.name);
            return byName != 0 ? byName : callSiteId.compareTo(o.callSiteId);
        }
    }
}
