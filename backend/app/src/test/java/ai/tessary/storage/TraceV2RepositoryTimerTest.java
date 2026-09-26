// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The §7.1 rollup timer, whose entire rule is one sentence: <b>the deadline only ever moves earlier,
 * never later.</b>
 *
 * <p>That rule is what removes the need for a hard cap. A trace that never goes quiet still fires at its
 * deadline, is re-armed by the next span, and fires again — so a long-running turn is refreshed with
 * current numbers roughly every ten seconds instead of showing nothing at all until it finishes. Get it
 * backwards and each arriving span pushes the rollup further out, a busy trace never rolls up, and the
 * only fix is a cap, at which point {@code is_settled} stops meaning "nothing has arrived since" and
 * starts meaning "we gave up waiting".
 *
 * <p>The claim/settle assertions here are a smoke check that the §7.3/§7.4 statements do what they say;
 * the full worker battery (crash recovery, replacement idempotence, the queue alarm) lives in
 * {@code TraceRollupWorkerIntegrationTest}, which owns the loop around them.
 *
 * <p>The scheduled beans are off in this context, and the property fingerprint is shared verbatim with the
 * write-path test so the two classes reuse one Spring context. The rollup worker claims due traces
 * GLOBALLY — with it running, {@link #expire} followed by {@code claimDue} is a race the test loses about
 * as often as the worker ticks.
 */
@SpringBootTest(
        properties = {
            "tessary.ingest.substrate.resolvers-enabled=false",
            "tessary.ingest.substrate.rollup-enabled=false"
        })
class TraceV2RepositoryTimerTest {

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
    private String traceId;
    private Instant t0;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
        pid = TenantFixture.bootstrap(tenants, "trace-v2-timer").project().id();
        t0 = Instant.parse("2026-08-12T10:00:00Z");
        traceId = SubstrateV2Fixtures.traceId();
        fx.trace(pid, traceId, t0);
        // One real span, because §7.2 will not settle a trace that has none. A span-less row is a shell
        // left behind when the write of its spans was lost, and settling one closes the books on nothing:
        // nothing re-claims a settled trace, so the spans that were meant to arrive can never complete it.
        // The timer rules under test are the same either way; this only keeps the fixture a trace.
        fx.llmSpan(pid, traceId, t0);
    }

    @Test
    @DisplayName("the first span of an unarmed trace arms it ten seconds out")
    void firstSpanArmsTheTrace() {
        assertNull(traces.findById(pid, traceId).orElseThrow().rollupDueAt(), "a created trace is not yet armed");

        arm(false);

        Instant due = Instant.parse(traces.findById(pid, traceId).orElseThrow().rollupDueAt());
        assertTrue(
                Duration.between(Instant.now(), due).toMillis() > 5_000,
                "a rootless trace waits the long window: we have no signal that the turn is over");
        assertTrue(Duration.between(Instant.now(), due).toMillis() <= 10_500);
    }

    @Test
    @DisplayName("a further span into an armed trace does not postpone the rollup")
    void theDeadlineNeverMovesLater() {
        arm(false);
        String first = traces.findById(pid, traceId).orElseThrow().rollupDueAt();

        arm(false);
        arm(false);

        assertEquals(
                first,
                traces.findById(pid, traceId).orElseThrow().rollupDueAt(),
                "each of these would have set a LATER now()+10s; LEAST is what discards them");
    }

    @Test
    @DisplayName("a root span pulls the deadline in to the short window")
    void rootPullsTheDeadlineIn() {
        arm(false);
        Instant rootless =
                Instant.parse(traces.findById(pid, traceId).orElseThrow().rollupDueAt());

        arm(true);

        TraceV2Row row = traces.findById(pid, traceId).orElseThrow();
        Instant withRoot = Instant.parse(row.rollupDueAt());
        assertTrue(
                withRoot.isBefore(rootless),
                "a root span means the turn is almost certainly finished, so the wait shortens");
        assertTrue(Duration.between(Instant.now(), withRoot).toMillis() <= 2_500);
        assertTrue(row.hasRootSpan(), "has_root_span latches once set, and drives the window for later spans");
    }

    @Test
    @DisplayName("once a trace has a root, later spans re-arm on the short window too")
    void hasRootSpanLatchesTheShortWindow() {
        arm(true);
        settle();

        arm(false);

        Instant due = Instant.parse(traces.findById(pid, traceId).orElseThrow().rollupDueAt());
        assertTrue(
                Duration.between(Instant.now(), due).toMillis() <= 2_500,
                "the CASE reads t.has_root_span too, not only this batch's has_root");
    }

    @Test
    @DisplayName("started_at only moves earlier and ended_at only moves later")
    void timestampsFoldByMinAndMax() {
        traces.applyBatchTimers(
                pid,
                List.of(new TraceV2Repository.TimerUpdate(
                        traceId, t0.toString(), t0.plusSeconds(5).toString(), false)));
        // A span that started before the trace's current start and ended before its current end.
        traces.applyBatchTimers(
                pid,
                List.of(new TraceV2Repository.TimerUpdate(
                        traceId,
                        t0.minusSeconds(2).toString(),
                        t0.plusSeconds(1).toString(),
                        false)));

        TraceV2Row row = traces.findById(pid, traceId).orElseThrow();
        assertEquals(t0.minusSeconds(2).toString(), row.startedAt(), "LEAST folded the earlier start in");
        assertEquals(t0.plusSeconds(5).toString(), row.endedAt(), "GREATEST refused the earlier end");
    }

    @Test
    @DisplayName("a batch with no ended_at at all leaves the trace open rather than closing it")
    void nullEndedAtIsIgnoredByGreatest() {
        traces.applyBatchTimers(
                pid,
                List.of(new TraceV2Repository.TimerUpdate(
                        traceId, t0.toString(), t0.plusSeconds(5).toString(), false)));
        traces.applyBatchTimers(pid, List.of(new TraceV2Repository.TimerUpdate(traceId, t0.toString(), null, false)));

        assertEquals(
                t0.plusSeconds(5).toString(),
                traces.findById(pid, traceId).orElseThrow().endedAt(),
                "GREATEST ignores NULLs — an unfinished span cannot un-finish the trace");
    }

    // ---- the claim/settle protocol these timers feed ------------------------------------------------

    /** One batch's worth of arming for this trace. */
    private void arm(boolean hasRoot) {
        traces.applyBatchTimers(pid, List.of(new TraceV2Repository.TimerUpdate(traceId, t0.toString(), null, hasRoot)));
    }

    /**
     * Bring this trace's deadline forward so the worker's {@code rollup_due_at <= now()} claim sees it.
     * Production waits out the real interval; a test that slept two seconds per case would be the slowest
     * suite in the repo and would still be racing the clock.
     */
    private void expire() {
        jdbc.sql("UPDATE trace SET rollup_due_at = now() - interval '1 second'"
                        + " WHERE project_id = :pid AND id = :id")
                .param("pid", pid)
                .param("id", traceId)
                .update();
    }

    /** Claim and recompute once — the worker's tick, run synchronously. */
    private void settle() {
        expire();
        traces.claimDue(500);
        traces.recompute(pid, traceId);
    }
}
