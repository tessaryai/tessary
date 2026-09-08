// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.vitals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.storage.SessionRepository;
import ai.tessary.evals.storage.SpanPayloadRepository;
import ai.tessary.evals.storage.SpanRepository;
import ai.tessary.evals.storage.SpanRow;
import ai.tessary.evals.storage.TraceV2Repository;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.SubstrateV2Fixtures;
import ai.tessary.evals.testsupport.TenantFixture;
import ai.tessary.evals.vitals.VitalsDtos.Group;
import ai.tessary.evals.vitals.VitalsDtos.Vitals;
import java.math.BigDecimal;
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

/**
 * The vitals aggregations against the real Postgres, because every one of them is SQL and the
 * interesting failures are SQL failures.
 *
 * <p><b>What changed under these tests.</b> Cost is no longer computed here at all: spans are priced on
 * arrival and this slice sums {@code span.total_cost}. Completeness is no longer guessed at with a
 * 300-second window: it is {@code trace.is_settled}, which means "nothing has arrived since the last
 * rollup" and is a fact the rollup worker wrote rather than an age threshold each consumer picked for
 * itself. Duration is no longer reconstructed from a min/max envelope over spans: it is
 * {@code trace.latency_ms}, the turn's own start→end.
 *
 * <p>The behaviours the old suite pinned are all still pinned, because they were never really about
 * where the arithmetic happened: a container span's cumulative usage must not double the bill, an
 * unpriced model must be counted rather than valued at zero, a stuck turn must be reported rather than
 * dropped, so the card
 * reconciles with its drill-down.
 */
@SpringBootTest(
        properties = {"evals.ingest.substrate.resolvers-enabled=false", "evals.ingest.substrate.rollup-enabled=false"})
class VitalsServiceIntegrationTest {

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
    VitalsService service;

    @Autowired
    JdbcClient jdbc;

    private SubstrateV2Fixtures fx;

    /** Comfortably inside the default 7-day window, and far from any boundary. */
    private static final Instant RAN = Instant.now().minus(2, ChronoUnit.HOURS);

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

    @Test
    @DisplayName("cost sums llm spans only, so a container's cumulative usage cannot double the bill")
    void costExcludesContainerSpans() {
        String pid = TenantFixture.bootstrap(tenants, "vitals-cost").project().id();
        String traceId = SubstrateV2Fixtures.traceId();

        // The container. Producers that report cumulative usage on an agent span report cumulative cost
        // on it too — measured on production as 4,015 agent rows carrying 5.2B tokens over their llm
        // children. Ingest keeps typed usage and cost off container kinds for exactly this reason; the
        // scope here is the second line of defence.
        SpanRow agent = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "agent", RAN, RAN.plusSeconds(5));
        agent = fx.withPreviews(agent, null, null, "cs-checkout");
        agent = fx.withUsage(agent, 1_000_000L, 0L, null, null, null);
        fx.withCost(agent, "2.00", "0", null, null, "provided");

