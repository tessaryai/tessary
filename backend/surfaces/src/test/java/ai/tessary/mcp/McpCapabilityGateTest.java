// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.plan.CapabilityService.CapabilitySet;
import ai.tessary.query.QueryService;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.traces.SessionReadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The capability gate on the MCP surface: which tools an org is <b>offered</b>, and what a call to a tool it
 * is not offered does.
 *
 * <p>This surface's capability gate had no test. Every other MCP test stubs every
 * capability on — deliberately, and each says so ("these tests exercise the tools themselves, not the
 * gate") — so nothing anywhere asserted that a partner's {@code tools/list} is the short list. The registry
 * makes the argument for why that matters in its own javadoc: offering a tool to an org that cannot use it is
 * worse than a 403, because the model plans around a tool that cannot work and burns a turn discovering it.
 * An argument with no test is a comment.
 *
 * <p>Both halves are asserted, because they are separate code paths ({@code availableFor} for the catalogue,
 * {@code availableTool} for the call) and a client can hold a stale catalogue:
 *
 * <ul>
 *   <li>{@code tools/list} omits every tool whose capability the org lacks, and keeps every open one.</li>
 *   <li>{@code tools/call} on a withheld tool reads as <b>unknown</b>, not forbidden — the deliberate
 *       posture, since there is nothing a partner can do about a capability they do not hold and naming it
 *       only tells them what is being withheld. The handler must not run.</li>
 *   <li>{@code initialize} instructions are built from the same offer, so the prose cannot advertise a tool
 *       the catalogue withholds.</li>
 * </ul>
 *
 * <p><b>The surface is read-only and entirely ungated.</b> {@code Capability.RCA} used to
 * gate five tools here; it now gates none, because a case carries its own RCA report inline and an org
 * without RCA has no report rows to inline — the gate moved from the tool to the data. {@code GRADERS}
 * gated the last two, {@code list_graders} and {@code get_grader}; grading was deleted from the platform,
 * taking the capability with it. {@link #theSurfaceHasNoWriteToolAndNoneOfTheRemovedSix} is the invariant that keeps
 * the surface read-only: it walks the full catalogue rather than a list maintained here, so a future write
 * tool fails this test at registration instead of shipping.
 */
class McpCapabilityGateTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String ORG_ID = "org-1";

    /**
     * The tools deleted from this surface. Named here so that re-adding one under its old name fails a
     * test rather than quietly restoring a surface we argued our way out of: five were the triage/RCA pair
     * (spend, and reports that now ride on the case that owns them), one was the last write, and three
     * went with grading when it was removed.
     */
    private static final Set<String> REMOVED_TOOLS = Set.of(
            "propose_grader_edit",
            "run_triage",
            "get_triage",
            "latest_triage",
            "list_rca_reports",
            "get_rca_report",
            // Grading left the platform, so the two grader reads and the quality-dimension list
            // have nothing behind them. Named here for the same reason as the other six.
            "list_graders",
            "get_grader",
            "list_quality_dimensions");

    /**
     * Names that would mean a write. Matched as a prefix over the whole catalogue, because the risk this
     * guards is not a specific tool coming back — it is the next write being added as one more
     * {@code add(...)} call, without anyone re-deciding that an agent may act on a customer's project.
     */
    private static final List<String> WRITE_PREFIXES = List.of("propose_", "run_", "create_", "update_", "delete_");

    /**
     * Open to every org: the launch product's own output and the reads it rests on. A tool leaving this set
     * is a product decision, so it should break this test and be argued for in the diff.
     */
    private static final Set<String> OPEN_TOOLS = Set.of(
            "get_project",
            "list_call_sites",
            "list_failure_modes",
            "list_cases",
            "get_case",
            "list_findings",
            "get_finding",
            // The evidence door is open for the same reason get_finding is: it returns ids into the
            // project's own substrate, gated by the detector the finding belongs to rather than by a
            // capability of its own.
            "get_finding_evidence",
            // Ungated because it describes the query schema, not a project's rows: withholding it would only
            // force an org to guess the field names of tools it already holds.
            "describe_dataset",
            "query_count",
            "query_timeseries",
            "query_facets",
            "query_search",
            // The substrate readers are open for the same reason the raw reads are: they page the project's
            // own traffic, which the token already scopes, and a partner who cannot list a trace cannot find
            // the id that get_trace needs. list_spans' payload opt-in is no exception — it returns exactly
            // what get_span returns, for spans the same token could already fetch one at a time.
            "list_traces",
            "list_spans",
            "list_sessions",
            "get_session",
            "get_span",
            "get_trace");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void launchPartnerIsOfferedOnlyTheOpenTools() throws Exception {
        // The launch configuration: no capability at all. No tool on this surface is gated,
        // so the open set IS the catalogue — which the next test asserts from the other direction.
        Fixture f = fixture(EnumSet.noneOf(Capability.class));

        Set<String> offered = f.listToolNames();

        assertEquals(OPEN_TOOLS, offered, "a partner holding no capability should be offered exactly the open set");
    }

    /**
     * The gate that no longer exists, asserted as an equality rather than an absence: holding RCA and holding
     * nothing produce the same catalogue. Written this way because the old failure would have been silent —
     * five tools reappearing for RCA orgs only, on a capability every launch partner has off, so no partner's
     * `tools/list` would have shown it.
     */
    @Test
    void rcaCapabilityChangesNothingAboutWhatIsOffered() throws Exception {
        Set<String> withoutRca = fixture(EnumSet.noneOf(Capability.class)).listToolNames();
        Set<String> withRca = fixture(EnumSet.of(Capability.RCA)).listToolNames();

        assertEquals(withoutRca, withRca, "RCA gates no MCP tool — a report reaches MCP inlined on its case");
    }

    @Test
    void everyToolIsOfferedWhenEveryCapabilityIsHeld() throws Exception {
        Fixture f = fixture(EnumSet.allOf(Capability.class));

        Set<String> offered = f.listToolNames();

        assertEquals(OPEN_TOOLS, offered, "holding everything offers exactly the open set — no tool is gated");
        assertEquals(19, offered.size(), "the surface is 19 tools, all open");
    }

    /**
     * The invariant behind the sentence {@code initialize} tells every client on connect: <i>every tool is
     * read-only</i>. Walks the catalogue an org holding everything is offered — so it cannot be satisfied by a
     * tool hiding behind a capability — and fails on a write-shaped name or on any of the six removed tools
     * coming back. A promise made to every client on connect should not rest on whoever reviews the next
     * {@code add(...)} call noticing.
     */
    @Test
    void theSurfaceHasNoWriteToolAndNoneOfTheRemovedSix() throws Exception {
        Set<String> everything = fixture(EnumSet.allOf(Capability.class)).listToolNames();

        for (String name : everything) {
            for (String prefix : WRITE_PREFIXES) {
                assertFalse(
                        name.startsWith(prefix),
                        name + " is write-shaped. Adding a write is a product decision, not a registration: it"
                                + " invalidates the read-only sentence initialize sends every client.");
            }
        }
        for (String gone : REMOVED_TOOLS) {
            assertFalse(everything.contains(gone), gone + " was removed in the read-only cutover and must stay gone");
        }
    }

    @Test
    void callingAWithheldToolReadsAsUnknownAndNeverReachesTheHandler() throws Exception {
        // A client holding a stale tool list, or one guessing: the gate has to hold at the call too, since
        // tools/list is only advice.
        Fixture f = fixture(EnumSet.noneOf(Capability.class));

        Map<String, Object> result = f.callTool("get_grader", "{\"grader_id\":\"g-1\"}");

        assertEquals(Boolean.TRUE, result.get("isError"));
        String text = errorText(result);
        assertTrue(text.contains("unknown tool"), "a withheld tool must read as unknown, got: " + text);
        assertFalse(
                text.toLowerCase(java.util.Locale.ROOT).contains("capab"),
                "the error must not name the capability being withheld, got: " + text);
    }

    @Test
    void anOpenToolStillWorksWithNoCapabilities() throws Exception {
        Fixture f = fixture(EnumSet.noneOf(Capability.class));

        Map<String, Object> result = f.callTool("get_project", "{}");

        assertEquals(Boolean.FALSE, result.get("isError"), "get_project is open — a partner must be able to ask");
        JsonNode body = mapper.valueToTree(Objects.requireNonNull(result.get("structuredContent")));
        assertEquals(PROJECT_ID, body.get("id").asText());
    }

    @Test
    void instructionsNeverAdvertiseAWithheldTool() throws Exception {
        Fixture f = fixture(EnumSet.noneOf(Capability.class));

        JsonRpc.Response r = f.dispatcher.dispatch(f.req(1, "initialize", mapper.readTree("{}")), f.ctx());
        assertNotNull(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                Objects.requireNonNull(Objects.requireNonNull(r).result());
        String instructions = Objects.requireNonNull(result.get("instructions")).toString();

        for (String gone : REMOVED_TOOLS) {
            assertFalse(
                    instructions.contains(gone),
                    "instructions must not mention the removed " + gone + ": " + instructions);
        }
        // ...and it does still describe what the partner CAN do, so an empty offer is not the reason it passed.
        assertTrue(instructions.contains("list_cases"), "instructions should still describe the open tools");
    }

    /**
     * The read-only sentence is unconditional, unlike every other sentence in the instructions, which are
     * built from the offer. It states a property of the surface rather than of a tool, so a partner holding
     * nothing must still be told it — otherwise the cheapest way to learn there is no write is to plan one.
     */
    @Test
    void instructionsStateTheSurfaceIsReadOnlyEvenWithNoCapabilities() throws Exception {
        Fixture f = fixture(EnumSet.noneOf(Capability.class));

        JsonRpc.Response r = f.dispatcher.dispatch(f.req(1, "initialize", mapper.readTree("{}")), f.ctx());
        assertNotNull(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                Objects.requireNonNull(Objects.requireNonNull(r).result());
        String instructions = Objects.requireNonNull(result.get("instructions")).toString();

        assertTrue(
                instructions.contains("read-only"),
                "the read-only statement is not conditional on the offer: " + instructions);
    }

    // ------------------------------------------------------------------ fixture

    private record Fixture(McpDispatcher dispatcher, ObjectMapper mapper) {

        TenantContext ctx() {
            return new TenantContext("user-1", null, ORG_ID, PROJECT_ID, "member", "tok-1");
        }

        JsonRpc.Request req(int id, String method, @Nullable JsonNode params) {
            return new JsonRpc.Request("2.0", IntNode.valueOf(id), method, params);
        }

        Set<String> listToolNames() {
            JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/list", null), ctx());
            assertNotNull(r);
            assertNull(Objects.requireNonNull(r).error(), "tools/list should not be a JSON-RPC error");
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) Objects.requireNonNull(r.result());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tools = (List<Map<String, Object>>) Objects.requireNonNull(result.get("tools"));
            List<String> names = new ArrayList<>();
            for (Map<String, Object> t : tools)
                names.add(Objects.requireNonNull(t.get("name")).toString());
            return Set.copyOf(names);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> callTool(String name, String argsJson) throws Exception {
            JsonNode params = mapper.readTree("{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}");
            JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/call", params), ctx());
            assertNotNull(r);
            assertNull(Objects.requireNonNull(r).error(), "expected a tool result, not a JSON-RPC error");
            return (Map<String, Object>) Objects.requireNonNull(r.result());
        }
    }

    /** A dispatcher whose org holds exactly {@code held} and nothing else. */
    private Fixture fixture(Set<Capability> held) {
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project =
                new Project(PROJECT_ID, ORG_ID, "proj", "Proj", null, "2026-08-12T00:00:00Z", null, null, true, null);
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        PipelineService pipeline = mock(PipelineService.class);
        when(pipeline.getPipeline(PROJECT_ID)).thenReturn(ai.tessary.model.Pipeline.empty());

        Map<Capability, Boolean> resolved = new EnumMap<>(Capability.class);
        for (Capability c : Capability.values()) resolved.put(c, held.contains(c));
        CapabilityService capabilities = mock(CapabilityService.class);
        when(capabilities.resolve(eq(ORG_ID))).thenReturn(new CapabilitySet(resolved));
        for (Capability c : Capability.values()) {
            when(capabilities.isEnabled(eq(ORG_ID), eq(c))).thenReturn(held.contains(c));
        }

        var registry = new McpToolRegistry(
                pipeline,
                projects,
                mock(QueryService.class),
                mock(SpanRepository.class),
                mock(SpanPayloadRepository.class),
                mock(TraceV2Repository.class),
                mock(SessionReadService.class),
                capabilities,
                mock(FindingService.class),
                mock(CaseService.class));
        return new Fixture(new McpDispatcher(registry, mapper), mapper);
    }

    private static String errorText(Map<String, Object> result) {
        assertEquals(Boolean.TRUE, result.get("isError"), "expected isError=true");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) Objects.requireNonNull(result.get("content"));
        return Objects.requireNonNull(content.get(0).get("text")).toString();
    }
}
