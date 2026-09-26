// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorDtos;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.classifier.metric.MetricBaselineRow.BucketKind;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricBaselineRow.State;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.vitals.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@link MetricDriftSweep} against real Postgres, for the two things a sweep must get right first.
 *
 * <p>The cursor advances: left behind, it re-reads one page forever; advanced past unreadable traffic, it loses that
 * traffic. A replayed page is not counted twice: a rewind re-offers history, and the {@code counted_through_*}
 * watermark, not a shrinking query, stops the double count.
 *
 * <p>The sweep is called directly rather than through {@link ClassifierWorker}, so nothing depends on the classifier
 * being enabled.
 */
@SpringBootTest
class MetricDriftSweepIntegrationTest {

    /**
     * The shipped shape at the smallest sizes the clamps allow, so a window closes within a fixture; everything else
     * stays default, because that is what the sweep reads.
     */
    private static final String CONFIG = """
            {"measures": ["turn_duration"], "window_target_count": 50, "min_sample": 30}""";

    /** Both grains of one switch, for the two §6.1 suppression tests. */
    private static final String CONFIG_BOTH_GRAINS = """
            {"measures": ["turn_duration", "tool_duration"], "window_target_count": 50, "min_sample": 30}""";

    /**
     * {@code cost} is the only measure under this switch that can open a finding; the token buckets ride along as
     * evidence (metric-drift.md §6.1).
     */
    private static final String CONFIG_COST = """
            {"measures": ["cost"], "window_target_count": 50, "min_sample": 30}""";

    private static final String CALL_SITE = "cs-research";

    /** In the price book with a cache-creation rate, so a reported cache write there is a measurement. */
    private static final String ANTHROPIC_MODEL = "claude-sonnet-5";

    /** In the price book with no cache-creation rate: automatic caching, no write charge or count. */
    private static final String OPENAI_MODEL = "gpt-4o";

    /** The one tool these fixtures call, as {@code ActionSymbol} mints it. */
    private static final String TOOL_BUCKET = "tool:search_docs";

    /** Old enough that the head read's settle window has passed for every seeded trace. */
    private static final Instant T0 = Instant.now().minus(2, ChronoUnit.HOURS);

    @Autowired
    MetricDriftSweep sweep;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    MetricBaselineRepository baselines;

    @Autowired
    FindingRepository findings;

    @Autowired
    ClassifierJobRepository jobs;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    ClassifierService classifiers;

    @Autowired
    FindingService drift;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Autowired
    TenantService tenants;

    /** Prices the cost fixture's generations from the imported book, the one {@code IngestPricer} reads. */
    @Autowired
    ai.tessary.pricing.PlatformCallPricer prices;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    @Test
    @DisplayName("a rewound cursor re-reads the page and the watermark refuses to count it twice")
    void aReplayedPageIsNotFoldedInTwice() {
        String pid = project("metric-sweep-replay");
        ClassifierRow signal = signal(pid);
        // Under the window target, so a rotation cannot make "did the count change" ambiguous.
        String lastTraceId = seedTurns(pid, 40);

        assertEquals(40, sweep.sweepMetrics(claim(pid, signal), signal).scanned());
        MetricBaselineRow first = baseline(pid, signal);
        assertEquals(40, first.currentCount());
        assertNull(first.controlJson(), "40 is under the 50-sample target, so no window closed into the control");

        // What a late call-site fact or a cleared queue does: the cursor goes back to the start.
        jobs.rewindCursor(pid, signal.id());
        MetricDriftSweep.MetricSweepOutcome replay = sweep.sweepMetrics(claim(pid, signal), signal);

        assertEquals(40, replay.scanned(), "the page really was re-read — the guard is the watermark, not the query");
        MetricBaselineRow second = baseline(pid, signal);
        assertEquals(40, second.currentCount(), "every one of those 40 was already in the sketch");
        assertEquals(40, sketch(second.currentSketchJson()).count());
        assertEquals(lastTraceId, second.countedThroughId());
        assertEquals(first.currentOpenedAt(), second.currentOpenedAt(), "the window did not re-open");
    }

