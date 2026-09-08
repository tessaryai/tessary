// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.substrate.BehaviorSubstrateRepository.TailWindow;
import ai.tessary.evals.storage.SessionRepository;
import ai.tessary.evals.storage.SpanPayloadRepository;
import ai.tessary.evals.storage.SpanRepository;
import ai.tessary.evals.storage.TraceV2Repository;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.SubstrateV2Fixtures;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The trailing-window read the saturation gate falls back to when a corpus has stopped growing.
 *
 * <p>Its own test, against real Postgres, because the lifecycle tests drive the gate through a
 * {@code trace_count} they set themselves — which is exactly how the rate this replaces shipped
 * broken. A profile's {@code trace_count} is a number in a column; the tail is a real slice of a real
 * corpus, and only the corpus can attest to which traces are in it.
 */
@SpringBootTest
class BehaviorTailWindowIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
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
    JdbcClient jdbc;

    @Autowired
    BehaviorSubstrateRepository substrate;

    @Autowired
    TenantService tenants;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    @Test
    @DisplayName("the tail is the most recent traces by EVENT time")
    void tailIsOrderedByEventTime() {
        String pid = TenantFixture.bootstrap(tenants, "drift-tail-eventtime")
                .project()
                .id();
        // Deliberately inverted against WRITE order: the traces that RAN most recently are the ones
        // written FIRST. In v1 that write order was a second clock (trace.created_at) that a backfill
        // could invert; v2 retired the column, so the only way to get this wrong now is to order by
        // something that is not started_at at all. The inversion still earns its place as the guard.
        for (int i = 0; i < 10; i++) {
            Instant ran = Instant.now().minus(Duration.ofDays(10 - i)); // i=9 ran most recently
            seedTrace(pid, "cs-tail", ran);
        }

        TailWindow window = substrate.tailWindow(pid, "cs-tail", 3).orElseThrow();

        assertEquals(3, window.traces(), "the slice holds exactly the requested count");
        Instant boundary = Instant.parse(window.boundary());
        // The three most recent RUNS are days 3, 2 and 1 back, so the boundary is ~3 days ago.
        assertTrue(
                boundary.isAfter(Instant.now().minus(Duration.ofDays(4))),
                "boundary must be the oldest of the three most recently RUN traces; got " + boundary);
        assertTrue(
                boundary.isBefore(Instant.now().minus(Duration.ofDays(2))),
                "and it must not be the newest one either; got " + boundary);
    }

    @Test
    @DisplayName("the tail is scoped to one call site, because epochs are")
    void tailIsScopedPerCallSite() {
        String pid =
                TenantFixture.bootstrap(tenants, "drift-tail-scope").project().id();
        Instant base = Instant.now().minus(Duration.ofHours(2));
        for (int i = 0; i < 6; i++) seedTrace(pid, "cs-busy", base.plusSeconds(i * 60L));
        for (int i = 0; i < 2; i++) seedTrace(pid, "cs-quiet", base.plusSeconds(i * 60L));

        assertEquals(
                6,
                substrate.tailWindow(pid, "cs-busy", 100).orElseThrow().traces(),
                "a scope sees only its own traces, never the project's");
        assertEquals(
                2,
                substrate.tailWindow(pid, "cs-quiet", 100).orElseThrow().traces(),
                "and the quiet scope is not inflated by the busy one — pooling them is what the "
                        + "per-call-site grain exists to prevent");
    }

    @Test
    @DisplayName("a scope with no traces yields no window at all, not a zero-trace one")
    void emptyScopeYieldsNoWindow() {
        String pid =
                TenantFixture.bootstrap(tenants, "drift-tail-empty").project().id();

        Optional<TailWindow> window = substrate.tailWindow(pid, "cs-nothing", 200);

        // count(*) returns a row holding 0 with a null min(), so the mapper has to reject it. A
        // TailWindow(0, null) would divide a unit count by zero traces in the caller.
        assertTrue(window.isEmpty(), "an empty slice is the absence of a measurement, not a rate of zero");
    }

    /** One trace under {@code callSiteId}, running at {@code ran}. */
    private void seedTrace(String pid, String callSiteId, Instant ran) {
        String traceId = SubstrateV2Fixtures.traceId();
        // The call site comes off the ENTRY POINT span and is carried onto the trace by the rollup, so
        // the scope only resolves with a root present and the rollup run.
        fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(SubstrateV2Fixtures.sessionId())
                .kind("agent")
                .name("loop")
                .callSiteId(callSiteId)
                .at(ran)
                .endedAt(ran.plusSeconds(1))
                .write();
        fx.rollup(pid, traceId);
    }
}
