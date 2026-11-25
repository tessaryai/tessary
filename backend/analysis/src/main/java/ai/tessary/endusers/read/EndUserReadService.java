// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.cases.CaseRepository;
import ai.tessary.cases.CaseRow;
import ai.tessary.endusers.facts.EndUserKeys;
import ai.tessary.endusers.hits.HitRules;
import ai.tessary.endusers.hits.IssueLineages;
import ai.tessary.endusers.hits.IssueLineages.IssueLineage;
import ai.tessary.endusers.judge.model.StateResult.Banner;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.Verdict;
import ai.tessary.endusers.read.CaseHitRepository.CaseFinding;
import ai.tessary.endusers.read.CaseHitRepository.CaseHits;
import ai.tessary.endusers.read.CaseHitRepository.CaseRefs;
import ai.tessary.endusers.read.CaseHitRepository.FindingRoles;
import ai.tessary.endusers.read.CaseHitRepository.Pick;
import ai.tessary.endusers.read.EndUserDtos.EndUserBanner;
import ai.tessary.endusers.read.EndUserDtos.EndUserCaseBlock;
import ai.tessary.endusers.read.EndUserDtos.EndUserErasure;
import ai.tessary.endusers.read.EndUserDtos.EndUserHitCount;
import ai.tessary.endusers.read.EndUserDtos.EndUserHitCounts;
import ai.tessary.endusers.read.EndUserDtos.EndUserIdPage;
import ai.tessary.endusers.read.EndUserDtos.EndUserIdRow;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssue;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssueList;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssueNumbers;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssueRow;
import ai.tessary.endusers.read.EndUserDtos.EndUserOverview;
import ai.tessary.endusers.read.EndUserDtos.EndUserSilentId;
import ai.tessary.endusers.read.EndUserDtos.EndUserSilentIds;
import ai.tessary.endusers.read.EndUserEraseRepository.Erased;
import ai.tessary.endusers.read.EndUserListRepository.IdAfter;
import ai.tessary.endusers.read.EndUserListRepository.IdRow;
import ai.tessary.endusers.read.IssueReadRepository.IssueRow;
import ai.tessary.endusers.read.IssueReadRepository.SilentAfter;
import ai.tessary.endusers.read.IssueReadRepository.SilentIdRow;
import ai.tessary.endusers.read.IssueReadRepository.SilentRest;
import ai.tessary.endusers.read.SnapshotReadRepository.SnapshotRow;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.EndUserError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Users view reads, for REST and MCP alike, by project ID; the caller has checked the tenant. Reads serve the
 * newest snapshot and never run the judge. Counts of user IDs hit are live, so an erasure or a new hit shows at once;
 * the issue numbers are those of the snapshot day. A case resolves to its issue, the case lineage the judge stored.
 */
@Service
public class EndUserReadService {

    private static final Logger log = LoggerFactory.getLogger(EndUserReadService.class);

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    static final int SILENT_PAGE = 50;
    static final Duration RECENTLY_RESOLVED = Duration.ofDays(7);

    /** The verdicts that claim a loss, so the only ones that name hit user IDs. */
    private static final Set<Verdict> CLAIMS = EnumSet.of(Verdict.EVIDENCE_OF_LOSS, Verdict.FEW_ACCOUNTS);

    private static final Set<PageState> SHOWS_STAGES = EnumSet.of(PageState.OK, PageState.PARTIAL_COVERAGE);

    private final SnapshotReadRepository snapshots;
    private final IssueReadRepository issues;
    private final EndUserListRepository ids;
    private final CaseHitRepository caseHits;
    private final EndUserEraseRepository eraser;
    private final CaseRepository cases;
    private final IssueLineages lineages;
    private final HitRules rules;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public EndUserReadService(
            SnapshotReadRepository snapshots,
            IssueReadRepository issues,
            EndUserListRepository ids,
            CaseHitRepository caseHits,
            EndUserEraseRepository eraser,
            CaseRepository cases,
            IssueLineages lineages,
            HitRules rules,
            PlatformTransactionManager transactions) {
        this(snapshots, issues, ids, caseHits, eraser, cases, lineages, rules, transactions, Clock.systemUTC());
    }