    @Test
    @DisplayName("a backfill replay compares each window with its prior 21 event-days, not the sweep's own clock")
    void aBackfillReplayJudgesEachWindowAgainstItsOwnEventDays() throws Exception {
        String pid = project("metric-sweep-backfill-replay");
        ClassifierRow signal = signal(pid);

        // Three windows months behind wall-clock time, like a backfill. Keying MetricControl by the sweep's own day
        // would fold all three into one slot.
        Instant lateJune = Instant.parse("2026-06-24T10:00:00Z");
        Instant earlyJuly = lateJune.plus(7, ChronoUnit.DAYS);
        Instant judgedJuly = earlyJuly.plus(7, ChronoUnit.DAYS);

        // Window 1 becomes the bootstrap pin.
        seedTurnsAt(pid, lateJune, 50, 2_000, null);
        assertEquals(1, sweep.sweepMetrics(claim(pid, signal), signal).windowsClosed());

        // Window 2 breaks from the pin, fires on the pinned arm, and folds an 8s day into the ring.
        seedTurnsAt(pid, earlyJuly, 50, 8_000, null);
        MetricDriftSweep.MetricSweepOutcome regression = sweep.sweepMetrics(claim(pid, signal), signal);
        assertEquals(1, regression.windowsClosed());
        assertEquals(1, regression.fired(), "still the same pin (2s), so an 8s window breaks from it");

        // Window 3 returns to the pin's 2s, so only the rolling control arm can fire. That control weighs the two
        // prior event days by age (14 and 7); the wrong day in fold or resolve would compare against nothing or
        // against the days merged.
        seedTurnsAt(pid, judgedJuly, 50, 2_000, null);
        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);
        assertEquals(1, outcome.windowsClosed());
        assertEquals(1, outcome.fired(), "recovered against the pin, but still a shift against the rolling control");

        // Three slots keyed by each window's event day, never by the day the sweep ran.
        MetricBaselineRow row = baseline(pid, signal);
        List<String> ringDays = new ArrayList<>();
        for (JsonNode day : MAPPER.readTree(row.controlJson()).path("days")) {
            ringDays.add(day.path("d").asText());
        }
        assertEquals(List.of("2026-06-24", "2026-07-01", "2026-07-08"), ringDays);

