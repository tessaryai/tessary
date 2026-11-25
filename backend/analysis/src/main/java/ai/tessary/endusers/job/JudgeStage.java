// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import ai.tessary.config.EndUsersProperties;
import ai.tessary.endusers.facts.EndUserProjectRepository;
import ai.tessary.endusers.facts.RouteClassRepository;
import ai.tessary.endusers.job.FactsLoadRepository.Loaded;
import ai.tessary.endusers.judge.EndUserJudge;
import ai.tessary.endusers.judge.EndUserJudge.Judgment;
import ai.tessary.endusers.judge.Verdicts.Decision;
import ai.tessary.endusers.judge.model.Facts.IssueInput;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.IssueEstimate.LineageEstimate;
import ai.tessary.endusers.judge.model.IssueResult.LineageResult;
import ai.tessary.endusers.judge.model.PageStateResult;
import ai.tessary.endusers.judge.model.Scope;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.StateResult;
import ai.tessary.endusers.judge.model.StateResult.Banner;
import ai.tessary.endusers.judge.model.StateResult.IdState;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.model.BannerCode;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.read.IssueDiagnostics;
import ai.tessary.endusers.read.SnapshotWriteRepository;
import ai.tessary.endusers.read.SnapshotWriteRepository.Issue;
import ai.tessary.endusers.read.SnapshotWriteRepository.IssueHit;
import ai.tessary.endusers.read.SnapshotWriteRepository.Run;
import ai.tessary.endusers.read.SnapshotWriteRepository.SilenceEvent;
import ai.tessary.endusers.read.SnapshotWriteRepository.Snapshot;
import ai.tessary.endusers.read.SnapshotWriteRepository.UserState;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stage 4 of the daily job (plan 3.2): judges the facts of the {@code judge-window-days} days that end on the job's
 * day and writes the snapshot. Above the size budget the snapshot is {@code not_computed}; a window with no user-ID day
 * gets its page state from the null-day rows alone. Everything is computed before the one transaction that writes the
 * snapshot, replaces the last run's rows and remembers the page state, so a run that fails leaves the last snapshot
 * served. A project whose history has not started gets no snapshot and stays {@code collecting}.
 */
@Component
public class JudgeStage implements EndUsersStage {

    private static final Logger log = LoggerFactory.getLogger(JudgeStage.class);

    private final FactsLoadRepository facts;
    private final SnapshotWriteRepository snapshots;
    private final EndUserProjectRepository projects;
    private final RouteClassRepository routes;
    private final EndUsersProperties props;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public JudgeStage(
            FactsLoadRepository facts,
            SnapshotWriteRepository snapshots,
            EndUserProjectRepository projects,
            RouteClassRepository routes,
            EndUsersProperties props,
            PlatformTransactionManager transactions) {
        this.facts = facts;
        this.snapshots = snapshots;
        this.projects = projects;
        this.routes = routes;
        this.props = props;
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.writes = new TransactionTemplate(transactions);
    }

    @Override
    public String name() {
        return "judge";
    }

    @Override
    public long run(String projectId, int day) {
        Instant began = Instant.now();
        EndUserProjectRepository.State state = projects.state(projectId);
        Integer t0 = state.historyStartDay();
        if (t0 == null) {
            logRun(projectId, day, null, 0, 0, began);
            return 0;
        }
        int fromDay = day - props.judgeWindowDays() + 1;
        long idDays = facts.idDays(projectId, fromDay, day);
        if (EndUserJudge.overBudget(idDays, props.maxIdDays())) {
            long rows = write(projectId, refused(day, PageState.NOT_COMPUTED, null, Map.of(), List.of()), null);
            logRun(projectId, day, PageState.NOT_COMPUTED, idDays, rows, began);
            return rows;
        }
        if (idDays == 0) {
            PageStateResult page =
                    EndUserJudge.withoutIds(day, t0, facts.nullDays(projectId, fromDay, day), routes.stored(projectId));
            long rows = write(
                    projectId,
                    refused(
                            day,
                            page.state(),
                            page.reason(),
                            page.reasonNumbers(),
                            List.of(new Banner(BannerCode.PAGE_REFUSAL, page.reasonNumbers()))),
                    page.ruleState());
            logRun(projectId, day, page.state(), 0, rows, began);
            return rows;
        }
        Loaded loaded = Objects.requireNonNull(
                reads.execute(tx -> facts.load(projectId, day, fromDay, t0, state.pageStateMemory())));
        Judgment judgment = EndUserJudge.judge(
                loaded.facts(),
                guards(props.guards()),
                (stage, rows, durationMs) -> StructuredLog.info(log, Markers.OPS, "endusers.judge.stage")
                        .message("judge stage %s gave %d rows in %d ms", stage, rows, durationMs)
                        .field("project", projectId)
                        .field("day", day)
                        .field("stage", stage)
                        .field("rows", rows)
                        .field("durationMs", durationMs)
                        .log());
        long rows = write(projectId, run(day, loaded, judgment), judgment.page().ruleState());
        logRun(projectId, day, judgment.page().state(), idDays, rows, began);
        return rows;
    }

    /**
     * Writes one run in one transaction: the page memory first and the shared-silence events last.
     *
     * @param remember the rule state to remember; null for a run that computed none
     */
    long write(String projectId, Run run, @Nullable PageState remember) {
        return Objects.requireNonNull(writes.execute(tx -> {
            if (remember != null) projects.rememberPageState(projectId, remember);
            return snapshots.write(projectId, run);
        }));
    }