    EndUserReadService(
            SnapshotReadRepository snapshots,
            IssueReadRepository issues,
            EndUserListRepository ids,
            CaseHitRepository caseHits,
            EndUserEraseRepository eraser,
            CaseRepository cases,
            IssueLineages lineages,
            HitRules rules,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.snapshots = snapshots;
        this.issues = issues;
        this.ids = ids;
        this.caseHits = caseHits;
        this.eraser = eraser;
        this.cases = cases;
        this.lineages = lineages;
        this.rules = rules;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    public EndUserOverview overview(String projectId) {
        Integer historySince = snapshots.historyStartDay(projectId).orElse(null);
        Optional<SnapshotRow> latest = snapshots.latest(projectId);
        if (latest.isEmpty()) {
            return new EndUserOverview(
                    PageState.COLLECTING,
                    null,
                    Map.of(),
                    null,
                    historySince,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    null);
        }
        SnapshotRow s = latest.get();
        StageCounts stages = s.stages();
        return new EndUserOverview(
                s.pageState(),
                s.reason(),
                s.reasonNumbers(),
                s.day(),
                historySince,
                stages == null ? null : stages.counted(),
                s.setAside(),
                stages,
                s.stopStage(),
                s.stopStageNumbers(),
                banners(projectId, s.banners()),
                s.limits(),
                s.trust());
    }

    /** One name per trace name, in route order; route names are read once for every banner. */
    private List<EndUserBanner> banners(String projectId, List<Banner> stored) {
        Set<Integer> routeNos =
                stored.stream().flatMap(b -> b.routeNos().stream()).collect(Collectors.toCollection(TreeSet::new));
        Map<Integer, String> names = routeNos.isEmpty() ? Map.of() : snapshots.routeNames(projectId, routeNos);
        return stored.stream()
                .map(b -> new EndUserBanner(
                        b.code(),
                        b.numbers(),
                        b.routeNos().stream()
                                .sorted()
                                .map(names::get)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList()))
                .toList();
    }

    public EndUserIssueList issues(String projectId) {
        Optional<SnapshotRow> latest = snapshots.latest(projectId);
        if (latest.isEmpty()) return new EndUserIssueList(PageState.COLLECTING, null, List.of());
        SnapshotRow s = latest.get();
        List<IssueRow> rows = issues.issues(projectId, s.day());
        Map<String, CaseHits> hits = liveHits(
                projectId,
                countedFindings(projectId, rows.stream().map(IssueRow::caseId).toList()),
                true);
        List<EndUserIssueRow> out = rows.stream()
                .map(r -> new EndUserIssueRow(
                        r.caseId(),
                        reference(r.caseSeq()),
                        r.title(),
                        r.detector(),
                        r.subjectLabel(),
                        sinceDay(hits.get(r.caseId())),
                        numbers(r)))
                .toList();
        return new EndUserIssueList(s.pageState(), s.day(), out);
    }

    public EndUserIssue issue(String projectId, String caseRef) {
        CaseRow c = requireCase(projectId, caseRef);
        Optional<SnapshotRow> latest = snapshots.latest(projectId);
        IssueRow row = latest.map(s ->
                        issues.forCases(projectId, s.day(), List.of(c.id())).get(c.id()))
                .orElse(null);
        Integer silentNow =
                row != null && CLAIMS.contains(row.verdict()) ? issues.silentCount(projectId, row.lineageKey()) : null;
        Map<String, CaseHits> hits =
                liveHits(projectId, countedFindings(projectId, List.of(c.id())), latest.isPresent());
        return new EndUserIssue(
                pageState(latest),
                latest.map(SnapshotRow::day).orElse(null),
                c.id(),
                c.reference(),
                c.title(),
                c.detector(),
                c.subjectLabel(),
                c.state(),
                c.resolvedAt(),
                sinceDay(hits.get(c.id())),
                silentNow,
                row == null ? null : numbers(row));
    }

    /** @param cursor the {@code next_cursor} of the previous page, else null */
    public EndUserSilentIds silentIds(String projectId, String caseRef, @Nullable String cursor) {
        CaseRow c = requireCase(projectId, caseRef);
        SilentAfter after = silentAfter(cursor);
        Optional<SnapshotRow> latest = snapshots.latest(projectId);
        if (latest.isEmpty()) return new EndUserSilentIds(null, List.of(), null, 0, null, null);
        int day = latest.get().day();
        IssueRow row = issues.forCases(projectId, day, List.of(c.id())).get(c.id());
        if (row == null) return new EndUserSilentIds(null, List.of(), null, 0, null, null);
        if (!CLAIMS.contains(row.verdict())) {
            return new EndUserSilentIds(row.verdict(), List.of(), null, 0, null, null);
        }
        List<SilentIdRow> page = issues.silentIds(projectId, row.lineageKey(), after, SILENT_PAGE + 1);
        List<SilentIdRow> shown = page.subList(0, Math.min(page.size(), SILENT_PAGE));
        String next = null;
        SilentRest rest = new SilentRest(0, null, null);
        if (page.size() > SILENT_PAGE) {
            SilentIdRow last = shown.get(shown.size() - 1);
            next = Keyset.encode(last.tie(), last.firstHitDay(), last.silentOrder());
            rest = issues.silentRest(
                    projectId, row.lineageKey(), new SilentAfter(last.firstHitDay(), last.silentOrder(), last.tie()));
        }
        Integer restLow = rest.firstHitLow();
        Integer restHigh = rest.firstHitHigh();
        return new EndUserSilentIds(
                row.verdict(),
                shown.stream()
                        .map(r -> new EndUserSilentId(
                                r.idText(), weeksSince(day, r.firstHitDay()), r.daysSilent(), r.stage(), r.grade()))
                        .toList(),
                next,
                rest.ids(),
                restHigh == null ? null : weeksSince(day, restHigh),
                restLow == null ? null : weeksSince(day, restLow));
    }

    private static int weeksSince(int day, int firstHitDay) {
        return (day - firstHitDay) / 7;
    }

    /** @param limit 1 to {@link #MAX_LIMIT} */
    public EndUserIdPage ids(String projectId, Stage stage, @Nullable String cursor, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) throw new TessaryException(CommonError.INVALID_PARAMETER, "limit");
        IdAfter after = idAfter(cursor);
        Optional<SnapshotRow> latest = snapshots.latest(projectId);
        List<IdRow> page = ids.page(projectId, stage, after, limit + 1);
        List<IdRow> shown = page.subList(0, Math.min(page.size(), limit));
        String next = null;
        if (page.size() > limit) {
            IdRow last = shown.get(shown.size() - 1);
            next = Keyset.encode(last.tie(), last.silentOrder());
        }
        return new EndUserIdPage(
                pageState(latest),
                stage,
                shown.stream()
                        .map(r -> new EndUserIdRow(
                                r.idText(), r.state(), r.substate(), r.grade(), r.daysSilent(), r.pStop(), r.hitBy()))
                        .toList(),
                next);
    }

