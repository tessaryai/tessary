// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricSource.Absence;
import ai.tessary.classifier.metric.MetricSource.Completion;
import ai.tessary.classifier.metric.MetricSource.Measurement;
import ai.tessary.classifier.metric.MetricSource.Provenance;
import ai.tessary.classifier.metric.MetricSource.Tally;
import ai.tessary.classifier.metric.MetricSource.ToolMetrics;
import ai.tessary.classifier.metric.MetricSource.TurnMetrics;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository.TraceHead;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link MetricSource} against real Postgres, because each of its decisions is about SQL: which span duration comes
 * off, which kinds are priced, and whether an unwritten column falls through to a derivation.
 *
 * <p>{@link #durationDerivesAndUnpricedCostAbstains()} decides whether the classifier works at all: ingest writes
 * {@code trace.latency_ms} as null, so a detector reading it bare abstains on all real traffic while passing tests
 * that fill it in. The fixture leaves it null and checks that first.
 *
 * <p>{@link #unpricedModelAbstainsRatherThanReadingAsZero()} decides whether the number can be trusted: an unpriced
 * model read as $0 turns a price-book gap into a cost improvement.
 */
@SpringBootTest
class MetricSourceTest {

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    TenantService tenants;

    @Autowired
    MetricSource source;

    @Autowired
    BehaviorSubstrateRepository substrate;

    @Autowired
    JdbcClient jdbc;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    private static final Instant T0 = Instant.now().minus(2, ChronoUnit.HOURS);

    /** In the price book at $2/M input, so the arithmetic is checkable by hand. */
    private static final String PRICED_MODEL = "claude-sonnet-5";

    @Test
    @DisplayName("duration derives from the span; cost abstains unless it was priced on arrival")
    void durationDerivesAndUnpricedCostAbstains() {
        String pid = project("metric-src-derived");
        // Seeded as ingest writes: no latency_ms, no total_cost. Filling them would let a column-only MetricSource
        // pass.
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-research", T0, T0.plusMillis(3200))
                .spanId();
        insertLlmLeaf(pid, traceId, rootId, PRICED_MODEL, 1_000_000L, 0L);

        Tally tally = new Tally();
        TurnMetrics turn = onlyTurn(pid, tally);

        assertEquals("cs-research", turn.callSiteId(), "filed under the entry point's call site");
        assertEquals(Completion.COMPLETED, turn.completion());

        // The rollup ran, and duration still comes off the root span, not the trace envelope.
        assertTrue(costRollupIsNull(pid, traceId), "no span was priced, so the cost rollup stays null");

        Measurement.Present duration = present(turn.measurement(Measure.TURN_DURATION));
        assertEquals(Provenance.DERIVED, duration.provenance(), "the root span's own interval, always");
        assertEquals(3200.0, duration.value(), 1.0);

        // Cost is never re-derived at read time: a figure that moves with each price-book refresh would make a pinned
        // reference read as a fleet-wide regression.
        Measurement.Absent cost = assertInstanceOf(
                Measurement.Absent.class, turn.measurement(Measure.COST), "an unpriced leaf must not be re-priced");
        assertEquals(Absence.UNPRICED_MODEL, cost.reason());

        // A measure at zero column and zero derived abstained on everything; these counters are the production
        // signal.
        assertEquals("turn_duration n=1 (column 0, derived 1)", summaryOf(tally, Measure.TURN_DURATION));
        assertEquals("cost n=0 (column 0, derived 0) absent {UNPRICED_MODEL=1}", summaryOf(tally, Measure.COST));
    }

    @Test
    @DisplayName("an unpriced model abstains — it never joins the distribution at $0")
    void unpricedModelAbstainsRatherThanReadingAsZero() {
        String pid = project("metric-src-unpriced");
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(1500))
                .spanId();
        // One priced generation and one unpriced. The partial $2.00 is never reported, by derivation or by the
        // rollup's SUM: an understated but plausible number is worse than the gap.
        insertLlmLeaf(pid, traceId, rootId, PRICED_MODEL, 1_000_000L, 0L, null, null, "2.00");
        insertLlmLeaf(pid, traceId, rootId, "some-bespoke-local-model", 500_000L, 10L);

        Tally tally = new Tally();
        TurnMetrics turn = onlyTurn(pid, tally);

        Measurement cost = turn.measurement(Measure.COST);
        Measurement.Absent absent = assertInstanceOf(
                Measurement.Absent.class, cost, "an unpriced model must not read as a value of any kind");
        assertEquals(Absence.UNPRICED_MODEL, absent.reason());
        assertEquals(
                "cost n=0 (column 0, derived 0) absent {UNPRICED_MODEL=1}",
                summaryOf(tally, Measure.COST),
                "cost abstained on all of its traffic");

        // One measure abstaining never takes the others down.
        assertEquals(1500.0, present(turn.measurement(Measure.TURN_DURATION)).value(), 1.0);
        // Token buckets still read: volume is known where price is not, as vitals counts unpriced calls.
        assertEquals(1500000.0, present(turn.measurement(Measure.TOK_INPUT)).value(), 0.0);
    }

    @Test
    @DisplayName("a leaf priced on arrival is read at that price, not re-priced against today's book")
    void aLeafPricedOnArrivalIsNotRepriced() {
        String pid = project("metric-src-priced-on-arrival");
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(1500))
                .spanId();
        // Stamped at arrival under a book at half today's price; the stored number wins, because re-pricing history
        // made a rate refresh read as a cost regression.
        insertLlmLeaf(pid, traceId, rootId, PRICED_MODEL, 1_000_000L, 0L, null, null, "1.00");

        Tally tally = new Tally();
        Measurement.Present cost = present(onlyTurn(pid, tally).measurement(Measure.COST));

        assertEquals(1.00, cost.value(), 1e-9, "the price it was billed at, not the price it would cost today");
        assertEquals(Provenance.COLUMN, cost.provenance(), "every cost is recorded, never derived");
        assertEquals("cost n=1 (column 1, derived 0)", summaryOf(tally, Measure.COST));
    }

    @Test
    @DisplayName("duration is the earliest root's own interval, never the max(end) - min(start) envelope")
    void durationIsTheRootsOwnIntervalNotTheEnvelope() {
        String pid = project("metric-src-envelope");
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(2000))
                .spanId();
        // An orphan parentless span (about 0.1% of traces), starting later, so it loses.
        insertObs(pid, traceId, null, "agent", "stray", "cs-a", T0.plusMillis(500), T0.plusMillis(9000));
        // An async child outliving its parent; letting it set the end inflated p95 by about 10% in production.
        insertObs(pid, traceId, rootId, "tool", "fire_and_forget", null, T0.plusMillis(100), T0.plusSeconds(30));

        Measurement.Present duration = present(onlyTurn(pid, new Tally()).measurement(Measure.TURN_DURATION));
        assertEquals(2000.0, duration.value(), 1.0, "the root's own interval; the envelope would read 30,000");
        assertEquals(
                30_000L,
                rollupLatencyMs(pid, traceId),
                "and the envelope really is there, holding 30,000 — the reading refused it rather than "
                        + "never having been offered it");
    }

    @Test
    @DisplayName("a turn whose root never ended is counted as its own category, never dropped")
    void unfinishedTurnsAreCountedRatherThanExcluded() {
        String pid = project("metric-src-unfinished");
        String traceId = seedTurn(pid);
        unterminatedRoot(pid, traceId);

        Tally tally = new Tally();
        TurnMetrics turn = onlyTurn(pid, tally);

        // Kept in the page: dropping stuck turns would make their rise read as faster latency.
        assertEquals(Completion.UNTERMINATED, turn.completion());
        assertEquals("turns {UNTERMINATED=1}", summaryOf(tally, "turns"));
        assertEquals(
                Absence.NO_END_TIME,
                assertInstanceOf(Measurement.Absent.class, turn.measurement(Measure.TURN_DURATION))
                        .reason());
    }

    @Test
    @DisplayName("a span whose interval runs backwards abstains rather than reaching the sketch as NaN")
    void clockSkewedSpansAbstainRatherThanPoisoningTheSketch() {
        String pid = project("metric-src-skew");
        String traceId = seedTurn(pid);
        // End before start: clock skew across hosts or a rewritten started_at. {@code ended_at} is set, so nothing
        // upstream calls it unfinished.
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.minusMillis(3000))
                .spanId();
        insertObs(pid, traceId, rootId, "tool", "search", null, T0.plusMillis(10), T0.minusMillis(90));

        Tally tally = new Tally();
        List<TraceHead> heads = heads(pid);
        TurnMetrics turn = source.turnMetrics(pid, heads, tally).get(0);
        ToolMetrics tool = source.toolMetrics(pid, heads, tally).get(0);

        // Its own category: a skewed clock is not a span that never finished.
        assertEquals(Completion.COMPLETED, turn.completion());
        assertEquals(
                Absence.NEGATIVE_INTERVAL,
                assertInstanceOf(Measurement.Absent.class, turn.measurement(Measure.TURN_DURATION))
                        .reason());
        assertEquals(
                Absence.NEGATIVE_INTERVAL,
                assertInstanceOf(Measurement.Absent.class, tool.duration()).reason());
        // {@code Math.log(-3000)} is NaN, and MetricSketch.add throws on NaN, which would stop the cursor and dead-
        // letter the signal.
        assertTrue(Double.isNaN(Math.log(-3000.0)), "the value this guard exists to keep out of add()");
    }

    @Test
    @DisplayName("stored token buckets are already disjoint and are never corrected a second time")
    void storedCacheReadsAreNotSubtractedTwice() {
        String pid = project("metric-src-openai");
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(800))
                .spanId();
        // OpenAI's input count includes cache reads; v2 carves them out once at write time, so this must add the
        // stored 600,000, not subtract again.
        insertLlmLeaf(pid, traceId, rootId, "gpt-4o", 600_000L, 0L, 400_000L, null, null);

        TurnMetrics turn = onlyTurn(pid, new Tally());

        assertEquals(
                600000.0,
                present(turn.measurement(Measure.TOK_INPUT)).value(),
                0.0,
                "the stored input bucket, summed as-is — correcting it again would double the discount");
        assertEquals(400000.0, present(turn.measurement(Measure.TOK_CACHE_READ)).value(), 0.0);
        // The ratio does not move with traffic volume, so it is the form the cache collapse is watched in.
        assertEquals(0.4, present(turn.cacheReadRatio()).value(), 1e-9);
    }

    @Test
    @DisplayName("a bucket the provider never reported is absent; a reported zero is a value")
    void unreportedCacheWriteIsAbsentAndAReportedZeroIsNot() {
        String pid = project("metric-src-buckets");
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(800))
                .spanId();
        // No write charge means no write count (null), while a present zero cache-read count is the prompt-prefix
        // regression itself.
        insertLlmLeaf(pid, traceId, rootId, "gpt-4o", 1000L, 50L, 0L, null, null);

        TurnMetrics turn = onlyTurn(pid, new Tally());

        assertEquals(0.0, present(turn.measurement(Measure.TOK_CACHE_READ)).value(), 0.0, "reported zero is a value");
        assertEquals(
                Absence.BUCKET_NOT_REPORTED,
                assertInstanceOf(Measurement.Absent.class, turn.measurement(Measure.TOK_CACHE_WRITE))
                        .reason(),
                "'not reported' must not be laundered into 'no writes'");
    }

    @Test
    @DisplayName("a reported zero cache-write counts only where the model is billed for cache creation")
    void aZeroCacheWriteIsAMeasurementOnlyWhereCreationIsBilled() {
        String pid = project("metric-src-cache-write-family");
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(800))
                .spanId();
        // An SDK stamping every attribute writes 0 for uncounted automatic caching; reading it as zero writes
        // fabricates a measurement.
        insertLlmLeaf(pid, traceId, rootId, "gpt-4o", 1000L, 50L, null, 0L, null);

        assertEquals(
                Absence.BUCKET_NOT_REPORTED,
                assertInstanceOf(
                                Measurement.Absent.class,
                                onlyTurn(pid, new Tally()).measurement(Measure.TOK_CACHE_WRITE))
                        .reason(),
                "gpt-4o carries no cache-creation rate, so a 0 there is bookkeeping rather than a count");

        // On a model billed for cache creation the zero is real and must reach the sketch: falling cache writes are
        // one shape of the regression.
        String billedPid = project("metric-src-cache-write-billed");
        String billedTrace = seedTurn(billedPid);
        String billedRoot = insertObs(billedPid, billedTrace, null, "agent", "loop", "cs-b", T0, T0.plusMillis(800))
                .spanId();
        insertLlmLeaf(billedPid, billedTrace, billedRoot, PRICED_MODEL, 1000L, 50L, null, 0L, null);

        assertEquals(
                0.0,
                present(onlyTurn(billedPid, new Tally()).measurement(Measure.TOK_CACHE_WRITE))
                        .value(),
                0.0,
                "a billed zero is a value");
    }

    /**
     * A turn missing a prompt bucket, or with a zero prompt, has no share; read as 0% it would look like the cache
     * collapsing.
     */
    @ParameterizedTest
    @CsvSource(
            nullValues = "NONE",
            value = {"1000, NONE", "NONE, 400", "0, 0"})
    @DisplayName("a turn without both prompt buckets, or with no prompt, has no cache-read share")
    void aTurnWithoutBothPromptBucketsHasNoCacheReadShare(@Nullable Long input, @Nullable Long cacheRead) {
        String pid = project("metric-src-no-share");
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(800))
                .spanId();
        insertLlmLeaf(pid, traceId, rootId, "gpt-4o", input, 50L, cacheRead, null, null);

        TurnMetrics turn = onlyTurn(pid, new Tally());

        assertEquals(
                Absence.BUCKET_NOT_REPORTED,
                assertInstanceOf(Measurement.Absent.class, turn.cacheReadRatio())
                        .reason());
        assertNull(turn.tokenReadings().cacheReadPct(), "no share is folded into the tokens sketch");
    }

    @Test
    @DisplayName("tool spans are keyed by ActionSymbol and carry their turn's call site for suppression")
    void toolSpansAreKeyedByActionSymbol() {
        String pid = project("metric-src-tools");
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-search", T0, T0.plusSeconds(38))
                .spanId();
        SpanRef toolSpan =
                insertObs(pid, traceId, rootId, "tool", "span-name-nobody-reads", null, T0, T0.plusMillis(34000));
        fx.toolCall(pid, toolSpan, "search_docs", null, T0);
        // An llm leaf is present but absent from the result: ActionSymbol collapses generations to one symbol, which
        // would pool the whole project.
        insertLlmLeaf(pid, traceId, rootId, PRICED_MODEL, 10L, 5L);

        Tally tally = new Tally();
        List<ToolMetrics> tools = source.toolMetrics(pid, heads(pid), tally);

        assertEquals(1, tools.size(), "only the dispatchable span; the llm leaf and the root are not tool buckets");
        ToolMetrics tool = tools.get(0);
        assertEquals("tool:search_docs", tool.bucketKey(), "the tool_call name wins over the span name");
        assertEquals("cs-search", tool.callSiteId(), "the TURN's entry point, so §6.1 can ask if this explains it");
        assertEquals(34000.0, present(tool.duration()).value(), 1.0);
        assertEquals("tool_duration n=1 (column 0, derived 1)", summaryOf(tally, Measure.TOOL_DURATION));
    }

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug).project().id();
    }

    /**
     * The sweep's head read, after putting every seeded trace through the real rollup, since the sweep reads settled
     * traces only.
     */
    private List<TraceHead> heads(String projectId) {
        for (String traceId : jdbc.sql("SELECT id FROM trace WHERE project_id = :pid")
                .param("pid", projectId)
                .query(String.class)
                .list()) {
            fx.rollup(projectId, traceId);
        }
        return substrate.tracesAfter(projectId, null, null, 100);
    }

    private TurnMetrics onlyTurn(String projectId, Tally tally) {
        List<TurnMetrics> turns = source.turnMetrics(projectId, heads(projectId), tally);
        assertEquals(1, turns.size(), "one seeded trace, one reading — a page must never silently shrink");
        return turns.get(0);
    }

    private static Measurement.Present present(Measurement measurement) {
        return assertInstanceOf(
                Measurement.Present.class, measurement, "expected a value, got " + measurement + " instead");
    }

    /** The {@link Tally#summary()} clause naming {@code key}. */
    private static String summaryOf(Tally tally, String key) {
        for (String clause : tally.summary().split("; ")) {
            if (clause.startsWith(key + " ")) return clause;
        }
        throw new AssertionError("no " + key + " clause in: " + tally.summary());
    }

    private @Nullable Long rollupLatencyMs(String projectId, String traceId) {
        return jdbc.sql("SELECT latency_ms FROM trace WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", traceId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    /** Null exactly when no span of the trace was priced. */
    private boolean costRollupIsNull(String projectId, String traceId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT total_cost IS NULL FROM trace WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", traceId)
                .query(Boolean.class)
                .single());
    }

    /** A session and one turn's trace; rollups are the worker's to write. */
    private String seedTurn(String projectId) {
        String sessionId = SubstrateV2Fixtures.sessionId();
        String traceId = SubstrateV2Fixtures.traceId();
        fx.trace(projectId, traceId, sessionId, T0);
        return traceId;
    }

    private SpanRef insertLlmLeaf(
            String projectId,
            String traceId,
            String parentId,
            String model,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens) {
        return insertLlmLeaf(projectId, traceId, parentId, model, inputTokens, outputTokens, null, null, null);
    }

    /**
     * An llm leaf with whichever buckets the producer reported; null means unreported, 0 is a measurement. A null
     * {@code totalCost} leaves the span {@code unpriced}, as ingest does for a model with no rate.
     */
    private SpanRef insertLlmLeaf(
            String projectId,
            String traceId,
            String parentId,
            String model,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable Long cacheReadTokens,
            @Nullable Long cacheWriteTokens,
            @Nullable String totalCost) {
        return fx.spanSeed(projectId)
                .traceId(traceId)
                .parentSpanId(parentId)
                .kind("llm")
                .name("chat")
                .model(model)
                .at(T0)
                .endedAt(T0.plusMillis(500))
                .usage(inputTokens, outputTokens)
                .cacheUsage(cacheReadTokens, cacheWriteTokens)
                .cost(totalCost, null, totalCost == null ? SpanRow.CostSource.UNPRICED : SpanRow.CostSource.INFERRED)
                .writeRef();
    }

    /** A root span that never closed. */
    private SpanRef unterminatedRoot(String projectId, String traceId) {
        return fx.spanSeed(projectId)
                .traceId(traceId)
                .kind("agent")
                .name("loop")
                .callSiteId("cs-a")
                .at(T0)
                .unterminated()
                .writeRef();
    }

    private SpanRef insertObs(
            String projectId,
            String traceId,
            @Nullable String parentId,
            String kind,
            String name,
            @Nullable String callSiteId,
            Instant startedAt,
            @Nullable Instant endedAt) {
        return fx.spanSeed(projectId)
                .traceId(traceId)
                .parentSpanId(parentId)
                .kind(kind)
                .name(name)
                .callSiteId(callSiteId)
                .at(startedAt)
                .endedAt(endedAt)
                .writeRef();
    }
}
