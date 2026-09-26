// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.ingest.CallSiteRegistry;
import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.MediaExternalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.storage.MediaRefRepository;
import ai.tessary.storage.RetrievedDocRepository;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.ToolCallRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Row;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The v2 write path against the real schema: what one drained batch does to {@code session / trace / span /
 * span_payload}, and three spec invariants. §6.1: a span row and its trace re-arm commit together or not at all.
 * §6.2: a completed span replaces its partial, an older redelivery cannot undo it, and neither overwrites platform-
 * derived fields. §7.1: one trace update per trace per batch, with sorted locking so overlapping batches cannot
 * deadlock.
 *
 * <p>The resolvers and rollup worker are off: several assertions are about what the write path did not derive, and
 * the worker claims due traces globally.
 */
@SpringBootTest(
        properties = {
            "tessary.ingest.substrate.resolvers-enabled=false",
            "tessary.ingest.substrate.rollup-enabled=false"
        })
class SpanBatchWriterIntegrationTest {

    @Autowired
    TenantService tenants;

    @Autowired
    SpanBatchWriter writer;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    ToolCallRepository toolCalls;

    @Autowired
    RetrievedDocRepository retrievedDocs;

    @Autowired
    SpanSideTables sideTables;

    @Autowired
    IngestPricer pricer;

    @Autowired
    MediaExternalizer mediaExternalizer;

    @Autowired
    MediaRefRepository mediaRefs;

    @Autowired
    CallSiteRegistry callSites;

    @Autowired
    SpanLateness lateness;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient jdbc;

    private String pid;
    private Instant t0;

    @BeforeEach
    void setUp() {
        pid = TenantFixture.bootstrap(tenants, "span-batch").project().id();
        t0 = Instant.parse("2026-08-12T10:00:00Z");
    }

    @Test
    @DisplayName("one batch lands session, trace and spans, with the payload split off the row")
    void write_landsTheWholeSpine() {
        String traceId = traceId("a");
        writer.write(
                pid,
                List.of(
                        span("root", null, traceId, KindNormalizer.AGENT, t0, t0.plusSeconds(4), meta("sess-1")),
                        span(
                                "child",
                                "root",
                                traceId,
                                KindNormalizer.LLM,
                                t0.plusSeconds(1),
                                t0.plusSeconds(3),
                                meta("sess-1"))));

        assertNotNull(sessions.findById(pid, "sess-1").orElse(null), "the producer sent a session, so one exists");
        TraceV2Row trace = traces.findById(pid, traceId).orElseThrow();
        assertEquals(t0.toString(), trace.startedAt(), "the trace starts when its earliest span did");
        assertEquals(t0.plusSeconds(4).toString(), trace.endedAt());
        assertTrue(trace.hasRootSpan(), "a parentless span is a root, and the rollup timer reads that");
        assertNotNull(trace.rollupDueAt(), "the batch armed the trace in the same transaction as its spans");
        assertNull(trace.spanCount(), "no arrival writes a rollup column — those are the worker's alone");

        List<SpanRow> written = spans.listByTrace(pid, traceId);
        assertEquals(2, written.size());
        SpanRow root = written.get(0);
        assertEquals("root", root.id());
        assertEquals("sess-1", root.sessionId());
        assertTrue(root.isLogicalRoot());
        assertEquals(4000L, root.latencyMs(), "latency is derived at write, not on read");
        assertEquals(
                t0.plusSeconds(4).toString(),
                root.eventTs(),
                "event_ts is the end when the producer sent one — the stamp LWW versions by");
        assertNull(root.path(), "ancestry is the resolver's; a null path means unresolved, never root");

        SpanPayloadRow payload = payloads.find(pid, traceId, "root").orElseThrow();
        assertEquals("in-root", payload.input());
        assertNotNull(payload.attributes(), "the producer's whole attribute bag survives off-row");
        assertEquals("in-root", root.inputPreview(), "the preview is cut once, at write, so the list is one table");
    }

    @Test
    @DisplayName("§6.1 — a batch that fails to commit leaves no trace or session row behind")
    void write_failedBatchLeavesNoIdentityRows() {
        String traceId = traceId("uncommittable");
        // Valid JSON Postgres will not take as jsonb: a \u0000 escape, from a real web search result. It failed a
        // batch mid-transaction and left the shell this test forbids.
        RawEntry uncommittable = new RawEntry(
                "tool-1",
                "execute_tool",
                "{\"result\":\"\\u0000\"}",
                "out-tool-1",
                null,
                meta("sess-uncommittable"),
                null,
                traceId,
                t0.toString(),
                KindNormalizer.TOOL,
                t0.toString(),
                null,
                null);

        assertThrows(RuntimeException.class, () -> writer.write(pid, List.of(uncommittable)));

        assertTrue(
                traces.findById(pid, traceId).isEmpty(),
                "the trace row is folded from the spans, so a batch that never committed leaves no trace: a "
                        + "shell can never gain a span, and one that cannot settle is re-armed by the reaper "
                        + "for the life of the database");
        assertNull(
                sessions.findById(pid, "sess-uncommittable").orElse(null),
                "the session identity row rides the same transaction for the same reason");
        assertTrue(spans.findById(pid, traceId, "tool-1").isEmpty(), "and no half of the span survived either");
    }