    public EndUserCaseBlock caseBlock(String projectId, String caseRef) {
        CaseRow c = requireCase(projectId, caseRef);
        Optional<SnapshotRow> latest = snapshots.latest(projectId);
        Map<String, List<String>> counted = countedFindings(projectId, List.of(c.id()));
        List<Pick> picks = picks(projectId, counted);
        boolean metricShift = metricShift(c, picks);
        CaseHits hits = liveHits(projectId, picks, latest.isPresent()).get(c.id());
        CaseRefs refs = caseRefs(projectId, counted).get(c.id());
        IssueRow row = latest.map(s ->
                        issues.forCases(projectId, s.day(), List.of(c.id())).get(c.id()))
                .orElse(null);
        return new EndUserCaseBlock(
                c.id(),
                c.reference(),
                pageState(latest),
                countKind(metricShift, latest),
                metricShift ? null : hits == null ? 0 : hits.userIds(),
                sinceDay(hits),
                refs == null ? 0 : refs.refs(),
                refs == null ? 0 : refs.refsNoUserId(),
                refusal(latest),
                row == null ? null : numbers(row));
    }

    /** The open cases, then the cases resolved in the last 7 days, as Triage lists them. */
    public EndUserHitCounts hitCounts(String projectId) {
        List<CaseRow> listed = Stream.concat(
                        cases.listLive(projectId).stream(),
                        cases.listResolvedSince(projectId, clock.instant().minus(RECENTLY_RESOLVED)).stream())
                .toList();
        Optional<SnapshotRow> latest = snapshots.latest(projectId);
        List<String> caseIds = listed.stream().map(CaseRow::id).toList();
        List<Pick> picks = picks(projectId, countedFindings(projectId, caseIds));
        Map<String, CaseHits> hits = liveHits(projectId, picks, latest.isPresent());
        Map<String, IssueRow> issueByCase =
                latest.map(s -> issues.forCases(projectId, s.day(), caseIds)).orElse(Map.of());
        PageState refusal = refusal(latest);
        List<EndUserHitCount> out = new ArrayList<>(listed.size());
        for (CaseRow c : listed) {
            boolean metricShift = metricShift(c, picks);
            CaseHits h = hits.get(c.id());
            IssueRow row = issueByCase.get(c.id());
            boolean loss = row != null && row.verdict() == Verdict.EVIDENCE_OF_LOSS;
            out.add(new EndUserHitCount(
                    c.id(),
                    c.reference(),
                    countKind(metricShift, latest),
                    metricShift ? null : h == null ? 0 : h.userIds(),
                    refusal,
                    loss ? row.lost() : null,
                    loss ? row.low() : null,
                    loss ? row.high() : null));
        }
        return new EndUserHitCounts(out);
    }

