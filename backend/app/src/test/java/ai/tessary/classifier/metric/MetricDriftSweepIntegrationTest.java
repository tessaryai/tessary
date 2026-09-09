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
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
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
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.vitals.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link MetricDriftSweep} end to end against the real Postgres, for the two things a sweep has to get
 * right before anything it computes can be believed.
 *
 * <p><b>The cursor advances.</b> A sweep that scores traffic and leaves its cursor behind re-reads the
 * same page every heartbeat forever; one that advances past traffic it could not read loses that traffic
 * permanently. Both are invisible in a unit test.
 *
 * <p><b>A replayed page is not counted twice.</b> The keyset cursor lives on the JOB row and the counters
 * live on the baseline, and those two have different lifetimes: clearing a stuck queue, or a call-site
 * fact arriving late, rewinds the cursor to null and re-offers the whole history. The
 * {@code counted_through_*} watermark is what makes that safe, and behaviour drift only learned it was
 * needed after production reported {@code trace_count} 565 for a project holding 443 distinct traces.
 * The replay below is exactly that scenario, and it asserts that the page really was re-READ — the
 * watermark, not a shrinking query, is what stops the double count.
 *
 * <p>The signal is the seeded {@code duration_drift} row wearing a config blob shrunk to fixture sizes.
 * The sweep is invoked directly rather than through {@link ClassifierWorker}'s {@code Grain.WINDOW}
 * branch, so nothing here depends on the classifier being ENABLED — which it is not, and deliberately
 * so until PLAN.md §9's null case sets a measured {@code w1_floor}.
 */
@SpringBootTest
class MetricDriftSweepIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

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
     * evidence and are not nameable here (PROGRAM.md §6.1).
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

    /** Prices the cost fixture's generations the way {@code IngestPricer} prices them at ingest. */
    @Autowired
    ai.tessary.vitals.TokenPriceBook prices;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    @Test
    @DisplayName("one pass folds the page into a baseline, closes the window it fills, and advances the cursor")
    void oneSweepFillsAWindowAndMovesTheCursor() {
        String pid = project("metric-sweep-cursor");
        ClassifierRow signal = signal(pid);
        // 60 turns against a target of 50: enough to close one window and leave a tail, which is the
        // realistic case — a page boundary almost never lands on a window boundary.
        String lastTraceId = seedTurns(pid, 60);

        MetricDriftSweep.MetricSweepOutcome outcome = sweep.sweepMetrics(claim(pid, signal), signal);

        assertEquals(60, outcome.scanned());
        assertEquals(1, outcome.windowsClosed());
        // 60 identical 2s turns: one window closes, it becomes the bootstrap pin, and it is the first
        // thing this bucket has ever closed — so there is nothing to compare it against and nothing to
        // report. What a human then does with a finding is MetricFindingResolveIntegrationTest.
        assertEquals(0, outcome.fired(), "the first closed window establishes the reference; it is not a shift");

        // The cursor advanced over the WHOLE page, on the INGEST clock. Advancing only over rows some
        // measure could read would re-offer the rest on every pass forever.
        ClassifierJobRow swept = job(pid, signal);
        assertEquals(lastTraceId, swept.cursorId());
        assertNotNull(swept.cursorAt());

        MetricBaselineRow row = baseline(pid, signal);
        assertEquals(50, controlDay(row).count(), "the closed window went into the control ring");
        assertEquals(10, row.currentCount(), "current_count is per WINDOW, so the tail opened a fresh one");
        assertEquals(10, sketch(row.currentSketchJson()).count());
        // The first armed close establishes the pinned reference, because a comparison needs one and a
        // bucket has none until it has closed something. That bootstrap is not the "Legitimate — absorb"
        // write, which moves a LIVE reference and is reachable only from a human pressing the verb.
        assertEquals(50, sketch(row.pinnedSketchJson()).count());
        assertEquals(State.ARMED, row.state());
        assertEquals(lastTraceId, row.countedThroughId(), "the watermark moved with the count it guards");
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
        MetricBaselineRow toolBaseline = baselines
                .find(pid, signal.id(), Measure.TOOL_DURATION, BucketKind.TOOL, TOOL_BUCKET)
                .orElseThrow();
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
        ClassifierRow base = signals.findByKey(projectId, classifierKey).orElseThrow();
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
        String sessionId = SubstrateV2Fixtures.sessionId();
        fx.session(projectId, sessionId, T0.plusSeconds(fromIndex));

        String lastTraceId = "";
        for (int i = 0; i < count; i++) {
            Instant startedAt = T0.plusSeconds(fromIndex + i);
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
     * <p>In v2 that is a plain statement rather than an inference. {@code parent_span_id} is the
     * producer's own word, stored verbatim and never repaired, so a span naming an absent parent is not
     * and cannot be mistaken for a root — where v1 had to keep the claimed parent in a second column
     * because it degraded the first one to null, and a reader consulting only that first column measured
     * this turn as a 400ms one.
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
     * The token buckets as v2 STORES them: disjoint, with a null meaning the producer reported nothing.
     *
     * <p>v1's fixture wrote a provider's raw blob and let the read carve OpenAI's cache-inclusive input
     * count down. That correction moved to write time, so what a fixture must now seed is the corrected
     * value — and passing a still-inclusive one here would be seeding a row ingest never produces.
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
        return prices.costOf(model, usage)
                .map(java.math.BigDecimal::toPlainString)
                .orElse(null);
    }

    /** A finding's evidence blob as JSON. Read as JSON because Postgres re-serializes a jsonb value. */
    private static JsonNode evidence(FindingRow finding) {
        assertNotNull(finding.payloadJson(), "a distribution_shift finding always carries evidence");
        return finding.payload();
    }

    /** Parse a persisted sketch, failing the test rather than the null check when the column is empty. */
    /**
     * The one day the control ring holds after a single close. Asserted through the ring rather than
     * through a resolved control because the resolved view is WEIGHTED — a same-day slot weighs 1 and a
     * test asserting exact counts should not depend on that staying true tomorrow.
     */
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
        return baselines
                .find(projectId, signal.id(), Measure.TURN_DURATION, BucketKind.CALL_SITE, CALL_SITE)
                .orElseThrow();
    }
}