    @Test
    @DisplayName("rows no v2 row could represent are dropped before the transaction, and the batch still lands")
    void write_poisonRowsAreDroppedNotThrown() {
        String traceId = traceId("poison");
        // Its own writer, so the drop counter is this test's.
        SpanBatchWriter isolated = newWriter(traces);
        List<RawEntry> batch = new ArrayList<>();
        batch.add(span("ok", null, traceId, KindNormalizer.LLM, t0, t0, Map.of()));
        batch.add(new RawEntry(null, "no id", "i", "o", null, Map.of(), null, traceId, t0.toString(), null));
        batch.add(new RawEntry("no-start", "n", "i", "o", null, Map.of(), null, traceId, null, null));
        batch.add(new RawEntry("x".repeat(600), "n", "i", "o", null, Map.of(), null, traceId, t0.toString(), null));

        assertEquals(1, isolated.write(pid, batch), "one writable row of four");
        assertEquals(3, isolated.droppedSpans(), "no id, no start time, and an id that is not an identifier");
        assertEquals(1, spans.listByTrace(pid, traceId).size(), "a poison row costs only itself");
    }

    @Test
    @DisplayName("an oversized payload or session id drops only its own span, and a leap-second start is kept")
    void write_oversizedRowsAreDroppedAndALeapSecondStartIsKept() {
        String traceId = traceId("oversized");
        SpanBatchWriter isolated = newWriter(traces);
        List<RawEntry> batch = new ArrayList<>();
        batch.add(span("ok", null, traceId, KindNormalizer.LLM, t0, t0, Map.of()));
        batch.add(new RawEntry(
                "huge-input", "n", "x".repeat(8_000_001), "o", null, Map.of(), null, traceId, t0.toString(), null));
        batch.add(span("long-session", null, traceId, KindNormalizer.LLM, t0, t0, meta("s".repeat(513))));
        batch.add(new RawEntry("bad-start", "n", "i", "o", null, Map.of(), null, traceId, "yesterday", null));
        // ISO-8601 allows a 60th second; an instant parser reads it as :59.
        batch.add(new RawEntry("leap", "n", "i", "o", null, Map.of(), null, traceId, "2026-08-12T23:59:60Z", null));

        assertEquals(2, isolated.write(pid, batch), "the ordinary span and the leap-second one");
        assertEquals(3, isolated.droppedSpans(), "the oversized input, the oversized session id, the unreadable start");
        assertEquals(
                "2026-08-12T23:59:59Z",
                spans.findById(pid, traceId, "leap").orElseThrow().startedAt());
    }

    @Test
    @DisplayName("§7.6: a span landing after its trace rolled up is measured against that rollup's watermark")
    void lateness_measuresASpanAgainstItsTracesLastRollup() {
        String traceId = traceId("late");
        SpanLateness own = new SpanLateness();
        SpanBatchWriter isolated = new SpanBatchWriter(
                sessions,
                traces,
                spans,
                payloads,
                toolCalls,
                retrievedDocs,
                sideTables,
                pricer,
                mediaExternalizer,
                mediaRefs,
                callSites,
                own,
                mapper,
                transactionManager);

        isolated.write(pid, List.of(span("s1", null, traceId, KindNormalizer.LLM, t0, t0.plusSeconds(1), Map.of())));
        assertArrayEquals(new long[6], own.drain(), "a trace that never rolled up has nothing to be late against");

        settle(traceId);
        isolated.write(
                pid,
                List.of(span(
                        "s2", "s1", traceId, KindNormalizer.TOOL, t0.plusSeconds(2), t0.plusMillis(3_500), Map.of())));

        assertArrayEquals(
                new long[] {0, 0, 1, 0, 0, 0},
                own.drain(),
                "ended 2.5s after the watermark (the rolled-up span's end): one sample in the 1s-10s bucket");
    }