    /** The caller has checked for a signed-in session and {@code ORG_MANAGE}. */
    public EndUserErasure erase(String projectId, String userId) {
        Instant began = Instant.now();
        if (EndUserKeys.normalise(userId) == null) {
            throw new TessaryException(CommonError.INVALID_PARAMETER, "user_id");
        }
        Erased erased = eraser.salt(projectId)
                .map(salt -> transaction.execute(tx -> eraser.erase(projectId, EndUserKeys.userKey(salt, userId))))
                .orElse(new Erased(0, 0, 0, 0, 0));
        StructuredLog.info(log, Markers.OPS, "endusers.erase")
                .message("erased %d rows of one user ID", erased.total())
                .field("project", projectId)
                .field("end_user", erased.endUser())
                .field("days", erased.days())
                .field("hits", erased.hits())
                .field("issue_hits", erased.issueHits())
                .field("alias_edges", erased.aliasEdges())
                .durationMs(began)
                .log();
        return new EndUserErasure(erased.total());
    }

    /** A case's counted findings are its lineage's; a case in no lineage, such as a false alarm, counts none. */
    private Map<String, List<String>> countedFindings(String projectId, Collection<String> caseIds) {
        Set<String> asked = Set.copyOf(caseIds);
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (IssueLineage lineage : lineages.lineages(projectId)) {
            for (String caseId : lineage.caseIds()) {
                if (asked.contains(caseId)) out.put(caseId, lineage.findingIds());
            }
        }
        return out;
    }

    private Map<String, CaseHits> liveHits(String projectId, Map<String, List<String>> counted, boolean coveredOnly) {
        return liveHits(projectId, picks(projectId, counted), coveredOnly);
    }

    /** The finding roles that count as hits for each case: those with a stored row under the finding's hit rule. */
    private List<Pick> picks(String projectId, Map<String, List<String>> counted) {
        Set<String> findingIds = counted.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        if (findingIds.isEmpty()) return List.of();
        Map<String, Set<String>> hitRoles = new HashMap<>();
        for (FindingRoles f : caseHits.findingRoles(projectId, findingIds)) {
            hitRoles.put(f.findingId(), rules.ruleFor(f.finding()).hitRoles(f.roles()));
        }
        List<Pick> picks = new ArrayList<>();
        counted.forEach((caseId, findings) -> {
            for (String findingId : findings) {
                for (String role : hitRoles.getOrDefault(findingId, Set.of())) {
                    picks.add(new Pick(caseId, findingId, role));
                }
            }
        });
        return picks;
    }

