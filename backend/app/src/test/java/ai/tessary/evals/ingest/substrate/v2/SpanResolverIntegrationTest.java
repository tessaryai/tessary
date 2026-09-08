// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.config.SubstrateProperties;
import ai.tessary.evals.storage.SessionRepository;
import ai.tessary.evals.storage.SpanPayloadRepository;
import ai.tessary.evals.storage.SpanRepository;
import ai.tessary.evals.storage.SpanRow;
import ai.tessary.evals.storage.TraceV2Repository;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.SubstrateV2Fixtures;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The two micro-batch resolvers: the ancestry fixpoint (§6.4) and the correlation backfill (§6.3), and the
 * terminal states that keep both of their partial indexes drainable (implementation plan §2.6).
 *
 * <p><b>Why the terminal states are the point.</b> Both resolvers select a LIMITed batch out of a partial
 * index. A row that can never resolve — a child whose parent the producer never shipped, a span of
 * anonymous traffic — stays in that index forever unless something takes it out. Once the permanent
 * residents outnumber the batch limit, every pass selects only residents and no genuine work is ever
 * reached again. That is a correctness failure that presents as a resolver quietly doing nothing, and the
 * starvation test below is the one that would catch it.
 *
 * <p>The scheduled beans are switched off for this context so the passes counted here are the passes that
 * ran, rather than whatever a one-second scheduler happened to do in between. The property fingerprint is
 * deliberately distinct from the write-path test's, because both resolvers select from a GLOBAL index: a
 * shared database would let one class's spans consume the other's batch limit, which is the very failure
 * the starvation test exists to detect.
 */
@SpringBootTest(
        properties = {
            "evals.ingest.substrate.resolvers-enabled=false",
            "evals.ingest.substrate.rollup-enabled=false",
            "evals.ingest.substrate.resolver-batch-size=500"
        })
