// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.SubstrateProperties;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.SpanRow;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.storage.TraceV2Row;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The rollup worker and its reaper against the real schema (substrate-model.md §7.2–§7.4).
 *
 * <h2>What is actually under test</h2>
 *
 * <p>One protocol, in three claims:
 *
 * <ol>
 *   <li><b>The claim clears the deadline, and that is what makes {@code is_settled} mean something.</b> A
 *       span arriving between the claim and the write re-arms it, the write sees a non-null deadline and
 *       declines to settle, and the trace fires again with that span in. {@code is_settled} therefore says
 *       "nothing has arrived since the last rollup" and can never be made to say "we stopped waiting".
 *   <li><b>Every rollup is a replacement, never a delta.</b> Firing twice writes the same numbers; a total
 *       corrupted by hand heals on the next fire. That is the property that makes crash recovery safe to
 *       be blunt about, and it is why no counter here is ever incremented.
 *   <li><b>Nothing is stranded.</b> A worker that dies between the claim and the write leaves a
 *       fingerprint the reaper recognises; re-arming a claim that was merely in flight costs one redundant
 *       recompute and corrupts nothing, because of (2).
 * </ol>
 *
 * <h2>Why nothing here sleeps</h2>
 *
 * <p>The worker and reaper are built here rather than injected, and driven through their synchronous
 * run-once methods. The scheduler is off in this context ({@code rollup-enabled=false}), so every claim in
 * these assertions is one this test made: a live worker claims due traces GLOBALLY, and a test that raced
 * it would be asserting against whichever of the two got there first.
 *
 * <p><b>Time is Postgres's, and only Postgres's.</b> The deadlines are {@code timestamptz} written by
 * {@code now()} inside the spec's own statements, so the tests move the clock the same way production
 * experiences it — by backdating the deadline — rather than through an app-side clock, which would be a
 * second opinion about whether a trace is due and any skew between the two lands squarely on the settle
 * protocol.
 */
@SpringBootTest(
        properties = {
            "tessary.ingest.substrate.resolvers-enabled=false",
            "tessary.ingest.substrate.rollup-enabled=false"
        })
class TraceRollupWorkerIntegrationTest {

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
    private TraceRollupMetrics metrics;
    private TraceRollupWorker worker;
    private TraceRollupReaper reaper;
    private String pid;
    private String traceId;
    private Instant t0;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
        pid = TenantFixture.bootstrap(tenants, "trace-rollup").project().id();
        t0 = Instant.parse("2026-08-12T10:00:00Z");
        traceId = SubstrateV2Fixtures.traceId();
        ownTheQueue();
        fx.trace(pid, traceId, t0);

