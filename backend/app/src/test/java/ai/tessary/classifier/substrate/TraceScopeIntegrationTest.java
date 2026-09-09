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
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
 * <p>Alongside the scoping cases sit three reads that the same substrate query answers and that nothing
 * else covers end to end: the parent column bounding the fan-out, a tool's symbol coming from its
 * {@code tool_call} name rather than the gen_ai span name, and an MCP call resolving through the raw
 * attribute it carries instead of a {@code tool_call} row it never gets.
 *
 * <p>Every assertion here is on {@link BehaviorSubstrateRepository} and {@link TrajectoryAssembler},
 * both shared by every trace-grain classifier, which is why this lives as a substrate test rather than a
 * drift test.
 */
@SpringBootTest
class TraceScopeIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

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
    TrajectoryAssembler assembler;

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
    @DisplayName("the trace is scoped to its root span's call site, not a child's")
    void scopeComesFromTheEntryPoint() {
        String pid = tenant("drift-scope").project().id();
        Instant t0 = Instant.now().minusSeconds(3_600);
        String traceId = SubstrateV2Fixtures.traceId();

        // Ids are chosen, not generated, so the root sorts last by id: a child would win any ordering
        // that did not first restrict to parentless spans. The root's own call site is the only one the
        // recompute may take.
        String rootId = "zzzz-root";
        seedSpan(pid, traceId, rootId, null, "agent", "loop", "policy.conversation", t0);
        seedSpan(pid, traceId, "aaaa-1", rootId, "llm", "chat", "policy.answer", t0.plusSeconds(1));
        seedSpan(pid, traceId, "aaaa-2", rootId, "tool", "verify", "policy.verify_member", t0.plusSeconds(2));
        seedSpan(pid, traceId, "aaaa-3", rootId, "retrieval", "s", "policy.retrieve_wording", t0.plusSeconds(3));
        fx.rollup(pid, traceId);

        BehaviorSubstrateRepository.TraceHead head = headOf(pid, traceId);

        assertEquals(
                "policy.conversation",
                head.callSiteId(),
                "the scope must be the entry point; a child's call site would fit a baseline of "
                        + "'traces that happened to contain this tool' rather than 'traffic that entered here'");
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

    @Test
    @DisplayName("parent_span_id survives the query and bounds the fan-out")
    void theParentColumnReachesTheReduction() {
        String pid = tenant("drift-fanout").project().id();
        Instant t0 = Instant.now().minusSeconds(3_600);
        String traceId = SubstrateV2Fixtures.traceId();

        // The dispatching call and two tools sit under the agent root; a sub-agent's own tool sits
        // under the sub-agent. Only the first two are the batch.
        SpanRef root = seedSpan(pid, traceId, null, "agent", "loop", "cs-a", t0);
        seedSpan(pid, traceId, root.spanId(), "llm", "llm -> a, b", "cs-a", t0.plusSeconds(1));
        seedSpan(pid, traceId, root.spanId(), "tool", "verify_member", "cs-a", t0.plusSeconds(2));
        seedSpan(pid, traceId, root.spanId(), "tool", "get_policy", "cs-a", t0.plusSeconds(3));
        SpanRef sub = seedSpan(pid, traceId, root.spanId(), "agent", "sub", "cs-a", t0.plusSeconds(4));
        seedSpan(pid, traceId, sub.spanId(), "tool", "nested", "cs-a", t0.plusSeconds(5));
        fx.rollup(pid, traceId);

        List<BehaviorSubstrateRepository.TraceHead> heads = List.of(headOf(pid, traceId));
        List<String> symbols = assembler.assemble(pid, heads, Set.of()).get(0).symbols();

        // Asserted through assemble() rather than reduce(): a dropped column or a wrong alias degrades to
        // all-null parents, which still yields a plausible-looking sequence with no fan-out at all. Only
        // an end-to-end assertion can tell "grouped correctly" from "never grouped".
        assertEquals(
                List.of(
                        "^",
                        "agent:loop",
                        "fork:2",
                        "tool:get_policy",
                        "tool:verify_member",
                        "join",
                        "agent:sub",
                        "tool:nested",
                        "$"),
                symbols,
                "the two siblings form the batch; the sub-agent's own tool stays outside it");
    }

    @Test
    @DisplayName("a tool's symbol is its tool_call name, not the gen_ai span name")
    void theToolSymbolComesFromTheToolCallName() {
        String pid = tenant("drift-toolname").project().id();
        Instant t0 = Instant.now().minusSeconds(3_600);
        String traceId = SubstrateV2Fixtures.traceId();

        // The gen_ai convention names a tool span "{operation} {target}", and ingest puts the bare
        // tool name on tool_call. Reading the span name instead would give `tool:execute_tool_verify_member`
        // rather than `tool:verify_member`, a whole-alphabet divergence on a common producer shape.
        SpanRef root = seedSpan(pid, traceId, null, "agent", "loop", "cs-a", t0);
        SpanRef tool =
                seedSpan(pid, traceId, root.spanId(), "tool", "execute_tool verify_member", "cs-a", t0.plusSeconds(1));
        // TWO tool_call rows for the one span. `tool_call` is unique only on
        // (observation_id, source_external_id) and that column is nullable, so a re-ingest genuinely
        // leaves duplicates. With a single row this would pass against a plain join too, and would be
        // asserting nothing.
        fx.toolCall(pid, tool, "verify_member", null, t0.plusSeconds(1));
        fx.toolCall(pid, tool, "verify_member", null, t0.plusSeconds(1));

        List<BehaviorSubstrateRepository.TraceAction> actions = substrate.actionsForTraces(pid, List.of(traceId));
        assertEquals(2, actions.size(), "one action per span: a fan-out inflates counts and fan-out width");
        assertEquals(
                "tool:verify_member",
                symbolOf(actions, "tool"),
                "the operation verb belongs to the span name, not to the alphabet");
    }

    @Test
    @DisplayName("an MCP call carries no tool_call row and still resolves to its tool name")
    void anMcpCallResolvesThroughTheRawAttribute() {
        String pid = tenant("drift-mcp").project().id();
        Instant t0 = Instant.now().minusSeconds(3_600);
        String traceId = SubstrateV2Fixtures.traceId();

        // Ingest mints a tool_call row only for kind `tool`, so an MCP call has none to read even though
        // it dispatches and fans out like any other. The raw attribute is what keeps
        // `mcp:execute_tool_search_docs` out of the alphabet; it lives on span_payload, which is why the
        // read pays a bounded 1:1 join for it rather than a column.
        SpanRef root = seedSpan(pid, traceId, null, "agent", "loop", "cs-a", t0);
        fx.spanSeed(pid)
                .traceId(traceId)
                .spanId(SubstrateV2Fixtures.spanId())
                .parentSpanId(root.spanId())
                .kind("mcp")
                .name("execute_tool search_docs")
                .callSiteId("cs-a")
                .at(t0.plusSeconds(1))
                .payload(null, null, "{\"gen_ai.tool.name\": \"search_docs\"}")
                .write();

        List<BehaviorSubstrateRepository.TraceAction> actions = substrate.actionsForTraces(pid, List.of(traceId));
        assertEquals("mcp:search_docs", symbolOf(actions, "mcp"));
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
            @Nullable String parentSpanId,
            String kind,
            String name,
            @Nullable String callSiteId,
            Instant startedAt) {
        return seedSpan(
                projectId, traceId, SubstrateV2Fixtures.spanId(), parentSpanId, kind, name, callSiteId, startedAt);
    }

    /** A span with a caller-chosen id, so a test can control where it sorts by id. */
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

    private String symbolOf(List<BehaviorSubstrateRepository.TraceAction> actions, String kind) {
        return actions.stream()
                .filter(a -> kind.equals(a.kind()))
                .map(a -> ActionSymbol.of(a.kind(), a.name(), a.isError()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " action returned"));
    }

    /**
     * A plain tenant. Nothing here reads a classifier row; the assertions are on spans, the rollup, and
     * the assembler.
     */
    private TenantFixture.Setup tenant(String name) {
        return TenantFixture.bootstrap(tenants, name);
    }
}