    @Test
    @DisplayName("§6.1: a failure between the span write and the trace re-arm leaves NEITHER visible")
    void atomicity_spanAndTraceReArmCommitTogether() {
        String traceId = traceId("atomic");
        TraceV2Repository failing = Mockito.spy(unproxied(traces));
        Mockito.doThrow(new IllegalStateException("induced failure between the span write and the re-arm"))
                .when(failing)
                .applyBatchTimers(Mockito.anyString(), Mockito.anyCollection());
        SpanBatchWriter isolated = newWriter(failing);

        assertThrows(
                IllegalStateException.class,
                () -> isolated.write(pid, List.of(span("s1", null, traceId, KindNormalizer.LLM, t0, t0, Map.of()))));

        assertTrue(
                spans.listByTrace(pid, traceId).isEmpty(),
                "a span row visible without its trace re-arm would be silently excluded from a settling rollup");
        assertTrue(
                traces.findById(pid, traceId).isEmpty(),
                "and the identity row goes with them. This assertion used to read the other way — that the "
                        + "trace was 'left unarmed, not half-armed' — which described the get-or-create running "
                        + "outside the transaction and accepted the shell it strands. Nothing can complete that "
                        + "row and nothing can retire it");
    }

    @Test
    @DisplayName("§7.1: 1,000 spans over 50 traces are 50 trace updates in one statement, not 1,000 statements")
    void coalescing_oneTraceUpdatePerTracePerBatch() {
        TraceV2Repository counting = Mockito.spy(unproxied(traces));
        AtomicReference<Integer> updates = new AtomicReference<>(0);
        List<Integer> callSizes = new ArrayList<>();
        Mockito.doAnswer(inv -> {
                    java.util.Collection<?> batch = inv.getArgument(1);
                    callSizes.add(batch.size());
                    updates.updateAndGet(n -> n + batch.size());
                    return inv.callRealMethod();
                })
                .when(counting)
                .applyBatchTimers(Mockito.anyString(), Mockito.anyCollection());
        SpanBatchWriter counted = newWriter(counting);

        List<RawEntry> batch = new ArrayList<>(1000);
        for (int t = 0; t < 50; t++) {
            String traceId = traceId("coalesce-" + t);
            for (int s = 0; s < 20; s++) {
                batch.add(span("s" + s, s == 0 ? null : "s0", traceId, KindNormalizer.LLM, t0, t0, Map.of()));
            }
        }

        assertEquals(1000, counted.write(pid, batch));

        assertEquals(List.of(50), callSizes, "one statement, carrying one row per trace touched");
        assertEquals(50, updates.get());
    }

    @Test
    @DisplayName("§7.1: two batches over overlapping trace sets do not deadlock — sorted locking makes it impossible")
    void coalescing_overlappingConcurrentBatchesDoNotDeadlock() throws Exception {
        List<String> traceIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            traceIds.add(traceId("dl-" + i));
        }
        // The same traces in opposite orders: the shape that deadlocks without sorted locking.
        List<RawEntry> ascending = new ArrayList<>();
        List<RawEntry> descending = new ArrayList<>();
        for (int i = 0; i < traceIds.size(); i++) {
            ascending.add(span("a" + i, null, traceIds.get(i), KindNormalizer.LLM, t0, t0, Map.of()));
            String reverse = traceIds.get(traceIds.size() - 1 - i);
            descending.add(span("b" + i, null, reverse, KindNormalizer.LLM, t0, t0, Map.of()));
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable first = writeConcurrently(ascending, ready, go, failures);
        Runnable second = writeConcurrently(descending, ready, go, failures);
        Thread one = Thread.ofVirtual().start(first);
        Thread two = Thread.ofVirtual().start(second);
        assertTrue(ready.await(20, TimeUnit.SECONDS), "both writers reached the start line");
        go.countDown();
        one.join(java.time.Duration.ofSeconds(60));
        two.join(java.time.Duration.ofSeconds(60));

        assertEquals(List.of(), failures, "a deadlock surfaces here as a Postgres deadlock-detected error");
        for (String traceId : traceIds) {
            assertEquals(2, spans.listByTrace(pid, traceId).size(), "both batches landed in full");
        }
    }

