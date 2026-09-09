// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

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
 * The v2 write path against the real schema: what one drained batch does to
 * {@code session / trace / span / span_payload}, and the three invariants the spec says must hold in
 * code rather than only on paper.
 *
 * <p>The three, in the order they are proven below:
 *
 * <ol>
 *   <li><b>§6.1 atomicity.</b> A span row and the trace re-arm that reflects it commit together, or neither
 *       does. Break it and the settle protocol silently excludes a visible span from a settling rollup.
 *   <li><b>§6.2 last-write-wins.</b> A span's completed version replaces the partial that preceded it, an
 *       older redelivery cannot undo it, and neither can overwrite what the PLATFORM derived.
 *   <li><b>§7.1 coalescing.</b> One trace update per trace per batch — not one per span — and sorted
 *       locking so two batches over overlapping traces cannot deadlock.
 * </ol>
 *
 * <p>The scheduled resolvers are off in this context. They tick every second, and half the assertions here
 * are about what the write path did and did NOT derive — {@code path} is null on arrival because ancestry
 * belongs to the fixpoint — which a resolver racing the assertion would make flaky rather than wrong. The
 * rollup worker is off for the harder version of the same reason: it claims due traces GLOBALLY, so a
 * running one could take the claim out from under {@link #settle} and leave the assertion reading a trace
 * something else had already rolled up.
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

    // ---- the shape one batch lands ------------------------------------------------------------------

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
    @DisplayName("a span with no session id belongs to no session — nothing is synthesized to fill the hole")
    void write_anonymousTrafficGetsNoSession() {
        String traceId = traceId("anon");
        writer.write(pid, List.of(span("s1", null, traceId, KindNormalizer.LLM, t0, t0, Map.of())));

        assertNull(traces.findById(pid, traceId).orElseThrow().sessionId());
        assertEquals(0, countSessions(), "a session row exists only when a producer sends a session id");
        assertEquals(
                SpanRow.ResolverState.PENDING,
                spans.findById(pid, traceId, "s1").orElseThrow().correlationState(),
                "it waits for its trace to answer, and is retired by the backfiller when the trace settles");
    }

    @Test
    @DisplayName("§6.1 — a batch that fails to commit leaves no trace or session row behind")
    void write_failedBatchLeavesNoIdentityRows() {
        String traceId = traceId("uncommittable");
        // A tool call whose arguments are valid JSON that Postgres will not take as jsonb: \u0000 is a legal
        // escape in a JSON string and has no representation in Postgres text, so Jackson parses it and the
        // bind throws. This is the real shape — a web search result carrying a NUL byte — that failed a
        // batch mid-transaction and left the shell this test exists to forbid.
        RawEntry uncommittable = new RawEntry(
                "tool-1",
                null,
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
        // Its own writer instance, so the drop counter is this test's and not the whole suite's.
        SpanBatchWriter isolated = newWriter(traces);
        List<RawEntry> batch = new ArrayList<>();
        batch.add(span("ok", null, traceId, KindNormalizer.LLM, t0, t0, Map.of()));
        batch.add(new RawEntry(null, null, "no id", "i", "o", null, Map.of(), null, traceId, t0.toString()));
        batch.add(new RawEntry("no-start", null, "n", "i", "o", null, Map.of(), null, traceId, null));
        batch.add(new RawEntry("x".repeat(600), null, "n", "i", "o", null, Map.of(), null, traceId, t0.toString()));

        assertEquals(1, isolated.write(pid, batch), "one writable row of four");
        assertEquals(3, isolated.droppedSpans(), "no id, no start time, and an id that is not an identifier");
        assertEquals(1, spans.listByTrace(pid, traceId).size(), "a poison row costs only itself");
    }

    // ---- §6.1 atomicity ------------------------------------------------------------------------------

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

    // ---- §6.2 last write wins ------------------------------------------------------------------------

    @Test
    @DisplayName("§6.2: the completed version replaces the partial that arrived first")
    void lww_finalReplacesPartial() {
        String traceId = traceId("lww");
        writer.write(pid, List.of(usage(span("s1", null, traceId, KindNormalizer.LLM, t0, null, Map.of()), 10L, null)));
        assertNull(spans.findById(pid, traceId, "s1").orElseThrow().outputTokens(), "the partial had no output yet");

        writer.write(
                pid,
                List.of(usage(
                        span("s1", null, traceId, KindNormalizer.LLM, t0, t0.plusSeconds(2), Map.of()), 10L, 40L)));

        SpanRow row = spans.findById(pid, traceId, "s1").orElseThrow();
        assertEquals(40L, row.outputTokens());
        assertEquals(50L, row.totalTokens(), "the generated total follows the replacement");
        assertEquals(t0.plusSeconds(2).toString(), row.endedAt());
    }

    @Test
    @DisplayName("§6.2: an older redelivery cannot undo the version already stored")
    void lww_olderArrivalIsANoOp() {
        String traceId = traceId("lww-old");
        writer.write(
                pid,
                List.of(usage(
                        span("s1", null, traceId, KindNormalizer.LLM, t0, t0.plusSeconds(2), Map.of()), 10L, 40L)));

        writer.write(pid, List.of(usage(span("s1", null, traceId, KindNormalizer.LLM, t0, null, Map.of()), 10L, null)));

        SpanRow row = spans.findById(pid, traceId, "s1").orElseThrow();
        assertEquals(40L, row.outputTokens(), "the partial redelivery lost the event_ts comparison");
        assertEquals(t0.plusSeconds(2).toString(), row.endedAt());
    }

    @Test
    @DisplayName("§6.2: equal event_ts goes to the latest arrival, because second-granularity clocks tie constantly")
    void lww_tiesGoToTheLatestArrival() {
        String traceId = traceId("lww-tie");
        writer.write(pid, List.of(named(span("s1", null, traceId, KindNormalizer.LLM, t0, t0, Map.of()), "first")));

        writer.write(pid, List.of(named(span("s1", null, traceId, KindNormalizer.LLM, t0, t0, Map.of()), "second")));

        assertEquals(
                "second",
                spans.findById(pid, traceId, "s1").orElseThrow().name(),
                "a strictly-greater guard would drop the completed version of every span whose partial shared its stamp");
    }

    @Test
    @DisplayName("§6.2: a newer version replaces what the producer said, never what the platform derived")
    void lww_platformDerivedAncestrySurvivesAProducerUpdate() {
        String traceId = traceId("lww-path");
        writer.write(pid, List.of(span("s1", null, traceId, KindNormalizer.LLM, t0, t0, Map.of())));
        spans.resolveRootPaths(100_000);
        assertEquals("s1", spans.findById(pid, traceId, "s1").orElseThrow().path());

        writer.write(
                pid,
                List.of(named(span("s1", null, traceId, KindNormalizer.LLM, t0, t0.plusSeconds(1), Map.of()), "v2")));

        SpanRow row = spans.findById(pid, traceId, "s1").orElseThrow();
        assertEquals("v2", row.name(), "the producer's fields did update");
        assertEquals("s1", row.path(), "but the resolved ancestry did not — a replay must not re-open resolved work");
        assertEquals(
                SpanRow.ResolverState.RESOLVED,
                row.pathState(),
                "nor send the span back into the fixpoint's queue on every redelivery");
    }

    // ---- §7.1 batch coalescing ----------------------------------------------------------------------

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
        // Two batches naming the same traces in OPPOSITE orders: the shape that deadlocks the moment two
        // writers lock rows in the order they happen to hold them.
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

    // ---- §6.5 pricing --------------------------------------------------------------------------------

    @Test
    @DisplayName("§6.5: a producer-sent cost is stored verbatim and no book is stamped")
    void pricing_providedIsVerbatim() {
        String traceId = traceId("cost-provided");
        Map<String, Object> attrs = new HashMap<>(usageAttrs(1_000_000L, 1_000_000L));
        attrs.put(GenAiAttributes.USAGE_COST, "0.0425");
        writer.write(pid, List.of(span("s1", null, traceId, KindNormalizer.LLM, t0, t0, attrs, "claude-sonnet-5")));

        SpanRow row = spans.findById(pid, traceId, "s1").orElseThrow();
        assertEquals(SpanRow.CostSource.PROVIDED, row.costSource());
        assertNull(row.priceBookVersion(), "we did not price it, so there is no book to name");
        assertEquals(0, new BigDecimal("0.0425").compareTo(new BigDecimal(requireCost(row.totalCost()))));
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
    @DisplayName("§6.5: an OpenAI cache-inclusive input count is made disjoint at write, so cache reads bill once")
    void pricing_cacheInclusiveInputIsCorrectedAtWrite() {
        String traceId = traceId("cost-cache");
        Map<String, Object> attrs = new HashMap<>(usageAttrs(1000L, 10L));
        attrs.put(GenAiAttributes.USAGE_CACHE_READ_INPUT_TOKENS, 400L);
        writer.write(pid, List.of(span("s1", null, traceId, KindNormalizer.LLM, t0, t0, attrs, "gpt-4o")));

        SpanRow row = spans.findById(pid, traceId, "s1").orElseThrow();
        assertEquals(600L, row.inputTokens(), "OpenAI's prompt_tokens already contained the 400 cached ones");
        assertEquals(400L, row.cacheReadTokens());
        assertEquals(1010L, row.totalTokens(), "so the five buckets sum to the tokens actually consumed, once");
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
        // The shape that produces the bug: an agent span reporting the SUM of its children, plus the
        // children themselves. Storing its numbers verbatim would put the whole subtree into the trace twice.
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

    // ---- helpers -------------------------------------------------------------------------------------

    /**
     * A writer with the real collaborators and one substituted trace repository, so a test can induce a
     * failure at an exact point of the batch, or count the statements it issues, without a bean override
     * (which would cost this class its own Spring context and a fresh Liquibase run).
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

    /**
     * The bean behind the proxy. Spring wraps every {@code @Repository} for persistence-exception
     * translation, and Mockito cannot spy a proxy — there are no fields on it to copy.
     */
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

    /** Claim and recompute once, with the deadline pulled forward — the rollup worker's tick, run inline. */
    private void settle(String traceId) {
        jdbc.sql("UPDATE trace SET rollup_due_at = now() - interval '1 second'"
                        + " WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", traceId)
                .update();
        traces.claimDue(500);
        traces.recompute(pid, traceId);
    }

    private int countSessions() {
        return jdbc.sql("SELECT count(*) FROM session WHERE project_id = :pid")
                .param("pid", pid)
                .query(Integer.class)
                .single();
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
                null,
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
                raw.sourceUrl(),
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
                raw.sourceUrl(),
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