        // The control finding names both prior event days, oldest first, weighted rather than merged.
        FindingRow controlFinding = findings.listByProject(pid, null, null, null, false, 100).stream()
                .filter(f -> f.nativeCauseKey().endsWith(":previous"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finding fired on the rolling-control arm"));
        MetricFindingEvidence.ShiftDetail detail = MetricFindingEvidence.detail(controlFinding.payloadJson());
        assertNotNull(detail);
        MetricFindingEvidence.Control control = detail.control();
        assertNotNull(control, "the control arm reports how its reference was composed");
        assertEquals(2, control.daysUsed(), "both prior event-days contributed, weighted — not one merged day");
        assertEquals("2026-06-24", control.oldestDay());
    }

    @Test
    @DisplayName("a confirmed regression's own event day is excluded from the control it would otherwise pollute")
    void aConfirmedRegressionExcludesItsOwnDayFromTheControl() {
        String pid = project("metric-sweep-confirmed-exclusion");
        ClassifierRow signal = signal(pid);

        // The same three windows as the backfill test; the only variable is a human confirming the middle one.
        Instant lateJune = Instant.parse("2026-06-24T10:00:00Z");
        Instant earlyJuly = lateJune.plus(7, ChronoUnit.DAYS);
        Instant judgedJuly = earlyJuly.plus(7, ChronoUnit.DAYS);

        seedTurnsAt(pid, lateJune, 50, 2_000, null);
        assertEquals(1, sweep.sweepMetrics(claim(pid, signal), signal).windowsClosed());

        seedTurnsAt(pid, earlyJuly, 50, 8_000, null);
        assertEquals(
                1,
                sweep.sweepMetrics(claim(pid, signal), signal).fired(),
                "breaks from the pin, same as the unconfirmed backfill replay above");

        // recordShift writes onset and last_seen as the window's event time [R11]; read back through
        // confirmedSpansBySubject.
        FindingRow toConfirm = findings.listByProject(pid, null, null, null, false, 100).stream()
                .filter(f -> f.nativeCauseKey().endsWith(":pinned"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finding fired on the pinned arm"));
        drift.resolve(pid, toConfirm.id(), BehaviorDtos.BehaviorResolutionRequest.NOT_EXPECTED, "user-1");

        MetricBaselineRow confirmedBaseline = baseline(pid, signal);
        List<FindingRepository.ConfirmedSpan> spans = findings.confirmedSpansBySubject(
                        pid, Set.of(signal.classifierKey()))
                .get(confirmedBaseline.id());
        assertNotNull(spans, "the confirmed finding must be readable back through its own baseline");
        FindingRepository.ConfirmedSpan span = spans.get(0);
        assertEquals(
                "2026-07-01",
                MetricControl.dayOf(Instant.parse(span.fromAt())),
                "onset_at reads back as the window's own EVENT day — the day the sweep must exclude");
        assertEquals(
                MetricControl.dayOf(Instant.parse(span.fromAt())),
                MetricControl.dayOf(Instant.parse(span.toAt())),
                "one window's worth of traffic is one event day, start and end alike");

        // Early July, left in, would inflate the reference and make this 2s recovery read as a shift. Excluded, the
        // reference is late June, which this window matches, so nothing fires.
        seedTurnsAt(pid, judgedJuly, 50, 2_000, null);
        assertEquals(
                0,
                sweep.sweepMetrics(claim(pid, signal), signal).fired(),
                "the confirmed day is excluded from the reference, so the recovery reads as a recovery");

        // The only finding is the one a human confirmed.
        assertEquals(
                1, findings.listByProject(pid, null, null, null, false, 100).size());
    }

    @Test
    @DisplayName("a window filled across two pages enumerates both, not just the page its close landed in")
    void windowRefsSurviveThePageBoundary() {
        String pid = project("metric-sweep-refs-across-pages");
        ClassifierRow signal = signal(pid);

        // 30 turns: the minimum sample, under the target, so the window stays open across passes.
        seedTurns(pid, 0, 30, 2_000, null);
        assertEquals(30, sweep.sweepMetrics(claim(pid, signal), signal).scanned());
        MetricBaselineRow afterFirst = baseline(pid, signal);
        assertEquals(30, afterFirst.currentCount());
        assertEquals(
                30,
                MetricEvidenceRefs.fromJson(afterFirst.currentRefsJson()).size(),
                "the open window kept the rows it folded, not just their count");

        // Page two crosses the target: the window closes at 50 and the last 10 open the next one.
        seedTurns(pid, 30, 30, 2_000, null);
        assertEquals(1, sweep.sweepMetrics(claim(pid, signal), signal).windowsClosed());

        MetricBaselineRow row = baseline(pid, signal);
        assertEquals(50, sketch(row.pinnedSketchJson()).count(), "the bootstrap pin is the window that closed");
        // The regression: windowRefs was a per-pass local, so a window spanning two pages pinned only its last pass's
        // refs.
        assertEquals(
                50,
                MetricEvidenceRefs.fromJson(row.pinnedRefsJson()).size(),
                "the pinned reference enumerates every sample in the window, across both pages");
        assertEquals(
                10,
                MetricEvidenceRefs.fromJson(row.currentRefsJson()).size(),
                "and the refs rotate with the sketch: the new window holds the carry only");
    }

    @Test
    @DisplayName("a turn whose root span has not landed holds the cursor instead of being stepped over")
    void aTraceStillWaitingForItsRootHoldsThePage() {
        String pid = project("metric-sweep-late-root");
        ClassifierRow signal = signal(pid);
        String lastComplete = seedTurns(pid, 40);
        // A batch exporter mid-flight: a child landed, the root has not, and it is inside the backstop.
        String pending = seedRootlessTurn(pid, Instant.now());

        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);

        // Advancing would lose the turn for good, and the loss is length-biased: the longer the turn, the likelier it
        // drops, so a regression that lengthens turns pushes traffic out of the population.
        assertEquals(40, outcome.scanned(), "the page stopped at the trace that is still arriving");
        ClassifierJobRow swept = job(pid, signal);
        assertEquals(lastComplete, swept.cursorId(), "the cursor did not step past the pending trace");

        landRoot(pid, pending, Instant.now().minusMillis(2_000), 2_000);
        assertEquals(1, sweep.sweepMetrics(claim(pid, signal), signal).scanned());
        assertEquals(pending, job(pid, signal).cursorId());
    }

    @Test
    @DisplayName("a rootless trace older than the backstop is admitted and abstains, rather than stalling")
    void aRootThatIsNeverComingDoesNotStallTheCursor() {
        String pid = project("metric-sweep-no-root-ever");
        ClassifierRow signal = signal(pid);
        seedTurns(pid, 40);
        // Two hours old and last in the keyset: its root is lost, not in flight, and holding would park the cursor
        // for good.
        String orphaned = seedRootlessTurn(pid, T0.plusSeconds(100));

        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);

        assertEquals(41, outcome.scanned(), "the backstop expired, so the page ran to its end");
        assertEquals(orphaned, job(pid, signal).cursorId());
        assertEquals(40, baseline(pid, signal).currentCount(), "admitted, and abstained on — never counted");
    }

    @Test
    @DisplayName("a turn slow because one tool is slow reports once — as the tool, carrying the turn")
    void aToolShiftSuppressesTheTurnItExplains() {
        String pid = project("metric-sweep-suppress");
        ClassifierRow signal = signal(pid, CONFIG_BOTH_GRAINS);
        // Two windows per grain in one pass: turn and tool both move by 3s, the same event seen at two depths.
        seedTurns(pid, 0, 50, 2_000, 1_500L);
        seedTurns(pid, 50, 50, 5_000, 4_500L);

        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);

        assertEquals(4, outcome.windowsClosed(), "two windows per grain");
        assertEquals(1, outcome.fired(), "one event, one finding — not one per grain");

        List<FindingRow> written = findings.listByProject(pid, null, null, null, false, 100);
        assertEquals(1, written.size());
        FindingRow tool = written.get(0);
        // The tool row survives because it names the fix.
        assertTrue(tool.nativeCauseKey().startsWith(Measure.TOOL_DURATION + ":" + TOOL_BUCKET), tool.nativeCauseKey());
        assertEquals(CALL_SITE, tool.callSiteId(), "a tool bucket is not scoped per call site; its FINDING is");

        // Suppression loses nothing: the turn shift rides along with its own reference and ratio.
        JsonNode explains = evidence(tool).path("explains");
        assertEquals(1, explains.size(), "the turn shift rode along rather than being dropped");
        assertEquals(Measure.TURN_DURATION, explains.get(0).path("measure").asText());
        assertEquals(CALL_SITE, explains.get(0).path("bucket").path("key").asText());
        assertEquals(1.0, explains.get(0).path("covered").asDouble(), 0.15);
    }