    private Map<String, CaseHits> liveHits(String projectId, List<Pick> picks, boolean coveredOnly) {
        if (picks.isEmpty()) return Map.of();
        return caseHits.caseHits(projectId, picks, coveredOnly).stream()
                .collect(Collectors.toMap(CaseHits::caseId, h -> h));
    }

    private Map<String, CaseRefs> caseRefs(String projectId, Map<String, List<String>> counted) {
        List<CaseFinding> pairs = new ArrayList<>();
        counted.forEach((caseId, findings) -> findings.forEach(f -> pairs.add(new CaseFinding(caseId, f))));
        if (pairs.isEmpty()) return Map.of();
        return caseHits.caseRefs(projectId, pairs).stream().collect(Collectors.toMap(CaseRefs::caseId, r -> r));
    }

    /**
     * A metric shift is measured like every other issue; only one with no stored hit row, with or without a user ID,
     * has no user IDs hit to count.
     */
    private static boolean metricShift(CaseRow c, List<Pick> picks) {
        return CaseRow.Detector.METRIC_DRIFT.equals(c.detector())
                && picks.stream().noneMatch(p -> p.caseId().equals(c.id()));
    }

    /** By ULID or by reference ({@code C-118}), within the project only. */
    private CaseRow requireCase(String projectId, String caseRef) {
        Optional<CaseRow> byId = cases.findById(projectId, caseRef);
        if (byId.isPresent()) return byId.get();
        Long seq = sequenceOf(caseRef);
        return (seq == null ? Optional.<CaseRow>empty() : cases.findBySeq(projectId, seq))
                .orElseThrow(() -> new TessaryException(EndUserError.NOT_FOUND));
    }

    private static @Nullable Long sequenceOf(String reference) {
        String digits = reference.startsWith("C-") || reference.startsWith("c-") ? reference.substring(2) : reference;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable SilentAfter silentAfter(@Nullable String cursor) {
        if (cursor == null) return null;
        String[] parts = Keyset.decode(cursor, 2);
        return new SilentAfter(Keyset.number(parts[0]), Keyset.number(parts[1]), parts[2]);
    }

    private static @Nullable IdAfter idAfter(@Nullable String cursor) {
        if (cursor == null) return null;
        String[] parts = Keyset.decode(cursor, 1);
        return new IdAfter(Keyset.number(parts[0]), parts[1]);
    }

    private static EndUserIssueNumbers numbers(IssueRow r) {
        return new EndUserIssueNumbers(
                r.verdict(),
                r.shown(),
                r.rank(),
                r.usersHit(),
                r.compared(),
                r.lost(),
                r.low(),
                r.high(),
                r.groupId(),
                r.diagnostics(),
                r.curve());
    }

    private static PageState pageState(Optional<SnapshotRow> latest) {
        return latest.map(SnapshotRow::pageState).orElse(PageState.COLLECTING);
    }

    /** The page state when the page shows no stage numbers, {@code collecting} before the first snapshot included. */
    private static @Nullable PageState refusal(Optional<SnapshotRow> latest) {
        PageState state = pageState(latest);
        return SHOWS_STAGES.contains(state) ? null : state;
    }

    private static CountKind countKind(boolean metricShift, Optional<SnapshotRow> latest) {
        if (metricShift) return CountKind.NOT_APPLICABLE;
        return latest.isPresent() ? CountKind.COVERED : CountKind.ID_VALUES;
    }

    private static @Nullable Integer sinceDay(@Nullable CaseHits hits) {
        return hits == null ? null : hits.sinceDay();
    }

    private static String reference(long seq) {
        return "C-" + seq;
    }
}
