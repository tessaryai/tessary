// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance for {@link MetricSource} against the real pgvector Postgres (Testcontainers), because every
 * decision it makes is a decision about SQL — which span the duration comes off, which kinds are priced,
 * and whether a column that ingestion never writes falls through to a derivation.
 *
 * <p>Two of these matter more than the rest, and PLAN.md §2 says so outright.
 *
 * <p><b>{@link #durationDerivesAndUnpricedCostAbstains()}</b> is the one that decides whether the
 * classifier works at all. {@code trace.latency_ms} is the intended source for duration and
 * the v1 write path passed literal null for it at the write site, so a detector that reads it
 * bare abstains on 100% of production traffic — and passes every test whose fixture filled it in. The
 * fixture here therefore leaves it <em>unset</em>, and the test asserts against the database that it
 * really is null before asserting anything about the reading. The same test pins cost's opposite rule:
 * a generation with no recorded price is never re-priced at read time, because a dollar figure that
 * moves with the deploy is one a pinned reference cannot be compared against.
 *
 * <p><b>{@link #unpricedModelAbstainsRatherThanReadingAsZero()}</b> is the one that decides whether the
 * number can be trusted when it does work. A model with no rate in the book is unpriced, not free:
 * reading it as $0 turns a price-book gap into a cost improvement, which is the one failure that makes
 * the measure worse than not having it.
 *
 * <p>Every trace is seeded old enough that {@code tracesAfter}'s settle window has passed, and heads are
 * taken through {@link BehaviorSubstrateRepository} rather than constructed, so the entry-point call site
 * these readings are filed under is the one the sweep would actually resolve.
 */
@SpringBootTest
class MetricSourceTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

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

    /** Old enough that nothing under test can confuse a seeded trace with live traffic. */
    private static final Instant T0 = Instant.now().minus(2, ChronoUnit.HOURS);

    /** In the price book: $2/M input, so the arithmetic below is checkable by hand. */
    private static final String PRICED_MODEL = "claude-sonnet-5";

    // -----------------------------------------------------------------------------------------------
    // The two a reviewer looks at first
    // -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("duration derives from the span; cost abstains unless it was priced on arrival")
    void durationDerivesAndUnpricedCostAbstains() {
        String pid = project("metric-src-derived");
        // Seeded exactly the way ingestion writes: no latency_ms, no total_cost. Filling them here would
        // make this test pass against a MetricSource that reads the columns and nothing else, which is
        // precisely the detector that never fires on real traffic.
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-research", T0, T0.plusMillis(3200))
                .spanId();
        insertLlmLeaf(pid, traceId, rootId, PRICED_MODEL, 1_000_000L, 0L);

        Tally tally = new Tally();
        TurnMetrics turn = onlyTurn(pid, tally);

        assertEquals("cs-research", turn.callSiteId(), "filed under the entry point's call site");
        assertEquals(Completion.COMPLETED, turn.completion());

        // The rollup HAS run — the sweep only reads settled traces — and duration still comes off the
        // root span, because trace.latency_ms is an envelope over the whole trace and this is not.
        assertTrue(costRollupIsNull(pid, traceId), "no span was priced, so the cost rollup stays null");

        Measurement.Present duration = present(turn.measurement(Measure.TURN_DURATION));
        assertEquals(Provenance.DERIVED, duration.provenance(), "the root span's own interval, always");
        assertEquals(3200.0, duration.value(), 1.0);

        // Cost is NOT re-derived from the leaf's usage. A dollar figure recomputed at read time moves
        // with the deploy that refreshes the price book, and a pinned reference holding older dollars
        // then reads as a fleet-wide regression nothing caused. A generation that arrived unpriced is
        // unscoreable, and stays that way.
        Measurement.Absent cost = assertInstanceOf(
                Measurement.Absent.class, turn.measurement(Measure.COST), "an unpriced leaf must not be re-priced");
        assertEquals(Absence.UNPRICED_MODEL, cost.reason());

        // The counters are the instrument that would have caught this in production: a measure sitting at
        // zero derived and zero from column is a measure that abstained on everything.
        assertEquals(0, tally.fromColumn(Measure.TURN_DURATION), "nothing came off a rollup column");
        assertEquals(1, tally.derived(Measure.TURN_DURATION));
        assertEquals(1.0, tally.abstentionRate(Measure.COST), 0.0);
        assertEquals(0.0, tally.abstentionRate(Measure.TURN_DURATION), 0.0);
    }

    @Test
    @DisplayName("an unpriced model abstains — it never joins the distribution at $0")
    void unpricedModelAbstainsRatherThanReadingAsZero() {
        String pid = project("metric-src-unpriced");
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(1500))
                .spanId();
        // One generation that arrived priced and one the book had no rate for, so ingest wrote no price.
        // The partial sum ($2.00) is never reported — not by the derivation and not by the rollup column
        // either, whose SUM skips the unpriced span and would report exactly that partial. It would
        // understate the turn by an unknown amount and put a plausible number into the distribution,
        // which is worse than the honest gap.
        insertLlmLeaf(pid, traceId, rootId, PRICED_MODEL, 1_000_000L, 0L, null, null, "2.00");
        insertLlmLeaf(pid, traceId, rootId, "some-bespoke-local-model", 500_000L, 10L);

        Tally tally = new Tally();
        TurnMetrics turn = onlyTurn(pid, tally);

        Measurement cost = turn.measurement(Measure.COST);
        Measurement.Absent absent = assertInstanceOf(
                Measurement.Absent.class, cost, "an unpriced model must not read as a value of any kind");
        assertEquals(Absence.UNPRICED_MODEL, absent.reason());
        assertEquals(1, tally.absent(Measure.COST, Absence.UNPRICED_MODEL));
        assertEquals(1.0, tally.abstentionRate(Measure.COST), 0.0, "cost abstained on all of its traffic");

        // Duration is unaffected: the price book has nothing to do with how long the turn took, and one
        // measure abstaining must never take the others down with it.
        assertEquals(1500.0, present(turn.measurement(Measure.TURN_DURATION)).value(), 1.0);
        // The token buckets are still readings. We know the volume even where we do not know the price —
        // the same posture vitals takes, counting unpriced calls rather than dropping their tokens.
        assertEquals(1500000.0, present(turn.measurement(Measure.TOK_INPUT)).value(), 0.0);
    }

    @Test
    @DisplayName("a leaf priced on arrival is read at that price, not re-priced against today's book")
    void aLeafPricedOnArrivalIsNotRepriced() {
        String pid = project("metric-src-priced-on-arrival");
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(1500))
                .spanId();
        // The same million input tokens today's book prices at $2.00, but stamped at arrival under a book
        // that charged half that. The stored number wins — which is the whole point: two windows are only
        // comparable when both were priced under the same rates, and re-pricing history against today's
        // book is what made a rate refresh read as a fleet-wide cost regression.
        insertLlmLeaf(pid, traceId, rootId, PRICED_MODEL, 1_000_000L, 0L, null, null, "1.00");

        Tally tally = new Tally();
        Measurement.Present cost = present(onlyTurn(pid, tally).measurement(Measure.COST));

        assertEquals(1.00, cost.value(), 1e-9, "the price it was billed at, not the price it would cost today");
        assertEquals(Provenance.COLUMN, cost.provenance(), "every cost is recorded, never derived");
        assertEquals(1, tally.fromColumn(Measure.COST));
        assertEquals(0, tally.derived(Measure.COST));
    }

    // -----------------------------------------------------------------------------------------------
    // The duration rules PROGRAM.md §13 warns about
    // -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a populated rollup latency is refused for duration, because it is an envelope")
    void aPopulatedRollupLatencyIsRefusedBecauseItIsAnEnvelope() {
        String pid = project("metric-src-column");
        // v1 preferred trace.latency_ms and nothing ever wrote it, so the preference was dead code that
        // read as a rule. v2's rollup worker writes it for real — as max(ended_at) - min(started_at) over
        // the trace — which is the envelope turn duration exists to reject. Promoting the rollups to real
        // numbers without deleting the preference would have inflated p95 on every turn with an async
        // child, silently, and passed every fixture that seeded a single span.
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(999))
                .spanId();
        insertObs(pid, traceId, rootId, "tool", "fire_and_forget", null, T0.plusMillis(10), T0.plusMillis(4321));

        Tally tally = new Tally();
        Measurement.Present duration = present(onlyTurn(pid, tally).measurement(Measure.TURN_DURATION));

        assertEquals(
                4321L,
                rollupLatencyMs(pid, traceId),
                "the worker really did write an envelope, and it really does disagree with the root");
        assertEquals(999.0, duration.value(), 1.0, "the root's own interval is what the user waited");
        assertEquals(Provenance.DERIVED, duration.provenance(), "there is no column arm for this measure");
        assertEquals(0, tally.fromColumn(Measure.TURN_DURATION));
        assertEquals(1, tally.derived(Measure.TURN_DURATION));
    }

    @Test
    @DisplayName("duration is the earliest root's own interval, never the max(end) - min(start) envelope")
    void durationIsTheRootsOwnIntervalNotTheEnvelope() {
        String pid = project("metric-src-envelope");
        String traceId = seedTurn(pid);
        // The real entry point: 2s long, starting first.
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(2000))
                .spanId();
        // A second parentless span — an orphan whose parent never landed, which ~0.1% of traces carry.
        // Starting later, it loses; taking it would file this turn under a different duration entirely.
        insertObs(pid, traceId, null, "agent", "stray", "cs-a", T0.plusMillis(500), T0.plusMillis(9000));
        // An async child outliving its parent. Measured against production, letting spans like this set
        // the end of the interval inflated p95 by ~10% versus the root span.
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

        // Present in the page — excluding it would silently remove precisely the traffic a duration
        // detector most wants to see, and a rising count of stuck turns would then read as faster latency.
        assertEquals(Completion.UNTERMINATED, turn.completion());
        assertEquals(1, tally.completions(Completion.UNTERMINATED));
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
        // Both endpoints present, the end BEFORE the start: clock skew between the host that stamped the
        // start and the host that stamped the end, which is routine across a distributed trace, or a
        // re-exported span whose started_at was rewritten. `ended_at IS NULL` is false, so nothing
        // upstream of the reading calls this unfinished.
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.minusMillis(3000))
                .spanId();
        insertObs(pid, traceId, rootId, "tool", "search", null, T0.plusMillis(10), T0.minusMillis(90));

        Tally tally = new Tally();
        List<TraceHead> heads = heads(pid);
        TurnMetrics turn = source.turnMetrics(pid, heads, tally).get(0);
        ToolMetrics tool = source.toolMetrics(pid, heads, tally).get(0);

        // Its own category, not NO_END_TIME: the end time is right there, and a skewed clock is not the
        // same operational fact as a span that never finished.
        assertEquals(Completion.COMPLETED, turn.completion());
        assertEquals(
                Absence.NEGATIVE_INTERVAL,
                assertInstanceOf(Measurement.Absent.class, turn.measurement(Measure.TURN_DURATION))
                        .reason());
        assertEquals(
                Absence.NEGATIVE_INTERVAL,
                assertInstanceOf(Measurement.Absent.class, tool.duration()).reason());
        // The point of the abstention. `Math.log(-3000)` is NaN, MetricSketch.add throws on NaN by
        // contract, and that throw would propagate out of the sweep before its cursor advanced — every
        // retry re-reading the same page and hitting the same span until the signal dead-lettered.
        assertTrue(Double.isNaN(Math.log(-3000.0)), "the value this guard exists to keep out of add()");
    }

    // -----------------------------------------------------------------------------------------------
    // Token buckets
    // -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("stored token buckets are already disjoint and are never corrected a second time")
    void storedCacheReadsAreNotSubtractedTwice() {
        String pid = project("metric-src-openai");
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(800))
                .spanId();
        // OpenAI reports a cache-INCLUSIVE input count: 1,000,000 total, of which 400,000 came from cache.
        // v1 stored the provider's number raw and carved the overlap out on every read; v2 does it once,
        // at write time in IngestPricer, so what is stored is the disjoint 600,000 — and this must add it
        // up rather than subtract the cache reads a second time and report 200,000.
        insertLlmLeaf(pid, traceId, rootId, "gpt-4o", 600_000L, 0L, 400_000L, null, null);

        TurnMetrics turn = onlyTurn(pid, new Tally());

        assertEquals(
                600000.0,
                present(turn.measurement(Measure.TOK_INPUT)).value(),
                0.0,
                "the stored input bucket, summed as-is — correcting it again would double the discount");
        assertEquals(400000.0, present(turn.measurement(Measure.TOK_CACHE_READ)).value(), 0.0);
        // The ratio is the form the cache collapse is watched in — it does not move with traffic volume.
        assertEquals(0.4, present(turn.cacheReadRatio()).value(), 1e-9);
    }

    @Test
    @DisplayName("a bucket the provider never reported is absent; a reported zero is a value")
    void unreportedCacheWriteIsAbsentAndAReportedZeroIsNot() {
        String pid = project("metric-src-buckets");
        String traceId = seedTurn(pid);
        String rootId = insertObs(pid, traceId, null, "agent", "loop", "cs-a", T0, T0.plusMillis(800))
                .spanId();
        // OpenAI's automatic caching has no write charge and emits no write count at all, while the
        // cache-read count is present and zero. Those are two different sentences, and only one of them is
        // a measurement — the zero IS the prompt-prefix regression this program exists to catch. In v2 the
        // difference is a null column against a zero one, rather than a missing key against a present one.
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
        // The case a present column alone cannot answer: an SDK that stamps every usage attribute it knows
        // about writes a literal 0 here for a provider whose caching is automatic and uncounted. Reading
        // that as "zero writes" would put a fabricated measurement into the distribution, and a call site
        // that later moved to a provider which DOES count writes would show a jump out of nowhere.
        insertLlmLeaf(pid, traceId, rootId, "gpt-4o", 1000L, 50L, null, 0L, null);

        assertEquals(
                Absence.BUCKET_NOT_REPORTED,
                assertInstanceOf(
                                Measurement.Absent.class,
                                onlyTurn(pid, new Tally()).measurement(Measure.TOK_CACHE_WRITE))
                        .reason(),
                "gpt-4o carries no cache-creation rate, so a 0 there is bookkeeping rather than a count");

        // The same blob on a model the book charges for cache creation. Here the zero is real — the turn
        // genuinely wrote nothing to the cache — and it has to reach the sketch, because a cache-write
        // count falling to zero is one of the shapes the prompt-prefix regression takes.
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

    // -----------------------------------------------------------------------------------------------
    // Tool grain
    // -----------------------------------------------------------------------------------------------

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
        // An llm leaf is deliberately present and deliberately absent from the result: ActionSymbol
        // collapses every generation to one symbol, so an `llm` bucket would pool the whole project.
        insertLlmLeaf(pid, traceId, rootId, PRICED_MODEL, 10L, 5L);

        Tally tally = new Tally();
        List<ToolMetrics> tools = source.toolMetrics(pid, heads(pid), tally);

        assertEquals(1, tools.size(), "only the dispatchable span; the llm leaf and the root are not tool buckets");
        ToolMetrics tool = tools.get(0);
        assertEquals("tool:search_docs", tool.bucketKey(), "the tool_call name wins over the span name");
        assertEquals("cs-search", tool.callSiteId(), "the TURN's entry point, so §6.1 can ask if this explains it");
        assertEquals(34000.0, present(tool.duration()).value(), 1.0);
        assertEquals(1, tally.derived(Measure.TOOL_DURATION));
    }

    // -----------------------------------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------------------------------

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug).project().id();
    }

    /**
     * The head read is what the sweep uses, so the call site under test is the one it would resolve.
     *
     * <p>Every seeded trace is put through the REAL rollup first. The sweep reads settled traces only,
     * and settling is the rollup worker's job — so this stands in for the worker at exactly the moment
     * production would have run it. Doing it here rather than at each seed site means no test can quietly
     * assert against a trace the sweep would never have been shown.
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

    /** Asked of the table itself, so a claim about what the rollup holds stays a fact rather than a hope. */
    private @Nullable Long rollupLatencyMs(String projectId, String traceId) {
        return jdbc.sql("SELECT latency_ms FROM trace WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", traceId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    /** Likewise for the cost rollup, which is null exactly when no span of the trace was priced. */
    private boolean costRollupIsNull(String projectId, String traceId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT total_cost IS NULL FROM trace WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", traceId)
                .query(Boolean.class)
                .single());
    }

    /** A session and the trace of one turn. Rollups are the worker's to write, so none are seeded. */
    private String seedTurn(String projectId) {
        String sessionId = SubstrateV2Fixtures.sessionId();
        String traceId = SubstrateV2Fixtures.traceId();
        fx.trace(projectId, traceId, sessionId, T0);
        return traceId;
    }

    /** An llm leaf with typed token buckets — no usage blob, because there is no usage blob any more. */
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
     * An llm leaf carrying whichever buckets the producer reported. Null and 0 are different facts on
     * every one of them: null is "this provider does not report it", 0 is a measurement.
     *
     * @param totalCost the price stamped at arrival, as {@code input_cost}. Null leaves the span
     *     {@code unpriced}, which is what ingest writes for a model the book holds no rate for.
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

    /** A root span the producer opened and never closed — {@code ended_at} genuinely NULL. */
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
