// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
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
 * The ancestry fixpoint (§6.4) and correlation backfill (§6.3), and the terminal states that keep their partial
 * indexes drainable (plan §2.6). Both select a LIMITed batch from a partial index; a row that can never resolve (a
 * missing parent, anonymous traffic) stays forever unless retired, and once such rows outnumber the limit, no real
 * work is reached. The starvation test catches that. The schedulers are off so counted passes are the ones that ran.
 */
@SpringBootTest
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
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
        pid = TenantFixture.bootstrap(tenants, "span-resolver").project().id();
        t0 = Instant.parse("2026-08-12T10:00:00Z");
    }

    @Test
    @DisplayName("a depth-n chain arriving deepest-first resolves in at most n passes, then converges")
    void pathFixpoint_resolvesOneLevelPerPassAndStops() {
        String traceId = SubstrateV2Fixtures.traceId();
        // Deepest-first is the real order: exporters flush a span when it ends, so the root ships last.
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
        assertEquals("orphan", child.pathState());
        assertNull(child.path(), "orphan means the ancestry is unknowable, not that the span is a root");
        assertEquals(0, pendingPaths(), "and it is out of the partial index for good");
    }

    /**
     * Catches: children retired as orphans while their long-running parent was still open stayed orphans
     * after it arrived, so a 30-second agent turn or queue-consumer span never got its children under it.
     * The exporter ships a span when it ends, so a parent that outlives its children by more than the
     * settle window always arrives after they were retired.
     */
    @Test
    @DisplayName("a parent that arrives after its children were retired as orphans takes them back")
    void pathFixpoint_aLateParentReclaimsItsOrphanedSubtree() {
        String traceId = SubstrateV2Fixtures.traceId();
        // The turn's children end and ship first: a step, the llm call inside it, and the reply.
        fx.span(pid, traceId, "assemble", "turn", "step", t0, t0.plusSeconds(1));
        fx.span(pid, traceId, "route", "assemble", "llm", t0, t0.plusSeconds(1));
        fx.span(pid, traceId, "reply", "turn", "llm", t0.plusSeconds(2), t0.plusSeconds(3));
        PathResolver resolver = resolver(500);
        settle(traceId);
        resolver.runOnce();
        assertEquals(
                "orphan", spans.findById(pid, traceId, "assemble").orElseThrow().pathState());
        assertEquals(
                "orphan", spans.findById(pid, traceId, "reply").orElseThrow().pathState());
        resolver.runOnce();
        assertEquals(
                "orphan",
                spans.findById(pid, traceId, "route").orElseThrow().pathState(),
                "retired behind its orphan parent");
        assertEquals(0, pendingPaths());

        // Twenty seconds later the turn itself ends and ships.
        fx.span(pid, traceId, "turn", null, "step", t0, t0.plusSeconds(25));

        assertEquals(
                4,
                pendingPaths(),
                "the arrival, a pending root itself, put its whole retired subtree back in the queue");
        for (int pass = 0; pass < 3; pass++) resolver.runOnce();
        String turn = pathOf(traceId, "turn");
        assertEquals(turn + ".assemble", pathOf(traceId, "assemble"));
        assertEquals(turn + ".assemble.route", pathOf(traceId, "route"), "two levels down, through the reclaimed step");
        assertEquals(turn + ".reply", pathOf(traceId, "reply"));
        assertEquals(0, pendingPaths());
    }

    /** Catches: a reclaim that un-retires every orphan in the trace, rather than the arrival's own descendants. */
    @Test
    @DisplayName("an arrival takes back only its own descendants, never a sibling orphan whose parent is still missing")
    void pathFixpoint_aLateParentLeavesUnrelatedOrphansRetired() {
        String traceId = SubstrateV2Fixtures.traceId();
        fx.span(pid, traceId, "mine", "late", "llm", t0, t0);
        fx.span(pid, traceId, "stray", "aparentthatnevership", "llm", t0, t0);
        PathResolver resolver = resolver(500);
        settle(traceId);
        resolver.runOnce();
        assertEquals(0, pendingPaths());

        fx.span(pid, traceId, "late", null, "step", t0, t0.plusSeconds(20));

        assertEquals(2, pendingPaths(), "the arrival and its own child are back in the queue, nothing else");
        assertEquals(
                "orphan", spans.findById(pid, traceId, "stray").orElseThrow().pathState());
        resolver.runOnce();
        resolver.runOnce();
        assertEquals(pathOf(traceId, "late") + ".mine", pathOf(traceId, "mine"));
        assertEquals(
                "orphan", spans.findById(pid, traceId, "stray").orElseThrow().pathState());
    }

    /**
     * Catches: a reclaim that reopens the starvation the orphan state exists to prevent. A late middle span
     * whose own parent never ships takes its children back to pending; once the trace is settled the pass
     * must retire the middle span and then its children again, and the index must drain to empty.
     */
    @Test
    @DisplayName(
            "a late span whose own parent never ships is retired with its reclaimed children, and the queue drains")
    void pathFixpoint_aReclaimedSubtreeBehindAMissingParentIsRetiredAgain() {
        String traceId = SubstrateV2Fixtures.traceId();
        fx.span(pid, traceId, "leaf", "middle", "llm", t0, t0);
        PathResolver resolver = resolver(500);
        settle(traceId);
        resolver.runOnce();
        assertEquals(
                "orphan", spans.findById(pid, traceId, "leaf").orElseThrow().pathState());

        fx.span(pid, traceId, "middle", "aparentthatnevership", "step", t0, t0.plusSeconds(20));

        assertEquals(2, pendingPaths(), "the middle span and its reclaimed leaf");
        resolver.runOnce();
        resolver.runOnce();
        assertEquals(
                "orphan", spans.findById(pid, traceId, "middle").orElseThrow().pathState());
        assertEquals(
                "orphan", spans.findById(pid, traceId, "leaf").orElseThrow().pathState(), "one level per pass");
        assertEquals(0, pendingPaths(), "nothing is left in the index");
    }

    /** Catches: a redelivered orphan sending its retired children round the retire loop on every replay. */
    @Test
    @DisplayName("a redelivered span that is itself an orphan does not reclaim its children")
    void pathFixpoint_aRedeliveredOrphanReclaimsNothing() {
        String traceId = SubstrateV2Fixtures.traceId();
        fx.span(pid, traceId, "middle", "aparentthatnevership", "step", t0, t0.plusSeconds(1));
        fx.span(pid, traceId, "leaf", "middle", "llm", t0, t0);
        PathResolver resolver = resolver(500);
        settle(traceId);
        resolver.runOnce();
        resolver.runOnce();
        assertEquals(0, pendingPaths());

        fx.span(pid, traceId, "middle", "aparentthatnevership", "step", t0, t0.plusSeconds(1));

        assertEquals(0, pendingPaths(), "the replay reclaimed nothing");
        assertEquals(
                "orphan", spans.findById(pid, traceId, "leaf").orElseThrow().pathState());
    }

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
    @DisplayName("permanent residents cannot starve genuine work out of a LIMITed batch")
    void correlation_terminalStatesKeepTheQueueDrainable() {
        // Far more anonymous spans than one pass holds: without the terminal state they are re-selected forever and
        // the one correlatable span is never reached. Earlier tests' leftovers are retired first.
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

    /** This project's spans awaiting ancestry; the database is shared. */
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

    /** Arm, claim, and recompute, as the rollup worker would. */
    private void settle(String traceId) {
        fx.rollup(pid, traceId, t0, null, true);
        assertTrue(traces.findById(pid, traceId).orElseThrow().isSettled(), "the trace must actually settle");
    }
}