    @Test
    @DisplayName("eleven tool calls where three used to do: the turn fires alone, unsuppressed")
    void aTurnShiftNothingExplainsStillFires() {
        String pid = project("metric-sweep-unexplained");
        ClassifierRow signal = signal(pid, CONFIG_BOTH_GRAINS);
        // The turn slows from 2s to 5s while every tool call stays at 400ms: invisible at tool grain, and the reason
        // turn duration is measured at all.
        seedTurns(pid, 0, 50, 2_000, 400L);
        seedTurns(pid, 50, 50, 5_000, 400L);

        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);

        assertEquals(4, outcome.windowsClosed(), "the tool's windows closed too; they just had nothing to say");
        assertEquals(1, outcome.fired());

        List<FindingRow> written = findings.listByProject(pid, null, null, null, false, 100);
        assertEquals(1, written.size());
        FindingRow turn = written.get(0);
        assertTrue(turn.nativeCauseKey().startsWith(Measure.TURN_DURATION + ":" + CALL_SITE), turn.nativeCauseKey());
        assertTrue(
                evidence(turn).path("explains").isMissingNode(),
                "a finding that suppressed nothing says so by carrying no such block");

        // Both windows enumerated: the shifted one and the pinned one it is judged against, via pinned_refs_json.
        assertEquals(50, turn.evidenceCount(FindingEvidenceRow.Role.MEMBER), "the shifted window, every row");
        assertEquals(50, turn.evidenceCount(FindingEvidenceRow.Role.BASELINE), "and the window it was pinned against");
        assertEquals(
                0,
                turn.evidenceCount(FindingEvidenceRow.Role.EXEMPLAR),
                "and no entry point: naming one trace as the way in biases the run that reads it");