class SpanResolverIntegrationTest {

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
        pid = TenantFixture.bootstrap(tenants, "span-resolver").project().id();
        t0 = Instant.parse("2026-08-12T10:00:00Z");
    }

    // ---- the ancestry fixpoint ---------------------------------------------------------------------

    @Test
    @DisplayName("a depth-n chain arriving deepest-first resolves in at most n passes, then converges")
    void pathFixpoint_resolvesOneLevelPerPassAndStops() {
        String traceId = SubstrateV2Fixtures.traceId();
        // Deepest-first is the real arrival order: an exporter flushes a span when it ENDS, so the leaf
        // ships before its parent and the root ships last.
        List<String> chain = List.of("aaaa", "bbbb", "cccc", "dddd");
        for (int depth = chain.size() - 1; depth >= 0; depth--) {
            fx.span(pid, traceId, chain.get(depth), depth == 0 ? null : chain.get(depth - 1), "llm", t0, t0);
        }
        settle(traceId);
        PathResolver resolver = resolver(500);

        for (int pass = 0; pass < chain.size(); pass++) {
            resolver.runOnce();
        }

        assertEquals(0, resolver.runOnce().total(), "the fixpoint has converged: another pass finds nothing");
        assertEquals("aaaa", pathOf(traceId, "aaaa"), "a producer-declared root is its own one-label path");
        assertEquals("aaaa.bbbb.cccc.dddd", pathOf(traceId, "dddd"));
        assertEquals(
                3, spans.findById(pid, traceId, "dddd").orElseThrow().depth(), "depth follows the path, generated");
        assertEquals(
                SpanRow.ResolverState.RESOLVED,
                spans.findById(pid, traceId, "dddd").orElseThrow().pathState());
    }

    @Test
    @DisplayName("a child whose parent was never shipped waits, then becomes an orphan once the trace settles")
    void pathFixpoint_orphansLeaveTheIndexOnlyAfterSettle() {
        String traceId = SubstrateV2Fixtures.traceId();
        fx.span(pid, traceId, "child", "aparentthatnevership", "llm", t0, t0);
        PathResolver resolver = resolver(500);

        assertEquals(0, resolver.runOnce().orphans(), "an unsettled trace may still be about to deliver the parent");
        assertEquals(
                SpanRow.ResolverState.PENDING,
                spans.findById(pid, traceId, "child").orElseThrow().pathState());
        assertEquals(1, pendingPaths(), "and until then it is genuine work, held in the index");

        settle(traceId);
        assertEquals(1, resolver.runOnce().orphans());

        SpanRow child = spans.findById(pid, traceId, "child").orElseThrow();
        assertEquals(SpanRow.ResolverState.ORPHAN, child.pathState());
        assertNull(child.path(), "orphan means the ancestry is unknowable, not that the span is a root");
        assertEquals(0, pendingPaths(), "and it is out of the partial index for good");
    }

    // ---- correlation backfill ------------------------------------------------------------------------

    @Test
    @DisplayName("a third-party span that arrived before its trace was known inherits the trace's handles")
    void correlation_copiesTheTraceHandlesDown() {
        String sessionId = SubstrateV2Fixtures.sessionId();
        String traceId = SubstrateV2Fixtures.traceId();
        fx.trace(pid, traceId, sessionId, t0);
        fx.span(pid, traceId, "s1", null, "llm", t0, t0);

        assertEquals(1, backfiller(500).runOnce().correlated());

        SpanRow row = spans.findById(pid, traceId, "s1").orElseThrow();
        assertEquals(sessionId, row.sessionId(), "no read has to join upward to find out which session this was");
        assertEquals(SpanRow.ResolverState.DONE, row.correlationState());
    }

    @Test
    @DisplayName("anonymous traffic is retired rather than re-read forever")
    void correlation_settledSessionlessTraceIsTerminal() {
        String traceId = SubstrateV2Fixtures.traceId();
        fx.span(pid, traceId, "s1", null, "llm", t0, t0);
        settle(traceId);

        CorrelationBackfiller backfiller = backfiller(500);
        assertEquals(1, backfiller.runOnce().anonymous());

        SpanRow row = spans.findById(pid, traceId, "s1").orElseThrow();
        assertEquals(SpanRow.ResolverState.NONE, row.correlationState());
        assertNull(row.sessionId(), "there was never a session to inherit — that is the whole finding");
        assertEquals(0, pendingCorrelations());
    }

    @Test
    @DisplayName("permanent residents cannot starve genuine work out of a LIMITed batch")
    void correlation_terminalStatesKeepTheQueueDrainable() {
        // Far more anonymous spans than one pass can hold. Without the terminal state they would be
        // re-selected on every pass forever, and the one span with a session waiting for it would never be
        // reached — the resolver would look healthy and simply never do the job.
        // Retire whatever earlier tests in this class left correlatable, so the residents below are the
        // only population the limited passes contend with.
        backfiller(100_000).runOnce();
        int residents = 12;
        int limit = 2;
        for (int i = 0; i < residents; i++) {
            String anonymousTrace = SubstrateV2Fixtures.traceId();
            fx.span(pid, anonymousTrace, "anon" + i, null, "llm", t0, t0);
            settle(anonymousTrace);
        }
        String sessionId = SubstrateV2Fixtures.sessionId();
        String correlatable = SubstrateV2Fixtures.traceId();
        fx.trace(pid, correlatable, sessionId, t0);
        fx.span(pid, correlatable, "pending", null, "llm", t0, t0);

        CorrelationBackfiller backfiller = backfiller(limit);
        List<Integer> depths = new ArrayList<>();
        for (int pass = 0; pass < residents + 2 && pendingCorrelations() > 0; pass++) {
            backfiller.runOnce();
            depths.add(pendingCorrelations());
        }

        assertEquals(
                sessionId,
                spans.findById(pid, correlatable, "pending").orElseThrow().sessionId(),
                "the span that HAD a session got it, with a batch limit far smaller than the resident population");
        assertEquals(0, pendingCorrelations(), "and the queue drained rather than plateauing: " + depths);
        assertTrue(depths.size() <= residents + 1, "each pass made progress: " + depths);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private PathResolver resolver(int batchSize) {
        return new PathResolver(spans, propsWithBatchSize(batchSize));
    }

    private CorrelationBackfiller backfiller(int batchSize) {
        return new CorrelationBackfiller(spans, propsWithBatchSize(batchSize));
    }

    private static SubstrateProperties propsWithBatchSize(int batchSize) {
        SubstrateProperties props = new SubstrateProperties();
        props.setResolverBatchSize(batchSize);
        return props;
    }

    private String pathOf(String traceId, String spanId) {
        String path = spans.findById(pid, traceId, spanId).orElseThrow().path();
        assertNotNull(path, "expected a resolved path for " + spanId);
        return path;
    }

    /** This project's spans still awaiting ancestry — scoped, because the test database is shared. */
    private int pendingPaths() {
        return jdbc.sql("SELECT count(*) FROM span WHERE project_id = :pid"
                        + " AND path IS NULL AND path_state = 'pending'")
                .param("pid", pid)
                .query(Integer.class)
                .single();
    }

    private int pendingCorrelations() {
        return jdbc.sql("SELECT count(*) FROM span WHERE project_id = :pid"
                        + " AND session_id IS NULL AND correlation_state = 'pending'")
                .param("pid", pid)
                .query(Integer.class)
                .single();
    }

    /** Settle a trace the way the rollup worker would: arm it, claim it, recompute it. */
    private void settle(String traceId) {
        traces.applyBatchTimers(pid, List.of(new TraceV2Repository.TimerUpdate(traceId, t0.toString(), null, true)));
        jdbc.sql("UPDATE trace SET rollup_due_at = now() - interval '1 second'"
                        + " WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", traceId)
                .update();
        traces.claimDue(500);
        traces.recompute(pid, traceId);
        assertTrue(traces.findById(pid, traceId).orElseThrow().isSettled(), "the trace must actually settle");
    }
}