        // The llm leaf that actually made the call.
        SpanRow leaf = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), agent.id(), "llm", RAN, RAN.plusSeconds(4));
        leaf = fx.withPreviews(leaf, null, null, "cs-checkout");
        leaf = fx.withUsage(leaf, 1_000_000L, 0L, null, null, null);
        fx.withCost(leaf, "2.00", "0", null, null, "inferred");

        settle(pid, traceId, RAN, RAN.plusSeconds(5));

        Group total = compute(pid).total();
        assertEquals(
                0,
                new BigDecimal("2.00").compareTo(total.cost().usd().stripTrailingZeros()),
                "only the llm span is summed; got " + total.cost().usd());
        assertEquals(1, total.cost().calls(), "one generation, not two");
        assertEquals(0, total.cost().unpricedCalls());
        assertEquals(1_000_000L, total.cost().tokens(), "and the token total is the leaf's, once");
    }

    @Test
    @DisplayName("an unpriced span is counted, never silently valued at zero")
    void unpricedSpansAreCounted() {
        String pid =
                TenantFixture.bootstrap(tenants, "vitals-unpriced").project().id();
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRow span = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "llm", RAN, RAN.plusSeconds(2));
        span = fx.withPreviews(span, null, null, "cs-a");
        // Usage known, rate unknown. The cost columns stay null and cost_source says why.
        fx.withUsage(span, 500_000L, 500_000L, null, null, null);
        settle(pid, traceId, RAN, RAN.plusSeconds(2));

        Group total = compute(pid).total();
        assertEquals(1, total.cost().unpricedCalls(), "surfaced as unpriced");
        assertEquals(0, BigDecimal.ZERO.compareTo(total.cost().usd()), "and contributing no spend");
        assertEquals(1_000_000L, total.cost().tokens(), "its tokens still count — we know the volume, not the price");
    }

    @Test
    @DisplayName("duration is the trace's own start→end, ignoring a child that outlived it")
    void durationComesFromTheTraceRollup() {
        String pid =
                TenantFixture.bootstrap(tenants, "vitals-duration").project().id();
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRow root = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "agent", RAN, RAN.plusSeconds(10));
        fx.withPreviews(root, null, null, "cs-x");
        // An async child outliving the root by 50s. A min/max envelope would call this turn 60 seconds;
        // on production that artifact inflated p95 by ~10%.
        fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), root.id(), "tool", RAN.plusSeconds(1), RAN.plusSeconds(60));
        // The trace's own end is the root's, which is what the §7.1 timer folds in.
        settle(pid, traceId, RAN, RAN.plusSeconds(10));

        Group total = compute(pid).total();
        assertEquals(1, total.duration().turns(), "one turn");
        assertEquals(10_000L, total.duration().p50Ms(), "10s, not the 60s envelope");
    }

    @Test
    @DisplayName("a turn that never ended is counted, not dropped")
    void unterminatedTurnsAreCounted() {
        String pid = TenantFixture.bootstrap(tenants, "vitals-stuck").project().id();
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRow root = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "agent", RAN, null);
        fx.withPreviews(root, null, null, "cs-y");
        settle(pid, traceId, RAN, null);

        Group total = compute(pid).total();
        assertEquals(0, total.duration().turns(), "it contributes no duration");
        assertNull(total.duration().p95Ms(), "and therefore no percentile");
        assertEquals(1, total.duration().unterminated(), "but it is reported, so stuck turns cannot read as fast");
    }

    @Test
    @DisplayName("an unsettled trace is left out of the sums, but its duration still counts")
    void unsettledTracesAreExcludedFromSumsButNotFromDuration() {
        String pid = TenantFixture.bootstrap(tenants, "vitals-settle").project().id();
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRow root = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "llm", RAN, RAN.plusSeconds(1));
        root = fx.withPreviews(root, null, null, "cs-z");
        root = fx.withUsage(root, 1_000_000L, 0L, null, null, null);
        fx.withCost(root, "2.00", "0", null, null, "inferred");
        // Timers only: the trace has an end (so a latency) but has never been rolled up, which is
        // precisely what is_settled = false says.
        traces.applyBatchTimers(
                pid,
                List.of(new TraceV2Repository.TimerUpdate(
                        traceId, RAN.toString(), RAN.plusSeconds(1).toString(), true)));

        Group total = compute(pid).total();
        assertEquals(
                0,
                BigDecimal.ZERO.compareTo(total.cost().usd()),
                "spend waits for the trace to settle — a partial sum reported as a whole is worse than none");
        assertEquals(1, total.duration().turns(), "duration needs no settle: the trace's end IS the completion signal");
    }

    @Test
    @DisplayName("unterminated turns are counted PER call site, not only on the total")
    void unterminatedIsCountedPerCallSite() {
        String pid =
                TenantFixture.bootstrap(tenants, "vitals-stuck-cs").project().id();

        String finished = SubstrateV2Fixtures.traceId();
        fx.withPreviews(
                fx.span(pid, finished, SubstrateV2Fixtures.spanId(), null, "agent", RAN, RAN.plusSeconds(2)),
                null,
                null,
                "cs-healthy");
        settle(pid, finished, RAN, RAN.plusSeconds(2));

        for (int i = 0; i < 2; i++) {
            String stuck = SubstrateV2Fixtures.traceId();
            fx.withPreviews(
                    fx.span(pid, stuck, SubstrateV2Fixtures.spanId(), null, "agent", RAN, null),
                    null,
                    null,
                    "cs-hanging");
            settle(pid, stuck, RAN, null);
        }

        Vitals v = compute(pid);
        assertEquals(2, v.total().duration().unterminated(), "the total counts them");
        // The regression this guards: reporting 0 on every row would let a call site whose turns
        // increasingly hang show a shrinking sample and a flattering p95, with nothing to say why.
        assertEquals(
                2,
                groupFor(v, "cs-hanging").duration().unterminated(),
                "the hanging call site owns both, on its own row");
        assertEquals(0, groupFor(v, "cs-healthy").duration().unterminated(), "the healthy one owns none");
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private Vitals compute(String projectId) {
        return service.compute(projectId, 7, VitalsRepository.Dimension.CALL_SITE);
    }

    /**
     * Fold the trace's timers in and roll it up synchronously.
     *
     * <p>The scheduler is off in this context, so the only rollup that ever runs is the one a test asks
     * for — which is what lets these assertions talk about {@code is_settled} at all.
     */
    private void settle(String pid, String traceId, Instant startedAt, @Nullable Instant endedAt) {
        traces.applyBatchTimers(
                pid,
                List.of(new TraceV2Repository.TimerUpdate(
                        traceId, startedAt.toString(), endedAt == null ? null : endedAt.toString(), true)));
        jdbc.sql("UPDATE trace SET rollup_due_at = now() - interval '1 second'"
                        + " WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", traceId)
                .update();
        traces.claimDue(500);
        traces.recompute(pid, traceId);
    }

    private static Group groupFor(Vitals v, String key) {
        Group g =
                v.groups().stream().filter(x -> key.equals(x.key())).findFirst().orElse(null);
        assertNotNull(
                g,
                "expected a group for " + key + ", got "
                        + v.groups().stream().map(Group::key).toList());
        return g;
    }

    /** Kept honest: an empty project reports nothing rather than throwing. */
    @Test
    void anEmptyProjectComputesCleanly() {
        String pid = TenantFixture.bootstrap(tenants, "vitals-empty").project().id();
        Vitals v = compute(pid);
        assertEquals(0, v.total().duration().turns());
        assertTrue(v.groups().isEmpty());
    }
}