        // A metrics bean of this test's own: the container's is a singleton every other class in the
        // context also feeds, so counter assertions on it would be reading someone else's traffic.
        metrics = new TraceRollupMetrics();
        worker = new TraceRollupWorker(traces, metrics, props(300));
        reaper = new TraceRollupReaper(traces, metrics, props(300));
    }

    // ---- claim, write, settle -----------------------------------------------------------------------

    @Test
    @DisplayName("the claim clears the deadline, and a quiet trace settles on the write that follows")
    void claimClearsTheDeadlineAndAQuietTraceSettles() {
        fx.withUsage(fx.llmSpan(pid, traceId, t0), 100L, 50L, null, null, null);
        arm(true);
        expire();

        TraceRollupWorker.Pass pass = worker.runOnce();

        assertTrue(pass.claimed() >= 1);
        assertEquals(pass.claimed(), pass.recomputed(), "every claimed trace was written back");
        assertEquals(0, pass.failed());

        TraceV2Row row = require(traceId);
        assertNull(row.rollupDueAt(), "the claim cleared it and nothing re-armed it");
        assertTrue(row.isSettled());
        assertEquals(1, row.spanCount());
        assertEquals(150L, row.totalTokens());
        assertNotNull(row.rolledUpAt());
        assertNotNull(row.rolledUpThrough(), "the watermark the lateness histogram measures arrivals against");
    }

    @Test
    @DisplayName("a span arriving between the claim and the write defeats the settle, and the re-fire includes it")
    void aSpanArrivingMidRollupDefeatsSettleAndIsIncludedOnTheRefire() {
        fx.withUsage(fx.llmSpan(pid, traceId, t0), 100L, 50L, null, null, null);
        arm(true);
        expire();

        // The worker's two statements, pulled apart so the arrival can land exactly between them.
        List<TraceV2Repository.Claim> claimed = traces.claimDue(500);
        assertTrue(claimed.stream().anyMatch(c -> c.traceId().equals(traceId)));

        fx.withUsage(
                fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "llm", t0.plusSeconds(1), t0.plusSeconds(2)),
                10L,
                5L,
                null,
                null,
                null);
        arm(true); // the §6.1 re-arm, in the same transaction as the span row in production

        traces.recompute(pid, traceId);

        TraceV2Row midFlight = require(traceId);
        assertFalse(midFlight.isSettled(), "the deadline was no longer clear, so the write declined to settle");
        assertNotNull(midFlight.rollupDueAt(), "and the re-arm survived, so the trace is still queued");
        assertEquals(2, midFlight.spanCount(), "the numbers are a replacement as of the read — never a lost update");

        expire();
        worker.runOnce();

        TraceV2Row refired = require(traceId);
        assertTrue(refired.isSettled(), "the re-fire found the deadline clear this time");
        assertEquals(2, refired.spanCount());
        assertEquals(165L, refired.totalTokens(), "the late span is in the totals, not merely in the table");
    }

    @Test
    @DisplayName("is_settled is honest: a late span un-settles a settled trace the moment it lands")
    void isSettledIsHonestAboutLateArrivals() {
        fx.llmSpan(pid, traceId, t0);
        arm(true);
        expire();
        worker.runOnce();
        assertTrue(require(traceId).isSettled());

        fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "tool", t0.plusSeconds(30), t0.plusSeconds(31));
        arm(false);

        TraceV2Row row = require(traceId);
        assertFalse(row.isSettled(), "settled means 'nothing has arrived since', so an arrival ends it");
        assertNotNull(row.rollupDueAt());
    }

    // ---- replacement, not accumulation --------------------------------------------------------------

    @Test
    @DisplayName("firing twice writes the same numbers — the recompute is a replacement, so it is idempotent")
    void doubleFireIsIdempotent() {
        fx.withUsage(fx.llmSpan(pid, traceId, t0), 100L, 50L, null, null, null);
        arm(true);
        expire();
        worker.runOnce();
        TraceV2Row first = require(traceId);

        expire();
        worker.runOnce();
        TraceV2Row second = require(traceId);

        assertEquals(first.spanCount(), second.spanCount());
        assertEquals(first.totalTokens(), second.totalTokens());
        assertEquals(first.inputTokens(), second.inputTokens());
        assertTrue(second.isSettled());
    }

    @Test
    @DisplayName("a total corrupted by hand heals on the next fire — no accumulation means no permanent drift")
    void corruptThenRecomputeSelfHeals() {
        fx.withUsage(fx.llmSpan(pid, traceId, t0), 100L, 50L, null, null, null);
        arm(true);
        expire();
        worker.runOnce();

        jdbc.sql("UPDATE trace SET total_tokens = 999999, span_count = 42, unpriced_spans = 7"
                        + " WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", traceId)
                .update();

        expire();
        worker.runOnce();

        TraceV2Row healed = require(traceId);
        assertEquals(150L, healed.totalTokens(), "a running sum would have had no repair path at all");
        assertEquals(1, healed.spanCount());
        assertEquals(1, healed.unpricedSpans());
    }

    // ---- crash recovery ------------------------------------------------------------------------------

    @Test
    @DisplayName("the reaper re-arms the fingerprint a dead worker leaves, and leaves a settled trace alone")
    void reaperReArmsStrandedTracesOnly() {
        fx.withUsage(fx.llmSpan(pid, traceId, t0), 100L, 50L, null, null, null);
        arm(true);
        expire();
        // The worker dies here: claimed (deadline cleared), never written back.
        traces.claimDue(500);

        TraceRollupReaper.Sweep sweep = reaper.sweepOnce();

        assertTrue(sweep.rearmed() >= 1);
        assertNotNull(require(traceId).rollupDueAt(), "armed for nobody, until the sweep put it back in the queue");

        // The re-armed deadline is now(), so the worker takes it on its very next pass.
        worker.runOnce();
        TraceV2Row recovered = require(traceId);
        assertTrue(recovered.isSettled());
        assertEquals(150L, recovered.totalTokens());

        assertEquals(0, reaper.sweepOnce().rearmed(), "a settled trace is not the fingerprint — it stays quiet");
        assertNull(require(traceId).rollupDueAt());
    }

    @Test
    @DisplayName("re-arming a healthy in-flight claim costs exactly one redundant recompute and corrupts nothing")
    void reapingAnInFlightClaimCostsOneRedundantRecompute() {
        fx.withUsage(fx.llmSpan(pid, traceId, t0), 100L, 50L, null, null, null);
        arm(true);
        expire();
        traces.claimDue(500);

        // The sweep cannot tell an in-flight claim from a dead one, and deliberately does not try.
        assertTrue(reaper.sweepOnce().rearmed() >= 1);

        // The "still alive" worker now completes the write it had claimed for.
        traces.recompute(pid, traceId);
        TraceV2Row afterInFlight = require(traceId);
        assertFalse(afterInFlight.isSettled(), "the sweep's re-arm is indistinguishable from a late span, correctly");
        assertEquals(150L, afterInFlight.totalTokens());

        // Which costs exactly one extra pass, and the second pass finds nothing left to do.
        TraceRollupWorker.Pass redundant = worker.runOnce();
        assertEquals(1, redundant.recomputed(), "one redundant recompute — the whole price of a blunt sweep");
        assertEquals(0, worker.runOnce().claimed(), "and the queue is drained, not looping");

        TraceV2Row settled = require(traceId);
        assertTrue(settled.isSettled());
        assertEquals(150L, settled.totalTokens(), "two rollups over one span is still one span's worth of tokens");
        assertEquals(1, settled.spanCount());
    }

    @Test
    @DisplayName("the sweep reports the queue it saw: depth armed, and how far behind the oldest deadline is")
    void sweepReportsQueueDepthAndOverdueAge() {
        fx.llmSpan(pid, traceId, t0);
        arm(false);
        expire();

        TraceRollupReaper.Sweep sweep = reaper.sweepOnce();

        assertTrue(sweep.queueDepth() >= 1, "an armed, un-rolled trace is queue depth");
        assertTrue(sweep.overdueMs() > 0, "and it is past its deadline, which is what the staleness alarm watches");
        assertEquals(sweep.queueDepth(), metrics.queueDepth());
        assertEquals(sweep.overdueMs(), metrics.overdueMs());

        worker.runOnce();
        assertEquals(0, reaper.sweepOnce().overdueMs(), "a drained queue is not behind on anything");
    }

    // ---- what the replacement counts -----------------------------------------------------------------

    @Test
    @DisplayName("unpriced_spans counts spans of ANY kind that carried usage — an unpriced tool call is spend too")
    void unpricedSpansCountsEveryKindThatCarriedUsage() {
        fx.withUsage(fx.llmSpan(pid, traceId, t0), 100L, 50L, null, null, null);
        fx.withUsage(
                fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "tool", t0, t0.plusSeconds(1)),
                20L,
                null,
                null,
                null,
                null);
        fx.withUsage(
                fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "embedding", t0, t0.plusSeconds(1)),
                30L,
                null,
                null,
                null,
                null);
        // Priced, so not a hole in the total.
        fx.withCost(
                fx.withUsage(
                        fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "llm", t0, t0.plusSeconds(1)),
                        40L,
                        null,
                        null,
                        null,
                        null),
                "0.000400",
                null,
                null,
                null,
                SpanRow.CostSource.INFERRED);
        // Unpriced but carrying no usage at all: nothing could have been billed, so it is not a hole.
        fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "tool", t0, t0.plusSeconds(1));

        arm(true);
        expire();
        worker.runOnce();

        TraceV2Row row = require(traceId);
        assertEquals(5, row.spanCount());
        assertEquals(3, row.unpricedSpans(), "llm + tool + embedding, all with usage and no rate");
        assertEquals(240L, row.totalTokens());
    }

    @Test
    @DisplayName("the traces list stays a single-table read: previews and call site are copied down from the root span")
    void rootSpanPreviewsAndCallSiteAreCopiedOntoTheTrace() {
        SpanRow root = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), null, "agent", t0, t0.plusSeconds(9));
        fx.withPreviews(root, "what the user asked", "what came back", "call-site-42");
        SpanRow child = fx.span(pid, traceId, SubstrateV2Fixtures.spanId(), root.id(), "llm", t0, t0.plusSeconds(2));
        fx.withPreviews(child, "an inner prompt", "an inner completion", "call-site-99");

        arm(true);
        expire();
        worker.runOnce();

        TraceV2Row row = require(traceId);
        assertEquals("what the user asked", row.inputPreview());
        assertEquals("what came back", row.outputPreview());
        assertEquals("call-site-42", row.callSiteId(), "the entry point is the ROOT's, not any span's");
    }

    @Test
    @DisplayName("a trace whose root has not landed yet is rolled up without previews rather than with a child's")
    void aTraceWithNoRootYetGetsNoPreviews() {
        SpanRow child = fx.span(
                pid, traceId, SubstrateV2Fixtures.spanId(), "parent-not-here-yet", "llm", t0, t0.plusSeconds(2));
        fx.withPreviews(child, "an inner prompt", "an inner completion", "call-site-99");

        arm(false); // no root in this batch, so has_root_span stays false
        expire();
        worker.runOnce();

        TraceV2Row row = require(traceId);
        assertEquals(1, row.spanCount(), "the rollup still ran — the previews are the only thing waiting");
        assertNull(row.inputPreview());
        assertNull(row.callSiteId());
    }

    // ---- the long-running turn -----------------------------------------------------------------------

    @Test
    @DisplayName("a long-running trace re-fires with fresh numbers every deadline — there is no cap and none is needed")
    void aLongRunningTraceKeepsRefiringWithFreshNumbers() {
        long expectedTokens = 0;
        for (int turn = 1; turn <= 4; turn++) {
            fx.withUsage(
                    fx.span(
                            pid,
                            traceId,
                            SubstrateV2Fixtures.spanId(),
                            null,
                            "llm",
                            t0.plusSeconds(turn * 10L),
                            t0.plusSeconds(turn * 10L + 1)),
                    100L,
                    50L,
                    null,
                    null,
                    null);
            expectedTokens += 150;
            arm(false);
            expire();

            assertEquals(1, worker.runOnce().recomputed(), "the trace fires at every deadline, however long it runs");

            TraceV2Row row = require(traceId);
            assertEquals(turn, row.spanCount(), "each fire is a full replacement, so the count is simply current");
            assertEquals(expectedTokens, row.totalTokens());
            assertTrue(row.isSettled(), "each fire settles honestly, and the next span un-settles it again");
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private SubstrateProperties props(int graceSeconds) {
        SubstrateProperties p = new SubstrateProperties();
        p.setRollupClaimLimit(500);
        p.setRollupReapGraceSeconds(graceSeconds);
        return p;
    }

    private TraceV2Row require(String id) {
        return traces.findById(pid, id).orElseThrow();
    }

    /**
     * Leave this test method the only occupant of the rollup queue.
     *
     * <p>The §7.3 claim is deployment-wide by design — there is no project in it, because the queue is one
     * queue — so a class that counts what a pass claimed has to own it. Traces every other test class in
     * this database left armed, or left claimed-and-unwritten, are taken out of both the queue and the
     * reaper's fingerprint here. Without this the counts below would depend on which suites ran first and
     * how long ago, which is the definition of a flaky assertion.
     */
    private void ownTheQueue() {
        jdbc.sql("""
                        UPDATE trace SET rollup_due_at = NULL, is_settled = true
                         WHERE project_id <> :pid
                           AND (rollup_due_at IS NOT NULL OR is_settled = false)
                        """).param("pid", pid).update();
    }

    /** One batch's worth of arming for this trace — the §7.1 update the write path issues. */
    private void arm(boolean hasRoot) {
        traces.applyBatchTimers(pid, List.of(new TraceV2Repository.TimerUpdate(traceId, t0.toString(), null, hasRoot)));
    }

    /**
     * Bring the deadline forward so the claim's {@code rollup_due_at <= now()} sees it. Production waits
     * out the real two or ten seconds; a suite that slept them would be the slowest in the repo and would
     * still be racing a clock it does not own.
     */
    private void expire() {
        jdbc.sql("UPDATE trace SET rollup_due_at = now() - interval '1 second'"
                        + " WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", traceId)
                .update();
    }
}