    private Runnable writeConcurrently(
            List<RawEntry> batch, CountDownLatch ready, CountDownLatch go, List<Throwable> failures) {
        return () -> {
            try {
                ready.countDown();
                go.await();
                writer.write(pid, batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                failures.add(e);
            }
        };
    }

    @Test
    @DisplayName("§6.5: usage with a known model is priced at write and stamped with the book that did it")
    void pricing_inferredStampsTheBook() {
        String traceId = traceId("cost-inferred");
        writer.write(
                pid,
                List.of(span(
                        "s1",
                        null,
                        traceId,
                        KindNormalizer.LLM,
                        t0,
                        t0,
                        usageAttrs(1_000_000L, 1_000_000L),
                        "claude-sonnet-5")));

        SpanRow row = spans.findById(pid, traceId, "s1").orElseThrow();
        assertEquals(SpanRow.CostSource.INFERRED, row.costSource());
        assertNotNull(row.priceBookVersion(), "a cost is only auditable if the book that produced it is named");
        assertEquals("claude-sonnet-5", row.modelId());
        assertEquals(
                0,
                new BigDecimal("2").compareTo(new BigDecimal(requireCost(row.inputCost()))),
                "one million input tokens at the vendored snapshot's $2/Mtok rate for Sonnet 5");
    }

    @Test
    @DisplayName("§6.5: a model no book prices is unpriced — every cost column null, never zero")
    void pricing_unknownModelIsNeverFree() {
        String traceId = traceId("cost-unpriced");
        writer.write(
                pid,
                List.of(span(
                        "s1",
                        null,
                        traceId,
                        KindNormalizer.LLM,
                        t0,
                        t0,
                        usageAttrs(1000L, 1000L),
                        "a-model-no-book-has-ever-carried")));

        SpanRow row = spans.findById(pid, traceId, "s1").orElseThrow();
        assertEquals(SpanRow.CostSource.UNPRICED, row.costSource());
        assertNull(row.totalCost(), "$0 would render real spend as free");
        assertNull(row.modelId());
        assertEquals(2000L, row.totalTokens(), "the usage is still a fact, and the rollup counts it as unpriced");
    }

    @Test
    @DisplayName("§6.5: the carve-out is unconditional — only an input smaller than its cached parts is left alone")
    void pricing_disjointInputIsNotCorrected() {
        String traceId = traceId("cost-disjoint");
        Map<String, Object> attrs = new HashMap<>(usageAttrs(1000L, 10L));
        attrs.put(GenAiAttributes.USAGE_CACHE_READ_INPUT_TOKENS, 400L);
        writer.write(pid, List.of(span("s1", null, traceId, KindNormalizer.LLM, t0, t0, attrs, "claude-sonnet-5")));

        assertEquals(
                600L,
                spans.findById(pid, traceId, "s1").orElseThrow().inputTokens(),
                "the OTel convention is cache-inclusive for every producer — the model name is not evidence");

        Map<String, Object> understated = new HashMap<>(usageAttrs(300L, 10L));
        understated.put(GenAiAttributes.USAGE_CACHE_READ_INPUT_TOKENS, 400L);
        writer.write(
                pid, List.of(span("s2", null, traceId, KindNormalizer.LLM, t0, t0, understated, "claude-sonnet-5")));

        assertEquals(
                300L,
                spans.findById(pid, traceId, "s2").orElseThrow().inputTokens(),
                "a total below its own parts proves disjoint buckets; subtracting would strip real fresh tokens");
    }

    @Test
    @DisplayName("§6.5: a container span's cumulative usage and cost are a receipt, so the rollup cannot double-count")
    void pricing_containerKindsDoNotDoubleCountIntoTheTrace() {
        String traceId = traceId("container");
        // An agent span reporting the sum of its children, beside the children: stored verbatim, the subtree counts
        // twice.
        Map<String, Object> agentAttrs = new HashMap<>(usageAttrs(2_000_000L, 2_000_000L));
        agentAttrs.put(GenAiAttributes.USAGE_COST, "8.00");
        Map<String, Object> childAttrs = new HashMap<>(usageAttrs(1_000_000L, 1_000_000L));
        childAttrs.put(GenAiAttributes.USAGE_COST, "4.00");
        writer.write(
                pid,
                List.of(
                        span(
                                "agent",
                                null,
                                traceId,
                                KindNormalizer.AGENT,
                                t0,
                                t0.plusSeconds(5),
                                agentAttrs,
                                "claude-sonnet-5"),
                        span(
                                "llm-a",
                                "agent",
                                traceId,
                                KindNormalizer.LLM,
                                t0,
                                t0.plusSeconds(2),
                                childAttrs,
                                "claude-sonnet-5"),
                        span(
                                "llm-b",
                                "agent",
                                traceId,
                                KindNormalizer.LLM,
                                t0.plusSeconds(2),
                                t0.plusSeconds(4),
                                childAttrs,
                                "claude-sonnet-5")));

        SpanRow agent = spans.findById(pid, traceId, "agent").orElseThrow();
        assertNull(agent.totalTokens(), "a container reports its children's consumption, not its own");
        assertNull(agent.totalCost());
        assertEquals(SpanRow.CostSource.UNPRICED, agent.costSource(), "never 'provided' — that is what double-counts");
        assertNotNull(
                payloads.find(pid, traceId, "agent").orElseThrow().providedUsage(),
                "the producer's numbers are kept as a receipt, they are simply never arithmetic");

        settle(traceId);
        TraceV2Row trace = traces.findById(pid, traceId).orElseThrow();
        assertEquals(
                0,
                new BigDecimal("8.00").compareTo(new BigDecimal(requireCost(trace.totalCost()))),
                "the turn cost the sum of its two children — not that plus the agent's copy of it");
        assertEquals(4_000_000L, trace.totalTokens());
    }

    /**
     * Real collaborators with one substituted trace repository, to fail at an exact point or count statements without
     * a bean override (which would cost a Spring context).
     */
    private SpanBatchWriter newWriter(TraceV2Repository tracesRepo) {
        return new SpanBatchWriter(
                sessions,
                tracesRepo,
                spans,
                payloads,
                toolCalls,
                retrievedDocs,
                sideTables,
                pricer,
                mediaExternalizer,
                mediaRefs,
                callSites,
                lateness,
                mapper,
                transactionManager);
    }

    /** The bean behind the {@code @Repository} proxy: Mockito cannot spy a proxy. */
    private static TraceV2Repository unproxied(TraceV2Repository repository) {
        if (!AopUtils.isAopProxy(repository)) return repository;
        try {
            Object target = ((Advised) repository).getTargetSource().getTarget();
            assertNotNull(target, "an AOP proxy with no target behind it");
            return (TraceV2Repository) target;
        } catch (Exception e) {
            throw new IllegalStateException("could not unwrap the trace repository proxy", e);
        }
    }

    /** The rollup worker's tick, run inline with the deadline pulled forward. */
    private void settle(String traceId) {
        jdbc.sql("UPDATE trace SET rollup_due_at = now() - interval '1 second'"
                        + " WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", traceId)
                .update();
        traces.claimDue(500);
        traces.recompute(pid, traceId);
    }

    private static String requireCost(@Nullable String cost) {
        assertNotNull(cost, "expected a cost");
        return cost;
    }

    private static String traceId(String suffix) {
        return "tr-" + suffix;
    }

    private static Map<String, Object> meta(String sessionId) {
        return Map.of(GenAiAttributes.SESSION_ID, sessionId);
    }

    private static Map<String, Object> usageAttrs(@Nullable Long input, @Nullable Long output) {
        Map<String, Object> attrs = new HashMap<>();
        if (input != null) attrs.put(GenAiAttributes.USAGE_INPUT_TOKENS, input);
        if (output != null) attrs.put(GenAiAttributes.USAGE_OUTPUT_TOKENS, output);
        return attrs;
    }

    private static RawEntry span(
            String spanId,
            @Nullable String parentId,
            String traceId,
            String kind,
            Instant startedAt,
            @Nullable Instant endedAt,
            Map<String, Object> attrs) {
        return span(spanId, parentId, traceId, kind, startedAt, endedAt, attrs, null);
    }

    private static RawEntry span(
            String spanId,
            @Nullable String parentId,
            String traceId,
            String kind,
            Instant startedAt,
            @Nullable Instant endedAt,
            Map<String, Object> attrs,
            @Nullable String model) {
        return new RawEntry(
                spanId,
                spanId,
                "in-" + spanId,
                "out-" + spanId,
                model,
                attrs,
                parentId,
                traceId,
                startedAt.toString(),
                kind,
                endedAt == null ? null : endedAt.toString(),
                null,
                null);
    }

    private static RawEntry named(RawEntry raw, String name) {
        return new RawEntry(
                raw.sourceExternalId(),
                name,
                raw.input(),
                raw.output(),
                raw.model(),
                raw.metadata(),
                raw.parentId(),
                raw.traceId(),
                raw.timestamp(),
                raw.operationKind(),
                raw.endTimestamp(),
                raw.inputMessagesJson(),
                raw.outputMessagesJson());
    }

    private static RawEntry usage(RawEntry raw, @Nullable Long input, @Nullable Long output) {
        Map<String, Object> attrs = new HashMap<>(raw.metadata() == null ? Map.of() : raw.metadata());
        attrs.putAll(usageAttrs(input, output));
        return new RawEntry(
                raw.sourceExternalId(),
                raw.name(),
                raw.input(),
                raw.output(),
                raw.model(),
                attrs,
                raw.parentId(),
                raw.traceId(),
                raw.timestamp(),
                raw.operationKind(),
                raw.endTimestamp(),
                raw.inputMessagesJson(),
                raw.outputMessagesJson());
    }
}
