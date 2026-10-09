// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.chart.ClassifierChartDtos.ArmingView;
import ai.tessary.classifier.chart.ClassifierChartDtos.CaseSpan;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartCard;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartPoint;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartsView;
import ai.tessary.classifier.chart.ClassifierChartDtos.HeadlineView;
import ai.tessary.classifier.chart.ClassifierChartRepository.ClassifierOnCallSite;
import ai.tessary.classifier.chart.ClassifierChartRepository.CountRow;
import ai.tessary.classifier.chart.ClassifierChartRepository.RangeRow;
import ai.tessary.classifier.chart.ClassifierChartRepository.RateRow;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository;
import ai.tessary.classifier.detector.groundedness.GroundednessStatus;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.frustration.FrustrationRateRepository;
import ai.tessary.classifier.frustration.JevFrustrationQuestion;
import ai.tessary.classifier.malformed.MalformedOutputRateRepository;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository;
import ai.tessary.classifier.toolerror.ToolErrorRepository;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.RateClassifierFixture;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.ToolCallTurn;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The chart series against Postgres: UTC hours, the sweep cursor, a tool key's raw names, the root span's own
 * duration on the drift grid, priced turns only, the arming band, call site and window, and the case strip and
 * selector counts.
 */
@SpringBootTest
class ClassifierChartRepositoryIntegrationTest {

    private static final String VERSION =
            JevFrustrationQuestion.scorerVersion(JevFrustrationQuestion.DEFAULT_THRESHOLD);
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final Instant FROM = Instant.parse("2026-09-11T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-09T00:00:00Z");

    @Autowired
    ClassifierChartRepository charts;

    @Autowired
    RateClassifierFixture fixture;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    ClassifierService classifierService;

    @Autowired
    ClassifierRepository classifierRows;

    @Autowired
    FrustrationRateRepository frustrationRates;

    @Autowired
    GroundednessRateRepository groundednessRates;

    @Autowired
    GroundednessStatus groundednessStatus;

    @Autowired
    MalformedOutputRateRepository malformedRates;

    @Autowired
    ToolErrorRepository toolErrors;

    @Autowired
    ToolErrorStateRepository toolErrorStates;

    @Autowired
    ToolErrorReferenceRepository toolErrorReferences;

    @Autowired
    MetricBaselineRepository baselines;

    @Autowired
    FindingRepository findings;

    @Autowired
    ClassifierJobRepository jobs;

    @Autowired
    SubstrateReadRepository substrate;

    @Autowired
    ObjectMapper mapper;

