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
 * {@link MetricDriftSweep} end to end against the real Postgres, for the two things a sweep has to get
 * right before anything it computes can be believed.
 *
 * <p>The cursor advances: a sweep that scores traffic and leaves its cursor behind re-reads the same
 * page every heartbeat forever, and one that advances past traffic it could not read loses that
 * traffic permanently. Both are invisible in a unit test.
 *
 * <p>A replayed page is not counted twice. The keyset cursor lives on the job row and the counters
 * live on the baseline, and those two have different lifetimes: clearing a stuck queue, or a
 * call-site fact arriving late, rewinds the cursor to null and re-offers the whole history. The
 * {@code counted_through_*} watermark is what makes that safe. The replay test below asserts that the
 * page really was re-read: the watermark, not a shrinking query, is what stops the double count.
 *
 * <p>The signal is the seeded {@code duration_drift} row wearing a config blob shrunk to fixture sizes.
 * The sweep is invoked directly rather than through {@link ClassifierWorker}'s {@code Grain.WINDOW}
 * branch, so nothing here depends on the classifier being enabled, which it is not until
 * a null run against real traffic sets a measured {@code w1_floor}.
 */
@SpringBootTest
class MetricDriftSweepIntegrationTest {

    /**
     * The shipped shape at the smallest sizes the clamps allow, so a window closes within a fixture
     * rather than within a production week. Everything else — {@code w1_floor}, the settle horizon, the
     * bin count — is left at its default, because those are what the sweep is supposed to be reading.
     */
    private static final String CONFIG = """
            {"measures": ["turn_duration"], "window_target_count": 50, "min_sample": 30}""";

    /**
     * The shipped measure list at the same fixture sizes — both grains of one switch. The two suppression
     * tests below need it because the whole point of §6.1 is what happens when a page closes a window at
     * each grain in the same pass.
     */
    private static final String CONFIG_BOTH_GRAINS = """
            {"measures": ["turn_duration", "tool_duration"], "window_target_count": 50, "min_sample": 30}""";

    /**
     * The {@code cost_drift} shape at fixture sizes. One measure, because {@code cost} is the only measure
     * under that switch that can open a finding at all — the four token buckets ride on the finding as
     * evidence and are not nameable here (metric-drift.md §6.1).
     */
    private static final String CONFIG_COST = """
            {"measures": ["cost"], "window_target_count": 50, "min_sample": 30}""";

    private static final String CALL_SITE = "cs-research";

    /** In the price book with a cache-creation rate, so a reported cache write there is a measurement. */
    private static final String ANTHROPIC_MODEL = "claude-sonnet-5";

    /** In the price book with NO cache-creation rate: automatic caching, no write charge, no write count. */
    private static final String OPENAI_MODEL = "gpt-4o";

    /** The one tool these fixtures call, as {@code ActionSymbol} mints it — drift's alphabet, verbatim. */
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
        // Under the window target on purpose: this test is about the counters, and a rotation mid-way
        // would make "did the count change" ambiguous.
        String lastTraceId = seedTurns(pid, 40);

        assertEquals(40, sweep.sweepMetrics(claim(pid, signal), signal).scanned());
        MetricBaselineRow first = baseline(pid, signal);
        assertEquals(40, first.currentCount());
        assertNull(first.controlJson(), "40 is under the 50-sample target, so no window closed into the control");

        // Exactly what ClassifierService#rewindForCallSiteFact does when a fact lands late, and what
        // clearing a stuck queue does by hand: the cursor goes back to the beginning of history.
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

        // Three windows whose EVENT time is months behind the moment this test actually runs — the
        // shape a backfill import has. If the sweep threaded its own wall-clock day into MetricControl
        // instead of each window's own event day, all three would fold into the ONE slot keyed to
        // today, and the ring assertion below would find one slot instead of three.
        Instant lateJune = Instant.parse("2026-06-24T10:00:00Z");
        Instant earlyJuly = lateJune.plus(7, ChronoUnit.DAYS);
        Instant judgedJuly = earlyJuly.plus(7, ChronoUnit.DAYS);

        // Window 1: nothing to compare against yet, so it becomes the bootstrap PIN.
        seedTurnsAt(pid, lateJune, 50, 2_000, null);
        assertEquals(1, sweep.sweepMetrics(claim(pid, signal), signal).windowsClosed());