    static Guards guards(EndUsersProperties.Guards keys) {
        return new Guards(
                keys.notJudgedWithoutPastCheck(),
                keys.cannotTellYet(),
                keys.historyTooShort(),
                keys.hitsNotComplete(),
                keys.mixedIdFormats(),
                keys.findingsFireTogether(),
                keys.outageBanner());
    }

    /** A snapshot with a page state and no number; it replaces the last run's issues, states and events with none. */
    private static Run refused(
            int day,
            PageState state,
            @Nullable PageStateReason reason,
            Map<String, Double> numbers,
            List<Banner> banners) {
        return new Run(
                new Snapshot(
                        day,
                        EndUserJudge.LOGIC_VERSION,
                        state,
                        reason,
                        numbers,
                        banners,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static Run run(int day, Loaded loaded, Judgment j) {
        PageStateResult page = j.page();
        StateResult states = j.states();
        StopOdds odds = j.odds();
        int h = j.estimate().h();
        Snapshot snapshot = new Snapshot(
                day,
                EndUserJudge.LOGIC_VERSION,
                page.state(),
                page.reason(),
                page.reasonNumbers(),
                states.banners(),
                states.limits(),
                states.trust(),
                states.stages(),
                states.setAside(),
                states.stopStage(),
                states.stopStage() == null ? null : states.stopStageNumbers(),
                odds.calibration(),
                h,
                odds.f());
        List<Issue> issues = new ArrayList<>();
        List<IssueHit> issueHits = new ArrayList<>();
        for (IssueInput input : loaded.facts().issues()) {
            String key = input.lineageKey();
            LineageResult r = Objects.requireNonNull(j.issues().byLineage().get(key));
            Decision d = Objects.requireNonNull(j.decisions().get(key));
            LineageEstimate first = j.estimate().byLineage().get(key);
            LineageEstimate used = d.used();
            issues.add(new Issue(
                    key,
                    input.caseId(),
                    input.findingIds(),
                    r.rank(),
                    r.usersHit(),
                    d.compared(),
                    r.lost(),
                    r.low(),
                    r.high(),
                    r.verdict(),
                    r.shown(),
                    r.groupId(),
                    r.curve().isEmpty() ? null : r.curve(),
                    diagnostics(r, d, first, used, h)));
            Objects.requireNonNull(j.hits().byLineage().get(key))
                    .hitDays()
                    .forEach((u, days) -> issueHits.add(new IssueHit(
                            key,
                            loaded.keys().get(u),
                            days.getFirst(),
                            first != null && first.estimateIds().contains(u))));
        }
        return new Run(snapshot, issues, issueHits, userStates(loaded, j), silence(j.silence()));
    }

    /** Null where the verdict computed none: the estimate's numbers only when the verdict reached the estimate. */
    private static IssueDiagnostics diagnostics(
            LineageResult r, Decision d, @Nullable LineageEstimate first, @Nullable LineageEstimate used, int h) {
        boolean estimated = used != null && first != null;
        return new IssueDiagnostics(
                estimated ? used.sumLo() : null,
                estimated ? used.expLo() : null,
                d.upper(),
                estimated ? h : null,
                estimated ? first.lateHits() : null,
                estimated ? used.unmatched() : null,
                estimated ? r.tVis() : null,
                d.popHigh(),
                r.firstEvidenceDay(),
                r.estimated(),
                r.accounts(),
                r.accountLines().isEmpty() ? null : r.accountLines());
    }

    /**
     * Every judged ID, and each alias member with its representative's state and set-aside reason and no stage, so it
     * is counted once and a hit on it counts only when its unit is covered.
     */
    private static List<UserState> userStates(Loaded loaded, Judgment j) {
        List<UserState> out = new ArrayList<>();
        Map<Integer, IdState> byId = j.states().byId();
        byId.forEach(
                (u, s) -> out.add(state(loaded.keys().get(u), s, j.odds().byId().get(u), s.setAside(), s.stage())));
        j.identity().aliasOf().forEach((member, rep) -> {
            IdState s = Objects.requireNonNull(byId.get(rep));
            out.add(state(loaded.keys().get(member), s, j.odds().byId().get(rep), s.setAside(), null));
        });
        return out;
    }

    private static UserState state(
            byte[] key, IdState s, @Nullable IdOdds odds, @Nullable SetAsideReason setAside, @Nullable Stage stage) {
        return new UserState(
                key,
                s.state(),
                s.substate(),
                s.grade(),
                odds == null ? null : odds.pStop(),
                odds == null ? null : odds.pNominal(),
                s.daysSilent(),
                setAside,
                stage);
    }

    private static List<SilenceEvent> silence(SilenceResult silence) {
        return silence.events().stream()
                .map(e -> new SilenceEvent(
                        scope(e.scope()),
                        e.startDay(),
                        e.endDay(),
                        e.state(),
                        e.members().size(),
                        e.i(),
                        e.i0(),
                        e.rho()))
                .toList();
    }

    private static String scope(Scope scope) {
        String group = scope.group();
        return group == null ? scope.kind().wire() : scope.kind().wire() + ":" + group;
    }

    private static void logRun(
            String projectId, int day, @Nullable PageState state, long idDays, long rows, Instant began) {
        StructuredLog.info(log, Markers.OPS, "endusers.judge")
                .message("judged %d user-ID days into page state %s; wrote %d rows", idDays, state, rows)
                .field("project", projectId)
                .field("day", day)
                .field("pageState", state == null ? null : state.wire())
                .field("idDays", idDays)
                .field("rows", rows)
                .durationMs(began)
                .log();
    }
}
