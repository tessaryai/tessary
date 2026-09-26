// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Which call site a trace is scoped to: the choice that decides which baseline it is fitted into.
 *
 * <p>A trace legitimately spans several call sites: {@code tessary.call_site.id} binds a span, and the
 * vitals slice groups spend and tool-error rate by the span's own. Drift is trace-grain and must
 * collapse that to one, and the right one is the entry point, what the product invoked, not whichever
 * child the agent happened to reach.
 *
 * <p>The collapse happens once, in the rollup recompute, which copies the root span's call site onto the
 * trace; a sweep then reads a column. So these tests seed spans, run the real rollup, and assert on what
 * the trace ended up scoped to.
 *
 * <p>Every assertion here is on {@link BehaviorSubstrateRepository}, shared by every trace-grain
 * classifier, which is why this lives as a substrate test rather than a drift test.
 */
@SpringBootTest
class TraceScopeIntegrationTest {

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository v2traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    BehaviorSubstrateRepository substrate;

    @Autowired
    TenantService tenants;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, v2traces, spans, payloads, jdbc);
    }

    @Test
    @DisplayName("an untagged root leaves the trace unattributed rather than borrowing a child's scope")
    void untaggedRootStaysUnattributed() {
        String pid = tenant("drift-scope-fallback").project().id();
        Instant t0 = Instant.now().minusSeconds(3_600);
        String traceId = SubstrateV2Fixtures.traceId();

        // Root carries no call site: a producer that tags only the spans it owns. The entry point either
        // declared a scope or it did not, and "did not" is its own bucket that nothing else is pooled into
        // rather than borrowing a child's scope.
        seedSpan(pid, traceId, "root", null, "agent", "loop", null, t0);
        seedSpan(pid, traceId, "aaaa-late", "root", "tool", "late", "policy.late", t0.plusSeconds(9));
        seedSpan(pid, traceId, "bbbb-early", "root", "llm", "early", "policy.early", t0.plusSeconds(1));
        fx.rollup(pid, traceId);

        BehaviorSubstrateRepository.TraceHead head = headOf(pid, traceId);

        assertEquals(
                BehaviorSubstrateRepository.UNATTRIBUTED,
                head.callSiteId(),
                "an untagged entry point is unattributed, never silently attributed to a child");
    }

    private BehaviorSubstrateRepository.TraceHead headOf(String projectId, String traceId) {
        return substrate.tracesAfter(projectId, null, null, 50).stream()
                .filter(h -> traceId.equals(h.traceId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("trace not returned by tracesAfter: " + traceId));
    }

    private SpanRef seedSpan(
            String projectId,
            String traceId,
            String spanId,
            @Nullable String parentSpanId,
            String kind,
            String name,
            @Nullable String callSiteId,
            Instant startedAt) {
        return fx.spanSeed(projectId)
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .kind(kind)
                .name(name)
                .callSiteId(callSiteId)
                .at(startedAt)
                .endedAt(startedAt.plusSeconds(1))
                .writeRef();
    }

    /**
     * A plain tenant. Nothing here reads a classifier row; the assertions are on spans and the rollup.
     */
    private TenantFixture.Setup tenant(String name) {
        return TenantFixture.bootstrap(tenants, name);
    }
}