        // Window 2: breaks from the pin just established. Fires on the PINNED arm — a separate cause
        // from the one this test is about — and folds an 8s day into the ring alongside the 2s one.
        seedTurnsAt(pid, earlyJuly, 50, 8_000, null);
        MetricDriftSweep.MetricSweepOutcome regression = sweep.sweepMetrics(claim(pid, signal), signal);
        assertEquals(1, regression.windowsClosed());
        assertEquals(1, regression.fired(), "still the same pin (2s), so an 8s window breaks from it");

        // Window 3: back to the pin's own 2s level, so the PINNED arm falls silent (ratio 1, no
        // shift) — which is what lets the ROLLING CONTROL arm be the one that fires here. That
        // control is a blend of the two prior event-days, weighted by how far back each one actually
        // was (late June at age 14, early July at age 7): if the sweep had threaded the wrong day into
        // either fold or resolve, this window would either compare against nothing (every day aged out
        // past real wall-clock retention) or against the two days merged as if same-day, and the
        // control arm below would not read as it does.
        seedTurnsAt(pid, judgedJuly, 50, 2_000, null);
        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);
        assertEquals(1, outcome.windowsClosed());
        assertEquals(1, outcome.fired(), "recovered against the pin, but still a shift against the rolling control");

        // The ring itself: three slots keyed by the EVENT day of each closing sample, never by the day
        // the sweep actually ran on — which is over two months later than any of them.
        MetricBaselineRow row = baseline(pid, signal);
        List<String> ringDays = new ArrayList<>();
        for (JsonNode day : MAPPER.readTree(row.controlJson()).path("days")) {
            ringDays.add(day.path("d").asText());
        }
        assertEquals(List.of("2026-06-24", "2026-07-01", "2026-07-08"), ringDays);

        // And the control-arm finding names the reference it was actually judged against: both prior
        // event-days, oldest first — late June AND early July, weighted rather than merged — exactly
        // what a human reading a backfilled regression needs to see.
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

        // The exact same three-window shape as the backfill replay above — a bootstrap pin, an 8s
        // window that breaks from it, and a recovery back to the pin's own 2s level — so the only
        // variable this test adds is whether a human confirmed the middle window before the third ran.
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

        // A human confirms it as a real regression. `recordShift` writes onset_at/last_seen_at as the
        // window's own EVENT time [R11] — checked here by reading them straight back off the row
        // `confirmedSpansBySubject` hands the sweep, rather than trusting the write in isolation.
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

        // Window 3: left in, early July (8s) would pollute the reference exactly as it does in the
        // unconfirmed backfill-replay test above, and this 2s recovery would misread as a shift off a
        // reference the confirmed regression itself had inflated. Excluded correctly, the reference is
        // late June alone — which this window matches exactly — so nothing should fire.
        seedTurnsAt(pid, judgedJuly, 50, 2_000, null);
        assertEquals(
                0,
                sweep.sweepMetrics(claim(pid, signal), signal).fired(),
                "the confirmed day is excluded from the reference, so the recovery reads as a recovery");

        // Across all three windows, the only finding on the books is the one a human already confirmed —
        // nothing the (correctly excluded) confirmed day should have produced downstream.
        assertEquals(
                1, findings.listByProject(pid, null, null, null, false, 100).size());
    }

    @Test
    @DisplayName("a window filled across two pages enumerates both, not just the page its close landed in")
    void windowRefsSurviveThePageBoundary() {
        String pid = project("metric-sweep-refs-across-pages");
        ClassifierRow signal = signal(pid);

        // Page one: 30 turns, which is the minimum sample and still under the 50 target, so the window
        // stays open and its refs have to outlive this pass to be worth anything.
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
        // The regression this test exists for. windowRefs used to be a per-pass local while the sketch
        // and count were persisted, so a window spanning two pages pinned only the 20 refs folded in the
        // pass its close landed in — a contiguous TAIL of 50 samples, written down as the population.
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
        // The shape a batch exporter produces mid-flight: the trace row exists because a CHILD landed,
        // and the root — which outlives every child, so it flushes in a later request — has not. It is
        // stamped `now`, so it is inside the backstop and its root may still be coming.
        String pending = seedRootlessTurn(pid, Instant.now());

        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);

        // The whole point. Advancing over it would lose the turn permanently — the cursor is forward-only
        // — and the loss is length-biased, because the gap between the trace row and its root IS the
        // turn's duration: the longer the turn, the likelier it is dropped, so the sketch fits on a
        // fast-biased sample and a regression that lengthens turns pushes traffic OUT of the population.
        assertEquals(40, outcome.scanned(), "the page stopped at the trace that is still arriving");
        ClassifierJobRow swept = job(pid, signal);
        assertEquals(lastComplete, swept.cursorId(), "the cursor did not step past the pending trace");

        // Once the root lands, the next pass reads it normally — nothing about it was consumed.
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
        // Two hours old, and last in the keyset. A producer that emits no parentless span at all, or a
        // root lost in transit — either way it is not in flight, and holding for it would park a
        // forward-only cursor for good.
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
        // Two windows per grain, closing in the same pass: 50 turns of 2.0s each containing one 1.5s
        // tool call, then 50 turns of 5.0s each containing one 4.5s call. Both grains moved, and they
        // moved by the same 3 seconds, because they are the same event seen at two depths.
        seedTurns(pid, 0, 50, 2_000, 1_500L);
        seedTurns(pid, 50, 50, 5_000, 4_500L);

        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);

        assertEquals(4, outcome.windowsClosed(), "two windows per grain");
        assertEquals(1, outcome.fired(), "one event, one finding — not one per grain");

        List<FindingRow> written = findings.listByProject(pid, null, null, null, false, 100);
        assertEquals(1, written.size());
        FindingRow tool = written.get(0);
        // The TOOL row survives, because it is the one that names a fix. "This call site got slower"
        // sends someone to read traces; "and 3 of the extra seconds were search_docs" ends the search.
        assertTrue(tool.nativeCauseKey().startsWith(Measure.TOOL_DURATION + ":" + TOOL_BUCKET), tool.nativeCauseKey());
        assertEquals(CALL_SITE, tool.callSiteId(), "a tool bucket is not scoped per call site; its FINDING is");

        // Suppressing loses nothing, and this is what makes that true: the turn shift a human would
        // otherwise have gone looking for is right here, with its own reference and its own ratio.
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
        // The turn went from 2.0s to 5.0s while every tool call stayed at exactly 400ms. Nothing about
        // this is visible at tool grain — the tool's distribution is bit-for-bit what it was — and it is
        // the whole reason turn duration is measured at all. A suppression rule that swallowed this
        // would leave duration_drift able to see only slower tools, never a busier agent.
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

        // BOTH windows, enumerated. The first 50 turns were the window this bucket pinned; the second 50
        // are the window that shifted against it. A claim that "it used to be 2s and now it is 5s" is not
        // auditable from the shifted side alone, so the pinned side's rows are carried on the baseline
        // row and written here — this is what pinned_refs_json exists for, and the only end-to-end proof
        // that a reference summarized into a sketch can still be pointed at.
        assertEquals(50, turn.evidenceCount(FindingEvidenceRow.Role.MEMBER), "the shifted window, every row");
        assertEquals(50, turn.evidenceCount(FindingEvidenceRow.Role.BASELINE), "and the window it was pinned against");
        assertEquals(
                0,
                turn.evidenceCount(FindingEvidenceRow.Role.EXEMPLAR),
                "and no entry point: naming one trace as the way in biases the run that reads it");

        // The tool baseline exists and is armed regardless — it was measured, it simply did not move.
        // Without this the test would pass just as well if tool_duration had never been folded at all.
        MetricBaselineRow toolBaseline = baselineFor(pid, signal, Measure.TOOL_DURATION, BucketKind.TOOL, TOOL_BUCKET);
        assertEquals(State.ARMED, toolBaseline.state());
        // 100, not 50: this page closed TWO windows for the tool and both landed on the same UTC day,
        // which is one control slot. That is the difference from the retired prev slot, which held only
        // the last of them and threw the earlier one away.
        assertEquals(100, controlDay(toolBaseline).count());
    }

    @Test
    @DisplayName("a cache collapse writes ONE cost finding, and the finding names the cache-read collapse")
    void aCacheCollapseIsOneFindingThatExplainsItself() {
        String pid = project("metric-sweep-cache-collapse");
        ClassifierRow signal = signal(pid, BuiltInDetector.Kind.COST_DRIFT, CONFIG_COST);
        // The regression this classifier exists to catch, and the reason its measures are not five
        // switches: somebody edited the prompt prefix. 50 turns whose prompt was 90% cache-read, then 50
        // of the same size where none of it hits. Cost quadruples, input tokens jump 10×, cache reads go
        // to zero — three measures moving because ONE thing changed.
        seedCostTurns(pid, 0, 50, ANTHROPIC_MODEL, usage(2_000, 18_000, 200, 500L), true);
        seedCostTurns(pid, 50, 50, ANTHROPIC_MODEL, usage(20_000, 0, 200, 500L), true);

        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);

        assertEquals(2, outcome.windowsClosed());
        assertEquals(1, outcome.fired(), "one cause, one row — the token buckets are evidence, not findings");

        List<FindingRow> written = findings.listByProject(pid, null, null, null, false, 100);
        assertEquals(1, written.size(), "cost is the only measure under this switch that opens a finding");
        FindingRow cost = written.get(0);
        assertTrue(cost.nativeCauseKey().startsWith(Measure.COST + ":" + CALL_SITE + ":dearer"), cost.nativeCauseKey());

        // And the row explains itself. "3× dearer" alone sends someone to go and read spend by model;
        // the cache-read share collapsing from ~90% to ~0% beside flat output tokens names the change.
        JsonNode tokens = evidence(cost).path("tokens");
        assertTrue(tokens.isObject(), "a cost finding carries its decomposition");
        assertTrue(tokens.path("cache_read_pct_p50").get(0).asDouble() > 80.0, tokens.toString());
        assertTrue(tokens.path("cache_read_pct_p50").get(1).asDouble() < 1.0, tokens.toString());
        // The buckets themselves, [then, now] like every other pair in the blob.
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
        // gpt-4o's automatic caching has no write charge, and the price book carries no cache-creation
        // rate for it — yet these spans carry a stored 0, which is what an SDK that stamps every usage
        // attribute it knows about produces. Reading that as "no writes" would put a number nobody
        // measured into the distribution, and the collapse it would show on a later provider switch would
        // be pure bookkeeping. The input counts are the DISJOINT ones ingest stored after carving
        // OpenAI's cache-inclusive convention down: 20,000 reported, 18,000 of it from cache.
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
        // The buckets that ARE reported still carry numbers, so the assertion above is about the write
        // bucket rather than about an empty block. Input is 2,000 in the first window because the
        // cache-read carve-out already happened at ingest, which is the other half of getting OpenAI's
        // convention right.
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

        // Advancing here would lose those turns for good once a config edit names a measure again.
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

        // The 40 samples on the old layout cannot join the new one, so the window, its count and its
        // population start over together: a count of 50 would close a window holding 10 samples.
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
        // Blobs a newer or broken build could leave behind. Each one throwing would fail the sweep on this
        // bucket every tick until the job dead-lettered, and the bucket would stop being watched.
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
     * The seeded {@code duration_drift} row, wearing a config blob shrunk so a window closes inside a
     * fixture rather than inside a production week. Read from the catalog rather than hand-built so the
     * FKs from {@code metric_baseline} and the job row point at a signal that really exists.
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

    /** The signal's own job row, freshly read so its cursor is whatever the last write left. */
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
     * {@code count} turns under one session and one call site, each a trace with a root span of the same
     * 2s duration and no tool spans. Started one second apart so the {@code (started_at, id)} keyset has a
     * strict order and "the last trace" is unambiguous.
     *
     * @return the id of the last trace by event order, which is where the cursor must land
     */
    private String seedTurns(String projectId, int count) {
        return seedTurns(projectId, 0, count, 2_000, null);
    }

    /**
     * {@code count} turns of {@code turnMillis} each, optionally containing one tool call of
     * {@code toolMillis}, started from second {@code fromIndex} of the fixture's timeline.
     *
     * <p>{@code fromIndex} is what makes a two-window story tellable: successive calls have to land
     * strictly LATER on the event clock, or the fast half and the slow half interleave in the keyset and
     * every window ends up holding a mixture of both. The cursor and the per-baseline watermark both walk
     * {@code (trace.started_at, trace.id)}, so that ordering is not cosmetic.
     */
    private String seedTurns(String projectId, int fromIndex, int count, long turnMillis, @Nullable Long toolMillis) {
        return seedTurnsAt(projectId, T0.plusSeconds(fromIndex), count, turnMillis, toolMillis);
    }

    /**
     * {@code count} turns of {@code turnMillis} each, optionally containing one tool call of
     * {@code toolMillis}, started from the absolute instant {@code start} rather than an offset from
     * {@link #T0} — what a backfill replay needs, since its event times are months behind the sweep's
     * own wall clock rather than a couple of hours behind it.
     */
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
                // A child of the root, the shape a dispatched call really has. Its own start and end are
                // what tool_duration reads; MEASURED_KINDS is what admits it.
                insertSpan(projectId, traceId, rootId, "tool", "search_docs", startedAt, toolMillis);
            }
            settle(projectId, traceId);
            lastTraceId = traceId;
        }
        return lastTraceId;
    }

    /**
     * One trace whose spans have landed but whose ROOT has not: a single span naming a parent nothing
     * resolves to, which is what a trace looks like between a batch exporter's flushes.
     *
     * <p>{@code parent_span_id} is the producer's own word, stored verbatim and never repaired, so a
     * span naming an absent parent cannot be mistaken for a root.
     *
     * <p>Started at {@code at}. The trace's own {@code started_at} is what the sweep reads to decide
     * whether the root may still be in flight, so this is the knob the two tests differ on.
     *
     * @return the trace id, so a caller can land its root later
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
     * The root landing after the fact, and the re-rollup its arrival triggers in production.
     *
     * <p>Both halves matter: the sweep reads settled traces, and a span arriving on a settled trace
     * un-settles it until the worker has folded it in. Seeding the span without the recompute would leave
     * a trace the sweep is right to skip and the assertion below would read as a lost turn.
     */
    private void landRoot(String projectId, String traceId, Instant startedAt, long millis) {
        insertSpan(projectId, traceId, null, "agent", "loop", startedAt, millis);
        settle(projectId, traceId);
    }

    /** The real rollup, which is what {@code trace.is_settled} — the sweep's admission gate — means. */
    private void settle(String projectId, String traceId) {
        fx.rollup(projectId, traceId);
    }

    /**
     * The token buckets as they are stored: disjoint, with a null meaning the producer reported
     * nothing. OpenAI's cache-inclusive input count is carved down at ingest, so a fixture must seed
     * the already-corrected value; a still-inclusive one would be seeding a row ingest never produces.
     */
    private static TokenUsage usage(long input, long cacheRead, long output, @Nullable Long cacheCreation) {
        return new TokenUsage(input, output, cacheRead, cacheCreation == null ? 0 : cacheCreation);
    }

    /**
     * {@code count} turns under one call site, each an agent root span with one llm leaf carrying
     * {@code usage} on {@code model} — the shape cost is summed from.
     *
     * <p>The llm leaf carries the price stamped at arrival, because that is what {@code IngestPricer}
     * writes and a recorded price is the only source of dollars the sweep reads. The MODEL is on the leaf
     * and never on the root, because root spans carry none — bucketing cost by a model read off the root
     * is the bug {@code VitalsRepository} documents.
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
     * One span with its own interval. {@code latency_ms} is left null — it is a producer-reported column
     * and these producers do not report it — so every duration here comes off the interval, which is the
     * live path in production.
     *
     * @param parentId null for the turn's entry point, the root's id for a span it dispatched
     * @return the span's id
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

    /**
     * What ingest would have stamped on this generation: the book's price for that usage, or null where
     * the book carries no rate — the same null {@code IngestPricer} writes, and the one the sweep
     * abstains on.
     */
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

    /**
     * The one day the control ring holds after a single close. Asserted through the ring rather than
     * through a resolved control because the resolved view is weighted — a same-day slot weighs 1 and a
     * test asserting exact counts should not depend on that staying true tomorrow.
     */
    private static MetricSketch controlDay(MetricBaselineRow row) {
        MetricControl.Day day = MetricControl.fromJson(row.controlJson()).newest();
        assertNotNull(day, "a closed window writes a control day");
        return sketch(day.sketchJson());
    }

    /** Parse a persisted sketch, failing the test rather than the null check when the column is empty. */
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