        // The tool baseline is armed anyway; without this the test would pass if tool_duration were never folded.
        MetricBaselineRow toolBaseline = baselineFor(pid, signal, Measure.TOOL_DURATION, BucketKind.TOOL, TOOL_BUCKET);
        assertEquals(State.ARMED, toolBaseline.state());
        // 100, not 50: two tool windows closed on one UTC day share one control slot.
        assertEquals(100, controlDay(toolBaseline).count());
    }

    @Test
    @DisplayName("a cache collapse writes ONE cost finding, and the finding names the cache-read collapse")
    void aCacheCollapseIsOneFindingThatExplainsItself() {
        String pid = project("metric-sweep-cache-collapse");
        ClassifierRow signal = signal(pid, BuiltInDetector.Kind.COST_DRIFT, CONFIG_COST);
        // The regression this classifier exists for: an edited prompt prefix. Cache reads drop from 90% to none, so
        // cost, input tokens and cache reads all move because one thing changed.
        seedCostTurns(pid, 0, 50, ANTHROPIC_MODEL, usage(2_000, 18_000, 200, 500L), true);
        seedCostTurns(pid, 50, 50, ANTHROPIC_MODEL, usage(20_000, 0, 200, 500L), true);

        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);

        assertEquals(2, outcome.windowsClosed());
        assertEquals(1, outcome.fired(), "one cause, one row — the token buckets are evidence, not findings");

        List<FindingRow> written = findings.listByProject(pid, null, null, null, false, 100);
        assertEquals(1, written.size(), "cost is the only measure under this switch that opens a finding");
        FindingRow cost = written.get(0);
        assertTrue(cost.nativeCauseKey().startsWith(Measure.COST + ":" + CALL_SITE + ":dearer"), cost.nativeCauseKey());

        // The row explains itself: the cache-read share collapsing beside flat output names the change.
        JsonNode tokens = evidence(cost).path("tokens");
        assertTrue(tokens.isObject(), "a cost finding carries its decomposition");
        assertTrue(tokens.path("cache_read_pct_p50").get(0).asDouble() > 80.0, tokens.toString());
        assertTrue(tokens.path("cache_read_pct_p50").get(1).asDouble() < 1.0, tokens.toString());
        assertEquals(18_000.0, tokens.path("tok_cache_read_p50").get(0).asDouble(), 900.0);
        assertEquals(0.0, tokens.path("tok_cache_read_p50").get(1).asDouble(), 0.05);
        assertEquals(200.0, tokens.path("tok_output_p50").get(0).asDouble(), 10.0, "output held flat");
        assertEquals(200.0, tokens.path("tok_output_p50").get(1).asDouble(), 10.0);
    }

    @Test
    @DisplayName("on a family nobody bills for cache creation, the write bucket is absent — not zero")
    void anOpenAiFamilyWindowLeavesCacheWriteAbsent() {
        String pid = project("metric-sweep-cache-write-absent");
        ClassifierRow signal = signal(pid, BuiltInDetector.Kind.COST_DRIFT, CONFIG_COST);
        // gpt-4o has no cache-write charge, yet these spans store 0, as an SDK stamping every attribute does. Reading
        // that as "no writes" would put an unmeasured number into the distribution. Input counts are ingest's
        // disjoint ones: 20,000 reported, 18,000 from cache.
        seedCostTurns(pid, 0, 50, OPENAI_MODEL, usage(2_000, 18_000, 200, 0L), true);
        seedCostTurns(pid, 50, 50, OPENAI_MODEL, usage(20_000, 0, 200, 0L), true);

        assertEquals(1, sweep.sweepMetrics(claim(pid, signal), signal).fired());

        JsonNode tokens = evidence(findings.listByProject(pid, null, null, null, false, 100)
                        .get(0))
                .path("tokens");
        assertTrue(tokens.path("tok_cache_write_p50").get(0).isNull(), tokens.toString());
        assertTrue(
                tokens.path("tok_cache_write_p50").get(1).isNull(),
                "'nobody counts writes here' must not be laundered into 'zero writes': " + tokens);
        // The reported buckets still carry numbers, so the assertion above is about the write bucket; input is 2,000
        // because ingest already carved out cache reads.
        assertEquals(2_000.0, tokens.path("tok_input_p50").get(0).asDouble(), 100.0);
        assertEquals(20_000.0, tokens.path("tok_input_p50").get(1).asDouble(), 1_000.0);
    }

    // -----------------------------------------------------------------------------------------------
    // Fixture
    @Test
    @DisplayName("a signal naming no measure this build knows finishes its job without moving the cursor")
    void aSignalWithNoKnownMeasureDoesNotStepOverTraffic() {
        String pid = project("metric-sweep-no-measures");
        ClassifierRow signal = signal(pid, "{\"measures\": [\"latency_p99\"], \"window_target_count\": 50}");
        seedTurns(pid, 10);

        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);

        // Advancing would lose these turns once a config names a measure again.
        assertEquals(0, outcome.scanned());
        assertNull(job(pid, signal).cursorId(), "nothing measured, so nothing stepped over");
        assertTrue(baselines.listByClassifier(pid, signal.id()).isEmpty());
    }

    @Test
    @DisplayName("a page whose every turn is still waiting for its root finishes without moving the cursor")
    void aPageStillArrivingIsReOfferedWhole() {
        String pid = project("metric-sweep-all-pending");
        ClassifierRow signal = signal(pid);
        String pending = seedRootlessTurn(pid, Instant.now());

        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);

        assertEquals(0, outcome.scanned());
        assertNull(job(pid, signal).cursorId(), "the pending turn is offered again next tick");

        landRoot(pid, pending, Instant.now().minusMillis(2_000), 2_000);
        assertEquals(1, sweep.sweepMetrics(claim(pid, signal), signal).scanned());
    }

    @Test
    @DisplayName("an edited bin count restarts the open window rather than mixing two grids in it")
    void anEditedBinCountRestartsTheOpenWindow() {
        String pid = project("metric-sweep-regrid");
        ClassifierRow signal = signal(pid);
        seedTurns(pid, 0, 40, 2_000, null);
        sweep.sweepMetrics(claim(pid, signal), signal);
        assertEquals(40, baseline(pid, signal).currentCount());

        ClassifierRow regridded = signal(
                pid,
                "{\"measures\": [\"turn_duration\"], \"window_target_count\": 50, \"min_sample\": 30, "
                        + "\"hist_bins\": 128}");
        seedTurns(pid, 40, 10, 2_000, null);
        sweep.sweepMetrics(claim(pid, regridded), regridded);

        // The 40 samples on the old layout cannot join the new one, so window, count and population restart together.
        MetricBaselineRow row = baseline(pid, regridded);
        assertEquals(10, row.currentCount());
        MetricSketch current = sketch(row.currentSketchJson());
        assertEquals(10, current.count());
        assertEquals(new MetricHistogram.Grid(1.0, MetricHistogram.DEFAULT_RATIO, 128).id(), current.gridId());
        assertEquals(10, MetricEvidenceRefs.fromJson(row.currentRefsJson()).size(), "the refs restart with it");
    }

    @Test
    @DisplayName("a cost window filled across two pages keeps both pages' token decomposition")
    void aWindowAcrossTwoPagesKeepsBothPagesTokens() throws Exception {
        String pid = project("metric-sweep-tokens-two-pages");
        ClassifierRow signal = signal(pid, BuiltInDetector.Kind.COST_DRIFT, CONFIG_COST);
        seedCostTurns(pid, 0, 30, ANTHROPIC_MODEL, usage(2_000, 18_000, 200, 500L), true);
        sweep.sweepMetrics(claim(pid, signal), signal);
        seedCostTurns(pid, 30, 30, ANTHROPIC_MODEL, usage(2_000, 18_000, 200, 500L), true);

        assertEquals(1, sweep.sweepMetrics(claim(pid, signal), signal).windowsClosed());

        // The window closed at 50 and became the reference: 30 turns from the first page, 20 from the second.
        MetricBaselineRow row = baselineFor(pid, signal, Measure.COST, BucketKind.CALL_SITE, CALL_SITE);
        assertEquals(
                50,
                MAPPER.readTree(Objects.requireNonNull(row.pinnedTokensJson()))
                        .path(MetricTokens.INPUT)
                        .path("n")
                        .asLong(),
                "the first page's tokens were carried into the window, not dropped at the page boundary");
    }

    @Test
    @DisplayName("unreadable stored blobs are read as absent, and the sweep carries on")
    void unreadableStoredBlobsAreReadAsAbsent() {
        String pid = project("metric-sweep-corrupt-blobs");
        ClassifierRow signal = signal(pid);
        seedTurns(pid, 0, 40, 2_000, null);
        sweep.sweepMetrics(claim(pid, signal), signal);
        String baselineId = baseline(pid, signal).id();
        // Blobs a newer or broken build could leave; throwing would dead-letter the job and stop watching the bucket.
        jdbc.sql("""
                        UPDATE metric_baseline
                        SET current_workload_json = '{not json', current_tokens_json = '{not json',
                            pinned_sketch_json = '{not json', pinned_workload_json = '{not json',
                            pinned_tokens_json = '{not json'
                        WHERE id = :id
                        """).param("id", baselineId).update();

        seedTurns(pid, 40, 20, 2_000, null);
        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);

        assertEquals(1, outcome.windowsClosed(), "the window's readable sketch still closed at 50");
        assertEquals(0, outcome.fired(), "an unreadable reference is no reference, not a shift");
        MetricBaselineRow row = baseline(pid, signal);
        assertEquals(10, row.currentCount());
        assertEquals(50, sketch(row.pinnedSketchJson()).count(), "the close re-established a readable reference");
    }

    // -----------------------------------------------------------------------------------------------

    private String project(String slug) {
        String pid = TenantFixture.bootstrap(tenants, slug).project().id();
        classifiers.seedBuiltIns(pid);
        return pid;
    }

    /**
     * The seeded {@code duration_drift} row with a fixture-sized config, read from the catalog so the FKs point at a
     * real signal.
     */
    private ClassifierRow signal(String projectId) {
        return signal(projectId, CONFIG);
    }

    private ClassifierRow signal(String projectId, String config) {
        return signal(projectId, BuiltInDetector.Kind.DURATION_DRIFT, config);
    }

    private ClassifierRow signal(String projectId, String classifierKey, String config) {
        ClassifierRow base =
                ClassifierRows.byKey(signals, projectId, classifierKey).orElseThrow();
        return new ClassifierRow(
                base.id(),
                base.projectId(),
                base.classifierKey(),
                base.name(),
                base.description(),
                base.detector(),
                config,
                base.builtIn(),
                base.version(),
                base.enabled(),
                base.mode(),
                base.createdAt(),
                base.updatedAt());
    }

    private ClassifierJobRow claim(String projectId, ClassifierRow signal) {
        jobs.enqueue(projectId, signal.id(), 0);
        return job(projectId, signal);
    }

    private ClassifierJobRow job(String projectId, ClassifierRow signal) {
        return jobs.listByProject(projectId).stream()
                .filter(j -> signal.id().equals(j.classifierId()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * {@code count} 2s turns, one second apart so the keyset order is strict. Returns the last trace, where the
     * cursor must land.
     */
    private String seedTurns(String projectId, int count) {
        return seedTurns(projectId, 0, count, 2_000, null);
    }

    /**
     * Turns of {@code turnMillis}, optionally with one tool call, from second {@code fromIndex}. Successive calls
     * must land later on the event clock, or both halves interleave in the keyset.
     */
    private String seedTurns(String projectId, int fromIndex, int count, long turnMillis, @Nullable Long toolMillis) {
        return seedTurnsAt(projectId, T0.plusSeconds(fromIndex), count, turnMillis, toolMillis);
    }

    /** Like {@link #seedTurns} but from an absolute instant, for backfill replays months behind the wall clock. */
    private String seedTurnsAt(String projectId, Instant start, int count, long turnMillis, @Nullable Long toolMillis) {
        String sessionId = SubstrateV2Fixtures.sessionId();
        fx.session(projectId, sessionId, start);

        String lastTraceId = "";
        for (int i = 0; i < count; i++) {
            Instant startedAt = start.plusSeconds(i);
            String traceId = SubstrateV2Fixtures.traceId();
            fx.trace(projectId, traceId, sessionId, startedAt);
            String rootId = insertSpan(projectId, traceId, null, "agent", "loop", startedAt, turnMillis);
            if (toolMillis != null) {
                // A child of the root, as a dispatched call really is.
                insertSpan(projectId, traceId, rootId, "tool", "search_docs", startedAt, toolMillis);
            }
            settle(projectId, traceId);
            lastTraceId = traceId;
        }
        return lastTraceId;
    }

    /**
     * A trace whose root has not landed: one span naming an absent parent, as between a batch exporter's flushes. Its
     * {@code started_at} is what decides whether the root may still arrive.
     */
    private String seedRootlessTurn(String projectId, Instant at) {
        String sessionId = SubstrateV2Fixtures.sessionId();
        String traceId = SubstrateV2Fixtures.traceId();
        fx.session(projectId, sessionId, at);
        fx.trace(projectId, traceId, sessionId, at);
        fx.spanSeed(projectId)
                .traceId(traceId)
                .parentSpanId("span-parent-not-yet-here")
                .kind("tool")
                .name("search_docs")
                .callSiteId(CALL_SITE)
                .at(at)
                .endedAt(at.plusMillis(400))
                .write();
        settle(projectId, traceId);
        return traceId;
    }

    /**
     * The root landing late, plus the re-rollup its arrival triggers: without the recompute the trace stays unsettled
     * and reads as a lost turn.
     */
    private void landRoot(String projectId, String traceId, Instant startedAt, long millis) {
        insertSpan(projectId, traceId, null, "agent", "loop", startedAt, millis);
        settle(projectId, traceId);
    }

    private void settle(String projectId, String traceId) {
        fx.rollup(projectId, traceId);
    }

    /**
     * Disjoint token buckets as stored, null meaning unreported; OpenAI's cache-inclusive input is already carved
     * down.
     */
    private static TokenUsage usage(long input, long cacheRead, long output, @Nullable Long cacheCreation) {
        return new TokenUsage(input, output, cacheRead, cacheCreation == null ? 0 : cacheCreation);
    }

    /**
     * {@code count} turns, each an agent root with one priced llm leaf. The model sits on the leaf, never the root:
     * bucketing cost by the root's model is the bug {@code VitalsRepository} documents.
     */
    private void seedCostTurns(
            String projectId, int fromIndex, int count, String model, TokenUsage usage, boolean reportsCacheWrite) {
        String sessionId = SubstrateV2Fixtures.sessionId();
        fx.session(projectId, sessionId, T0.plusSeconds(fromIndex));

        for (int i = 0; i < count; i++) {
            Instant startedAt = T0.plusSeconds(fromIndex + i);
            String traceId = SubstrateV2Fixtures.traceId();
            fx.trace(projectId, traceId, sessionId, startedAt);
            String rootId = insertSpan(projectId, traceId, null, "agent", "loop", startedAt, 2_000);
            fx.spanSeed(projectId)
                    .traceId(traceId)
                    .parentSpanId(rootId)
                    .kind("llm")
                    .name("chat")
                    .model(model)
                    .callSiteId(CALL_SITE)
                    .at(startedAt)
                    .endedAt(startedAt.plusMillis(1_500))
                    .usage(usage.inputTokens(), usage.outputTokens())
                    .cacheUsage(usage.cacheReadTokens(), reportsCacheWrite ? usage.cacheWriteTokens() : null)
                    .cost(priceOf(model, usage), null, SpanRow.CostSource.INFERRED)
                    .write();
            settle(projectId, traceId);
        }
    }

    /**
     * One span with its own interval and no {@code latency_ms}, so durations come off the interval as in production.
     */
    private String insertSpan(
            String projectId,
            String traceId,
            @Nullable String parentId,
            String kind,
            String name,
            Instant startedAt,
            long millis) {
        return fx.spanSeed(projectId)
                .traceId(traceId)
                .parentSpanId(parentId)
                .kind(kind)
                .name(name)
                .callSiteId(CALL_SITE)
                .at(startedAt)
                .endedAt(startedAt.plusMillis(millis))
                .write()
                .id();
    }

    /** The price ingest would have stamped, or null where the book has no rate. */
    private @Nullable String priceOf(String model, TokenUsage usage) {
        return prices.price(
                        model,
                        Math.toIntExact(usage.inputTokens()),
                        Math.toIntExact(usage.outputTokens()),
                        Math.toIntExact(usage.cacheReadTokens()),
                        Math.toIntExact(usage.cacheWriteTokens()))
                .map(priced -> priced.total().toPlainString())
                .orElse(null);
    }

    /** A finding's evidence blob as JSON. Read as JSON because Postgres re-serializes a jsonb value. */
    private static JsonNode evidence(FindingRow finding) {
        assertNotNull(finding.payloadJson(), "a distribution_shift finding always carries evidence");
        return finding.payload();
    }

    /** The ring's single day after one close, read raw because the resolved view is weighted. */
    private static MetricSketch controlDay(MetricBaselineRow row) {
        MetricControl.Day day = MetricControl.fromJson(row.controlJson()).newest();
        assertNotNull(day, "a closed window writes a control day");
        return sketch(day.sketchJson());
    }

    private static MetricSketch sketch(@Nullable String json) {
        assertNotNull(json, "expected a persisted sketch, found none");
        return MetricSketch.fromJson(json);
    }

    private MetricBaselineRow baseline(String projectId, ClassifierRow signal) {
        return baselineFor(projectId, signal, Measure.TURN_DURATION, BucketKind.CALL_SITE, CALL_SITE);
    }

    private MetricBaselineRow baselineFor(
            String projectId, ClassifierRow signal, String measure, String bucketKind, String bucketKey) {
        return baselines.listByClassifier(projectId, signal.id()).stream()
                .filter(r -> r.measure().equals(measure)
                        && r.bucketKind().equals(bucketKind)
                        && r.bucketKey().equals(bucketKey))
                .findFirst()
                .orElseThrow();
    }
}
