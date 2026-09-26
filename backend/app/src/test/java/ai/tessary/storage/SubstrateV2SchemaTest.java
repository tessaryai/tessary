// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The v2 schema's own invariants, asserted against the real Postgres the migrations ran on.
 *
 * <p>These are not repository tests. Every claim here is enforced by DDL — a generated column, a CHECK, a
 * composite foreign key — and the point of testing them is that the DDL is the last line of defence for a
 * property no amount of careful Java can restore once a row is wrong.
 *
 * <p>The headline one: <b>all-null usage or cost buckets yield NULL, never 0.</b> Written as plain
 * {@code coalesce(a,0) + coalesce(b,0)} — the obvious spelling — five null buckets sum to zero, and a span
 * whose producer sent no usage becomes indistinguishable from a span that genuinely consumed nothing.
 * Every honesty marker downstream (unpriced counts, unsettled flags, the "—" the list renders) is built on
 * that distinction surviving the round trip.
 */
@SpringBootTest
class SubstrateV2SchemaTest {

    @Autowired
    TenantService tenants;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    JdbcClient jdbc;

    private SubstrateV2Fixtures fx;
    private String pid;
    private Instant t0;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
        pid = TenantFixture.bootstrap(tenants, "substrate-v2-schema").project().id();
        t0 = Instant.parse("2026-08-12T10:00:00Z");
    }

    /** Null means "the producer sent nothing"; zero is a measurement. Known buckets sum, unknown ones add nothing. */
    @Test
    void totalTokens_isNullOnlyWhenEveryBucketIsNull() {
        SpanRow none = fx.llmSpan(pid, SubstrateV2Fixtures.traceId(), t0);
        SpanRow partial = fx.withUsage(fx.llmSpan(pid, SubstrateV2Fixtures.traceId(), t0), 10L, null);
        SpanRow zero = fx.withUsage(fx.llmSpan(pid, SubstrateV2Fixtures.traceId(), t0), 0L, null);
        SpanRow all = fx.withUsage(fx.llmSpan(pid, SubstrateV2Fixtures.traceId(), t0), 1L, 2L, 4L, 8L, 16L);

        SpanRow read = spans.findById(pid, none.traceId(), none.id()).orElseThrow();
        assertNull(read.totalTokens(), "a span with no usage did not consume zero tokens");
        assertNull(read.totalCost(), "and it did not cost zero dollars either");
        assertEquals(10L, totalTokens(partial));
        assertEquals(0L, totalTokens(zero));
        assertEquals(31L, totalTokens(all), "all five buckets, reasoning tokens included");
    }

    private @Nullable Long totalTokens(SpanRow span) {
        return spans.findById(pid, span.traceId(), span.id()).orElseThrow().totalTokens();
    }

    @Test
    @DisplayName("every cost bucket null yields a null total cost — never $0")
    void totalCost_allNullIsNull() {
        SpanRow span = fx.llmSpan(pid, SubstrateV2Fixtures.traceId(), t0);
        // Usage present, cost absent: exactly the `unpriced` case, and the one where a zero would read as
        // a real spend being free.
        fx.withUsage(span, 1000L, 500L);

        SpanRow read = spans.findById(pid, span.traceId(), span.id()).orElseThrow();
        assertEquals(1500L, read.totalTokens());
        assertNull(read.totalCost(), "a model we hold no rate for is unpriced, not free");
        assertEquals(SpanRow.CostSource.UNPRICED, read.costSource());
    }

    @Test
    @DisplayName("cost buckets sum at full numeric precision, not double precision")
    void totalCost_sumsAtNumericPrecision() {
        SpanRow span = fx.llmSpan(pid, SubstrateV2Fixtures.traceId(), t0);
        fx.withCost(span, "0.000000000001", "0.000000000002", null, null, SpanRow.CostSource.INFERRED);

        assertEquals(
                new java.math.BigDecimal("0.000000000003"),
                new java.math.BigDecimal(spans.findById(pid, span.traceId(), span.id())
                        .orElseThrow()
                        .totalCost()),
                "picodollar buckets are why this column is numeric(18,12) and not a float");
    }

    @Test
    @DisplayName("depth is null while ancestry is unresolved, and counts edges once it is")
    void depth_nullUntilPathResolves() {
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRow root = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "agent", t0, null);
        SpanRow child = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), root.id(), "llm", t0, null);

        assertNull(
                spans.findById(pid, traceId, child.id()).orElseThrow().depth(),
                "a null path means ancestry is not resolved yet — it never means root");

        // What the path resolver does: root gets its own id, a child gets parent-path || own id.
        setPath(traceId, root.id(), root.id());
        setPath(traceId, child.id(), root.id() + "." + child.id());

        assertEquals(0, spans.findById(pid, traceId, root.id()).orElseThrow().depth());
        assertEquals(1, spans.findById(pid, traceId, child.id()).orElseThrow().depth());
    }

    @Test
    @DisplayName("trace latency is null until the trace has an end, then derives from the two timestamps")
    void traceLatency_nullUntilEnded() {
        String traceId = SubstrateV2Fixtures.traceId();
        fx.trace(pid, traceId, t0);

        assertNull(
                traces.findById(pid, traceId).orElseThrow().latencyMs(),
                "an unfinished turn has no latency; a 0 here would sort as the fastest turn in the project");

        traces.applyBatchTimers(
                pid,
                List.of(new TraceV2Repository.TimerUpdate(
                        traceId, t0.toString(), t0.plusMillis(1500).toString(), false)));

        assertEquals(1500L, traces.findById(pid, traceId).orElseThrow().latencyMs());
    }

    // ---- constraints -------------------------------------------------------------------------------

    @Test
    @DisplayName("cost_source accepts only the three values that mean something")
    void costSource_isConstrained() {
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRow span = fx.llmSpan(pid, traceId, t0);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.sql("UPDATE span SET cost_source = 'free' WHERE project_id = :pid AND id = :id")
                        .param("pid", pid)
                        .param("id", span.id())
                        .update(),
                "a fourth value would be a fourth way to read a null cost, and readers only handle three");
    }

    // ---- FK satisfiability: the §6.1 resolution order is a real constraint, not a convention --------

    @Test
    @DisplayName("a span cannot be written before its trace exists")
    void fkOrdering_spanRequiresItsTrace() {
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRow orphan = SubstrateV2Fixtures.spanRow(
                pid, traceId, SubstrateV2Fixtures.spanId(), null, "llm", t0.toString(), null, t0.toString());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> spans.upsertAll(List.of(orphan)),
                "fk_span_trace is what makes the ingest order load-bearing rather than merely tidy");

        // Get-or-create the trace first — §6.1 step 2 — and the identical write lands.
        traces.getOrCreateAll(
                List.of(TraceV2Row.of(pid, traceId, null, null, null, null, null, t0.toString(), t0.toString())));
        spans.upsertAll(List.of(orphan));
        assertTrue(spans.findById(pid, traceId, orphan.id()).isPresent());
    }

    @Test
    @DisplayName("a trace naming a session cannot be written before that session exists")
    void fkOrdering_traceRequiresItsSession() {
        String traceId = SubstrateV2Fixtures.traceId();
        String sessionId = SubstrateV2Fixtures.sessionId();
        TraceV2Row row = TraceV2Row.of(pid, traceId, sessionId, null, null, null, null, t0.toString(), t0.toString());

        assertThrows(DataIntegrityViolationException.class, () -> traces.getOrCreateAll(List.of(row)));

        sessions.getOrCreateAll(List.of(SubstrateV2Fixtures.sessionRow(pid, sessionId, t0.toString())));
        traces.getOrCreateAll(List.of(row));
        assertTrue(traces.findById(pid, traceId).isPresent());
    }

    @Test
    @DisplayName("a payload cannot be written before its span, and follows the span's cascade")
    void fkOrdering_payloadRequiresItsSpan() {
        String traceId = SubstrateV2Fixtures.traceId();
        String spanId = SubstrateV2Fixtures.spanId();
        fx.trace(pid, traceId, t0);
        SpanPayloadRow payload =
                new SpanPayloadRow(pid, traceId, spanId, "prompt", "completion", null, null, t0.toString());

        assertThrows(DataIntegrityViolationException.class, () -> payloads.upsertAll(List.of(payload)));

        fx.span(pid, traceId, spanId, null, "llm", t0, null);
        payloads.upsertAll(List.of(payload));
        assertNotNull(payloads.find(pid, traceId, spanId).orElseThrow().input());

        jdbc.sql("DELETE FROM span WHERE project_id = :pid AND trace_id = :tid")
                .param("pid", pid)
                .param("tid", traceId)
                .update();
        assertEquals(
                java.util.Optional.empty(),
                payloads.find(pid, traceId, spanId),
                "ON DELETE CASCADE is the correctness backstop; bulk deletion still goes through the batcher");
    }

    /** What {@code PathResolver} will do, expressed directly — the platform writes path, never an arrival. */
    private void setPath(String traceId, String spanId, String path) {
        jdbc.sql("UPDATE span SET path = :path::ltree, path_state = 'resolved'"
                        + " WHERE project_id = :pid AND trace_id = :tid AND id = :id")
                .param("path", path)
                .param("pid", pid)
                .param("tid", traceId)
                .param("id", spanId)
                .update();
    }
}