    private SubstrateV2Fixtures fx;
    private ClassifierChartService service;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
        service = new ClassifierChartService(
                classifierService,
                charts,
                frustrationRates,
                groundednessRates,
                groundednessStatus,
                malformedRates,
                toolErrors,
                toolErrorStates,
                toolErrorReferences,
                baselines,
                findings,
                jobs,
                substrate,
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * A zoneless hour cut reads the session's zone: in India, five and a half hours east of UTC, 23:30 UTC is 05:00
     * local and would start its own hour.
     */
    @Test
    @Transactional
    void rateHour_bucketsInUtc() {
        String pid = project("chart-utc", Capability.FRUSTRATION);
        ClassifierRow signal = fixture.builtIn(pid, BuiltInDetector.Kind.FRUSTRATION);
        fixture.frustrationAssessment(
                pid, signal, "t-late", "conv-late", "cs-a", Instant.parse("2026-10-05T23:30:00Z"), false, VERSION);
        jdbc.sql("SET LOCAL TIME ZONE 'Asia/Kolkata'").update();

        List<RateRow> hours = charts.frustrationHours(pid, signal.id(), VERSION, "cs-a", FROM, FROM);

        assertEquals(List.of(new RateRow(Instant.parse("2026-10-05T23:00:00Z"), 1, 0)), hours);
    }

    /**
     * Grouped from the range's start, a session that began before a short range is a new trial at its first turn
     * inside it, so the last seven days of a 7-day chart and a 28-day chart would disagree.
     */
    @Test
    void frustrationHeadline_isTheSameAtSevenAndTwentyEightDays() {
        String pid = project("chart-cross", Capability.FRUSTRATION);
        ClassifierRow signal = enable(fixture.builtIn(pid, BuiltInDetector.Kind.FRUSTRATION));
        declare(pid, "cs-a", null);
        Instant crossing = Instant.parse("2026-09-30T10:00:00Z"); // before the 7-day range, inside the 28-day one
        fixture.frustrationAssessment(pid, signal, "t-1a", "conv-1", "cs-a", crossing, false, VERSION);
        Instant flaggedAt = Instant.parse("2026-10-06T10:00:00Z");
        fixture.frustrationAssessment(pid, signal, "t-1b", "conv-1", "cs-a", flaggedAt, true, VERSION);
        frustrationFlag(pid, signal, "t-1b", "conv-1", "cs-a", flaggedAt);
        fixture.frustrationAssessment(
                pid, signal, "t-2", "conv-2", "cs-a", Instant.parse("2026-10-05T10:00:00Z"), false, VERSION);

        ChartCard week = card(service.charts(pid, "cs-a", null, 7), BuiltInDetector.Kind.FRUSTRATION);
        ChartCard month = card(service.charts(pid, "cs-a", null, 28), BuiltInDetector.Kind.FRUSTRATION);

        assertEquals(0.0, week.headline().value(), "conv-1 is a trial of Sep 30, before the headline's week");
        assertEquals(week.headline(), month.headline());
        Instant oct5 = Instant.parse("2026-10-05T10:00:00Z");
        assertEquals(
                List.of(
                        ChartPoint.rate(crossing, crossing.plusSeconds(3600), false, 1, 1),
                        ChartPoint.rate(oct5, oct5.plusSeconds(3600), false, 1, 0)),
                month.points(),
                "Oct 5 would stretch the Sep 30 point past a day, so it starts its own; a later turn is not a trial;"
                        + " Oct 5 is days before now, so no hour can still join it");
    }

    /** The sweep walks the ingest clock: a span stored after its cursor was never checked, whatever its start. */
    @Test
    void malformedDays_ignoreSpansPastTheSweepCursor() {
        String pid = project("chart-cursor", Capability.MALFORMED_OUTPUT);
        ClassifierRow signal = fixture.builtIn(pid, BuiltInDetector.Kind.MALFORMED_OUTPUT);
        declare(pid, "cs-a", "{\"type\":\"object\"}");
        Instant started = Instant.parse("2026-10-01T10:00:00Z");
        var checked = output(pid, "cs-a", started);
        var backfilled = output(pid, "cs-a", started.plusSeconds(60));
        fx.ingestedAt(checked, Instant.parse("2026-10-02T00:00:00Z"));
        fx.ingestedAt(backfilled, Instant.parse("2026-10-07T00:00:00Z"));

        List<RateRow> hours = charts.malformedHours(pid, signal.id(), "cs-a", FROM, "2026-10-05T00:00:00Z");

        assertEquals(List.of(new RateRow(Instant.parse("2026-10-01T10:00:00Z"), 1, 0)), hours);
    }

    /** A tool key folds names; matching it in SQL against one spelling drops the others' calls. */
    @Test
    void toolErrorDays_countEveryRawNameOfTheKey() {
        String pid = project("chart-tool-names", Capability.TOOL_ERROR);
        enable(fixture.builtIn(pid, BuiltInDetector.Kind.TOOL_ERROR));
        Instant at = Instant.parse("2026-10-06T10:00:00Z");
        fx.toolCallTurns(
                pid,
                "Search_Orders",
                List.of(new ToolCallTurn(at, "Timeout", null, null), new ToolCallTurn(at, null, null, null)));
        fx.toolCallTurns(pid, "search_orders", List.of(new ToolCallTurn(at.plusSeconds(5), null, null, null)));

        ChartCard card = card(service.charts(pid, null, "tool:search_orders", 7), BuiltInDetector.Kind.TOOL_ERROR);

        assertEquals(
                List.of(ChartPoint.rate(at, at.plusSeconds(3600), false, 3, 1)),
                card.points(),
                "more than a day before now, so it is no longer filling");
    }

    /** A child that ends after its root stretches {@code trace.latency_ms}; the turn is the root's own interval. */
    @Test
    void turnDuration_usesRootSpanNotTraceLatency() {
        String pid = project("chart-turn", Capability.DURATION_DRIFT);
        Instant at = Instant.parse("2026-10-06T10:00:00Z");
        String slow = turn(pid, "cs-a", at, 1_000);
        fx.spanSeed(pid)
                .traceId(slow)
                .parentSpanId(rootOf(pid, slow))
                .kind("tool")
                .at(at)
                .endedAt(at.plusMillis(5_000))
                .write();
        fx.rollup(pid, slow);
        turn(pid, "cs-a", at.plusSeconds(60), 2_000);

        List<RangeRow> hours = charts.turnDurationHours(pid, "cs-a", FROM, Grid.duration());

        // On the 1 ms, 5% grid, 1000 ms is bin floor(ln 1000 / ln 1.05) = 141 and 2000 ms is 155. Over trace latency
        // the slow turn would read 5000 ms, bin 174.
        assertEquals(Set.of(new RangeRow(at, 141, 1), new RangeRow(at, 155, 1)), Set.copyOf(hours));
        // percentile_cont over {1000, 2000}: p95 = 1000 + 0.95 * 1000. The pooled headline stays exact.
        assertEquals(1_950.0, charts.turnDurationP95(pid, "cs-a", FROM));
    }

    /** {@code ln} of zero raises in Postgres, and a value past the grid has no bin of its own: both clamp. */
    @Test
    void turnDurationHours_clampZeroAndOverflowIntoTheEdgeBins() {
        String pid = project("chart-turn-edges", Capability.DURATION_DRIFT);
        Instant at = Instant.parse("2026-10-06T10:00:00Z");
        turn(pid, "cs-a", at, 0);
        turn(pid, "cs-a", at.plusSeconds(60), 10L * 3_600_000); // 1.05^320 ms is about 1.7 hours

        List<RangeRow> hours = charts.turnDurationHours(pid, "cs-a", FROM, Grid.duration());

        assertEquals(Set.of(new RangeRow(at, 0, 1), new RangeRow(at, 319, 1)), Set.copyOf(hours));
    }

    /**
     * A tool span is binned on the same grid, 250 ms in bin floor(ln 250 / ln 1.05) = 113, and a classifier limited to
     * other call sites reads none of it.
     */
    @Test
    void toolDurationHours_binTheSpanOnTheGridWithinTheClassifiersCallSites() {
        String pid = project("chart-tool-duration", Capability.DURATION_DRIFT);
        Instant at = Instant.parse("2026-10-06T10:00:00Z");
        String trace = turn(pid, "cs-a", at, 1_000);
        fx.spanSeed(pid)
                .traceId(trace)
                .parentSpanId(rootOf(pid, trace))
                .kind("tool")
                .name("search")
                .at(at)
                .endedAt(at.plusMillis(250))
                .write();
        fx.rollup(pid, trace);

        assertEquals(
                List.of(new RangeRow(at, 113, 1)),
                charts.toolDurationHours(pid, List.of("search"), null, FROM, Grid.duration()));
        assertEquals(
                List.of(), charts.toolDurationHours(pid, List.of("search"), List.of("cs-b"), FROM, Grid.duration()));
    }

    /** A turn with an unpriced span reports the price of the others and reads cheap; it is not a sample. */
    @Test
    void costDays_dropTracesWithUnpricedSpans() {
        String pid = project("chart-cost", Capability.COST_DRIFT);
        Instant at = Instant.parse("2026-10-06T10:00:00Z");
        cost(turn(pid, "cs-a", at, 100), "0.02", 0);
        cost(turn(pid, "cs-a", at.plusSeconds(1), 100), "0.01", 1);
        cost(turn(pid, "cs-a", at.plusSeconds(2), 100), null, 0);

        // On the $0.00001, 5% grid, $0.02 is bin floor(ln 2000 / ln 1.05) = 155.
        assertEquals(List.of(new RangeRow(at, 155, 1)), charts.costHours(pid, "cs-a", FROM, Grid.cost()));
    }

    /**
     * Secret Leak's bar counts the HIGH band, per call site and per pattern: a low-confidence match, a match on
     * another call site and a second pattern do not add to the busiest pattern's count. Every match on the call site
     * is in the total.
     */
    @Test
    void countDays_respectHighBandAndCallSite() {
        String pid = project("chart-count", Capability.SECRET_LEAK);
        ClassifierRow signal = fixture.builtIn(pid, BuiltInDetector.Kind.SECRET_LEAK);
        Instant at = Instant.parse("2026-10-06T10:00:00Z");
        leak(pid, signal, "cs-a", "aws", "high", "sess-1", at);
        leak(pid, signal, "cs-a", "aws", "high", "sess-2", at.plusSeconds(1));
        leak(pid, signal, "cs-a", "github", "high", "sess-1", at.plusSeconds(2));
        leak(pid, signal, "cs-a", "aws", "low", "sess-3", at.plusSeconds(3));
        leak(pid, signal, "cs-b", "aws", "high", "sess-4", at.plusSeconds(4));

        List<CountRow> hours = charts.countBuckets(
                BuiltInDetector.Kind.SECRET_LEAK, pid, signal.id(), "cs-a", FROM, 3600, true, "pattern");

        assertEquals(List.of(new CountRow(Instant.parse("2026-10-06T10:00:00Z"), 2, 2, 4)), hours);
    }

    /**
     * A session seen in two hours is one session in their day: hourly distinct counts summed into the bar's window
     * would read two and could mark a window reached that the bar never counted.
     */
    @Test
    void countBuckets_countSessionsOncePerBucket() {
        String pid = project("chart-count-grain", Capability.SECRET_LEAK);
        ClassifierRow signal = fixture.builtIn(pid, BuiltInDetector.Kind.SECRET_LEAK);
        leak(pid, signal, "cs-a", "aws", "high", "sess-1", Instant.parse("2026-10-06T10:00:00Z"));
        leak(pid, signal, "cs-a", "aws", "high", "sess-1", Instant.parse("2026-10-06T11:30:00Z"));

        List<CountRow> hours = charts.countBuckets(
                BuiltInDetector.Kind.SECRET_LEAK, pid, signal.id(), "cs-a", FROM, 3600, true, "pattern");
        List<CountRow> days = charts.countBuckets(
                BuiltInDetector.Kind.SECRET_LEAK, pid, signal.id(), "cs-a", FROM, 86_400, true, "pattern");

        assertEquals(
                Set.of(
                        new CountRow(Instant.parse("2026-10-06T10:00:00Z"), 1, 1, 1),
                        new CountRow(Instant.parse("2026-10-06T11:00:00Z"), 1, 1, 1)),
                Set.copyOf(hours));
        assertEquals(List.of(new CountRow(Instant.parse("2026-10-06T00:00:00Z"), 2, 1, 2)), days);
    }

    /**
     * A user classifier's bar counts the whole project, so a call-site card counts what the bar counts: an hour this
     * call site saw one match but the project saw three, in a day that reached a bar of two. The total stays the call
     * site's own, and every point of that day is reached.
     */
    @Test
    void userClassifierCount_isWhatItsWholeProjectBarCounts() {
        String pid = project("chart-user", Capability.SECRET_LEAK);
        declare(pid, "cs-a", null);
        declare(pid, "cs-b", null);
        ClassifierRow refunds = ClassifierRows.insertRow(
                classifierRows,
                pid,
                "refund-words",
                "Refund words",
                BuiltInDetector.Kind.REGEX,
                "{\"arming\":{\"threshold\":2}}",
                ClassifierRow.Mode.DISCOVERY,
                true);
        Instant at = Instant.parse("2026-10-06T10:00:00Z");
        match(pid, refunds, "cs-a", at);
        match(pid, refunds, "cs-b", at.plusSeconds(1));
        match(pid, refunds, "cs-b", at.plusSeconds(2));

        ChartCard week = card(service.charts(pid, "cs-a", null, 7), "refund-words");
        ChartCard month = card(service.charts(pid, "cs-a", null, 28), "refund-words");

        assertEquals(new ArmingView(2, 86_400, "event_count", "any"), week.arming());
        Instant hour = Instant.parse("2026-10-06T10:00:00Z");
        assertEquals(ChartPoint.count(hour, hour.plusSeconds(3600), false, 3, 1, true), point(week, hour));
        Instant early = Instant.parse("2026-10-06T01:00:00Z");
        assertEquals(ChartPoint.count(early, early.plusSeconds(3600), false, 0, 0, true), point(week, early));
        Instant dayBefore = Instant.parse("2026-10-05T10:00:00Z");
        assertEquals(
                ChartPoint.count(dayBefore, dayBefore.plusSeconds(3600), false, 0, 0, false), point(week, dayBefore));
        Instant sixHours = Instant.parse("2026-10-06T06:00:00Z");
        assertEquals(
                ChartPoint.count(sixHours, sixHours.plusSeconds(6 * 3600), false, 3, 1, true), point(month, sixHours));
        assertEquals(new HeadlineView(1.0, null), week.headline());
    }

    /** A finding nobody opened a case for never draws a bar; nor does a case resolved before the range. */
    @Test
    void caseSpans_skipFindingsWithoutCase() {
        String pid = project("chart-no-case", Capability.FRUSTRATION);
        String open = caseRow(pid, 1, "frustration", "call_site", "cs-a", "open", null);
        String old = caseRow(pid, 2, "frustration", "call_site", "cs-a-old", "resolved", "2026-09-01T00:00:00Z");
        String withCase =
                finding(pid, "frustration", "classifier", "frustration", "cs-a", "2026-10-03T08:00:00Z", open);
        finding(pid, "frustration", "classifier", "frustration", "cs-a", "2026-10-04T08:00:00Z", null);
        finding(pid, "frustration", "classifier", "frustration", "cs-a", "2026-08-20T08:00:00Z", old);

        List<CaseSpan> strip = charts.caseSpans(pid, new ClassifierOnCallSite("frustration", "cs-a"), FROM, TO);

        assertEquals(
                List.of(new CaseSpan(
                        withCase, open, "C-1", "title " + open, "open", "2026-10-03T08:00:00Z", null, null, null)),
                strip);
    }

    /** Two findings in one case are two bars with one reference; open first, then newest onset by instant. */
    @Test
    void caseSpans_oneBarPerFindingWithCaseReference() {
        String pid = project("chart-bars", Capability.FRUSTRATION);
        String open = caseRow(pid, 7, "frustration", "call_site", "cs-a", "open", null);
        String fixed = caseRow(pid, 43, "frustration", "call_site", "cs-a-2", "resolved", "2026-10-05T12:00:00Z");
        String older = finding(pid, "frustration", "classifier", "frustration", "cs-a", "2026-09-20T09:00:00Z", open);
        String first = finding(pid, "frustration", "classifier", "frustration", "cs-a", "2026-09-29T09:00:00Z", fixed);
        // Instant.toString drops a zero fraction, so as text this sorts before 09:00:00Z. It is later.
        String second =
                finding(pid, "frustration", "classifier", "frustration", "cs-a", "2026-09-29T09:00:00.5Z", fixed);

        List<CaseSpan> strip = charts.caseSpans(pid, new ClassifierOnCallSite("frustration", "cs-a"), FROM, TO);

        assertEquals(
                List.of(
                        new CaseSpan(
                                older, open, "C-7", "title " + open, "open", "2026-09-20T09:00:00Z", null, null, null),
                        new CaseSpan(
                                second,
                                fixed,
                                "C-43",
                                "title " + fixed,
                                "resolved",
                                "2026-09-29T09:00:00.5Z",
                                "2026-10-05T12:00:00Z",
                                "recovered",
                                "fixed"),
                        new CaseSpan(
                                first,
                                fixed,
                                "C-43",
                                "title " + fixed,
                                "resolved",
                                "2026-09-29T09:00:00Z",
                                "2026-10-05T12:00:00Z",
                                "recovered",
                                "fixed")),
                strip);
    }

    /**
     * The selector counts what the cards count: a muted case is not open, and a tool-grain drift case names its
     * busiest caller but belongs to the tool.
     */
    @Test
    void callSiteOpenCases_excludeToolDurationAndMutedCases() {
        String pid = project("chart-open", Capability.DURATION_DRIFT);
        ClassifierRow drift = fixture.builtIn(pid, BuiltInDetector.Kind.DURATION_DRIFT);
        String frustration = caseRow(pid, 1, "frustration", "call_site", "cs-a", "open", null);
        finding(pid, "frustration", "classifier", "frustration", "cs-a", "2026-10-01T00:00:00Z", frustration);
        String muted = caseRow(pid, 2, "classifier", "classifier", "groundedness", "muted", null);
        finding(pid, "groundedness", "classifier", "groundedness", "cs-a", "2026-10-01T00:00:00Z", muted);
        String toolBucket = baseline(pid, drift, "tool_duration", "tool", "tool:search");
        String toolCase = caseRow(pid, 3, "metric_drift", "metric_baseline", toolBucket, "open", null);
        finding(pid, "duration_drift", "metric_baseline", toolBucket, "cs-a", "2026-10-01T00:00:00Z", toolCase);
        String turnBucket = baseline(pid, drift, "turn_duration", "call_site", "cs-a");
        String turnCase = caseRow(pid, 4, "metric_drift", "metric_baseline", turnBucket, "open", null);
        finding(pid, "duration_drift", "metric_baseline", turnBucket, "cs-a", "2026-10-01T00:00:00Z", turnCase);

        assertEquals(Map.of("cs-a", 2), charts.openCasesByCallSite(pid, List.of("frustration", "groundedness")));
        assertEquals(Map.of("tool:search", 1), charts.openCasesByTool(pid));
    }

    /**
     * A tool-grain duration bucket also holds MCP and retrieval actions. Only {@code tool:} keys are tools, so an
     * open case on {@code mcp:} or {@code retrieval:} never lists a tool with a raw key.
     */
    @Test
    void toolOpenCases_countOnlyToolKeys() {
        String pid = project("chart-tool-keys", Capability.DURATION_DRIFT);
        ClassifierRow drift = fixture.builtIn(pid, BuiltInDetector.Kind.DURATION_DRIFT);
        long seq = 1;
        for (String key : List.of("tool:search", "mcp:fetch_page", "retrieval:kb")) {
            String bucket = baseline(pid, drift, "tool_duration", "tool", key);
            String open = caseRow(pid, seq++, "metric_drift", "metric_baseline", bucket, "open", null);
            finding(pid, "duration_drift", "metric_baseline", bucket, "cs-a", "2026-10-01T00:00:00Z", open);
        }

        assertEquals(Map.of("tool:search", 1), charts.openCasesByTool(pid));
    }

    // ---- seeds --------------------------------------------------------------------------------------

    private String project(String slug, Capability capability) {
        return TenantFixture.bootstrap(tenants, slug, org -> capabilities.grant(org.id(), capability))
                .project()
                .id();
    }

    private ClassifierRow enable(ClassifierRow row) {
        jdbc.sql("UPDATE classifier SET enabled = TRUE WHERE id = :id")
                .param("id", row.id())
                .update();
        return row;
    }

    private void declare(String pid, String callSite, @Nullable String schema) {
        jdbc.sql("INSERT INTO call_site (project_id, id, output_schema) VALUES (:pid, :id, :schema)")
                .param("pid", pid)
                .param("id", callSite)
                .param("schema", schema)
                .update();
    }

    private static ChartCard card(ChartsView view, String key) {
        return view.cards().stream()
                .filter(c -> key.equals(c.classifierKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + key + " card in " + view));
    }

    private static ChartPoint point(ChartCard card, Instant start) {
        return card.points().stream()
                .filter(p -> start.toString().equals(p.startAt()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no point at " + start + " in " + card.points()));
    }

    private void frustrationFlag(
            String pid, ClassifierRow signal, String traceId, String conversation, String callSite, Instant at) {
        jdbc.sql("INSERT INTO frustration_detection"
                        + " (id, project_id, classifier_id, classifier_key, subject_session_id, subject_trace_id,"
                        + " severity, confidence, evidence, subject_started_at)"
                        + " VALUES (:id, :pid, :cid, 'frustration', :conv, :trace, 'warn', 'high',"
                        + " CAST(:evidence AS jsonb), :at)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("cid", signal.id())
                .param("conv", conversation)
                .param("trace", traceId)
                .param("evidence", "{\"score\":0.71,\"call_site_id\":\"" + callSite + "\"}")
                .param("at", Timestamp.from(at))
                .update();
    }

    private ai.tessary.storage.SpanRow output(String pid, String callSite, Instant at) {
        return fx.spanSeed(pid)
                .callSiteId(callSite)
                .at(at)
                .previews(null, "{\"answer\":\"ok\"}")
                .write();
    }

    /** A settled trace on {@code callSite} whose root span runs {@code ms}. */
    private String turn(String pid, String callSite, Instant at, long ms) {
        var root = fx.spanSeed(pid)
                .callSiteId(callSite)
                .kind("agent")
                .at(at)
                .endedAt(at.plusMillis(ms))
                .write();
        fx.rollup(pid, root.traceId());
        return root.traceId();
    }

    private String rootOf(String pid, String traceId) {
        return spans.listByTrace(pid, traceId).stream()
                .filter(s -> s.parentSpanId() == null)
                .findFirst()
                .orElseThrow()
                .id();
    }

    private void cost(String traceId, @Nullable String totalCost, int unpriced) {
        jdbc.sql("UPDATE trace SET total_cost = CAST(:cost AS numeric), unpriced_spans = :unpriced WHERE id = :id")
                .param("cost", totalCost)
                .param("unpriced", unpriced)
                .param("id", traceId)
                .update();
    }

    private void leak(
            String pid,
            ClassifierRow signal,
            String callSite,
            String pattern,
            String confidence,
            String session,
            Instant at) {
        var span = fx.spanSeed(pid).callSiteId(callSite).at(at).write();
        jdbc.sql("INSERT INTO secret_leak_detection (id, project_id, classifier_id, classifier_key,"
                        + " subject_session_id, subject_trace_id, subject_span_id, severity, confidence, evidence,"
                        + " subject_started_at)"
                        + " VALUES (:id, :pid, :cid, 'secret_leak', :session, :trace, :span, 'critical', :confidence,"
                        + " CAST(:evidence AS jsonb), :at)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("cid", signal.id())
                .param("session", session)
                .param("trace", span.traceId())
                .param("span", span.id())
                .param("confidence", confidence)
                .param("evidence", "{\"pattern\":\"" + pattern + "\"}")
                .param("at", Timestamp.from(at))
                .update();
    }

    private void match(String pid, ClassifierRow signal, String callSite, Instant at) {
        var span = fx.spanSeed(pid).callSiteId(callSite).at(at).write();
        jdbc.sql("INSERT INTO user_classifier_detection (id, project_id, classifier_id, classifier_key,"
                        + " subject_trace_id, subject_span_id, confidence, evidence, subject_started_at)"
                        + " VALUES (:id, :pid, :cid, :key, :trace, :span, 'high', CAST('{}' AS jsonb), :at)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("cid", signal.id())
                .param("key", signal.classifierKey())
                .param("trace", span.traceId())
                .param("span", span.id())
                .param("at", Timestamp.from(at))
                .update();
    }

    private String caseRow(
            String pid,
            long seq,
            String detector,
            String subjectKind,
            String subjectId,
            String state,
            @Nullable String resolvedAt) {
        String id = Ids.ulid();
        boolean resolved = resolvedAt != null;
        jdbc.sql("INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label,"
                        + " metric, state, title, basis, severity, onset_at, opened_at, last_seen_at, updated_at,"
                        + " resolved_at, resolution, disposition)"
                        + " VALUES (:id, :pid, :seq, :detector, :kind, :subject, :subject, 'm', :state, :title, 'b',"
                        + " 1.0, '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z',"
                        + " '2026-09-01T00:00:00Z', :resolvedAt, :resolution, :disposition)")
                .param("id", id)
                .param("pid", pid)
                .param("seq", seq)
                .param("detector", detector)
                .param("kind", subjectKind)
                .param("subject", subjectId)
                .param("state", state)
                .param("title", "title " + id)
                .param("resolvedAt", resolvedAt)
                .param("resolution", resolved ? "recovered" : null)
                .param("disposition", resolved && "frustration".equals(detector) ? "fixed" : null)
                .update();
        return id;
    }

    private String finding(
            String pid,
            String classifierKey,
            String subjectKind,
            String subjectId,
            String callSite,
            String onsetAt,
            @Nullable String caseId) {
        String id = Ids.ulid();
        jdbc.sql("INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,"
                        + " call_site_id, status, onset_at, last_seen_at, created_at, updated_at, case_id)"
                        + " VALUES (:id, :pid, :key, :cause, :kind, :subject, :callSite, 'open', :onset, :onset,"
                        + " :onset, :onset, :caseId)")
                .param("id", id)
                .param("pid", pid)
                .param("key", classifierKey)
                .param("cause", "cause-" + id)
                .param("kind", subjectKind)
                .param("subject", subjectId)
                .param("callSite", callSite)
                .param("onset", onsetAt)
                .param("caseId", caseId)
                .update();
        return id;
    }

    private String baseline(String pid, ClassifierRow drift, String measure, String bucketKind, String key) {
        String id = Ids.ulid();
        jdbc.sql("INSERT INTO metric_baseline (id, project_id, classifier_id, measure, bucket_kind, bucket_key,"
                        + " state, created_at, updated_at)"
                        + " VALUES (:id, :pid, :cid, :measure, :kind, :key, 'armed', :at, :at)")
                .param("id", id)
                .param("pid", pid)
                .param("cid", drift.id())
                .param("measure", measure)
                .param("kind", bucketKind)
                .param("key", key)
                .param("at", "2026-09-01T00:00:00Z")
                .update();
        return id;
    }
}
