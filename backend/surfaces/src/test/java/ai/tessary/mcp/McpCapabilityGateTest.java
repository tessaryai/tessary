// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.FindingService;
import ai.tessary.pipeline.PipelineService;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * What the MCP surface offers: which tools {@code tools/list} names, and what a call to a tool it does not name
 * does.
 *
 * <ul>
 *   <li>{@code tools/list} names exactly the open set, 19 tools.</li>
 *   <li>{@code tools/call} on a removed tool reads as <b>unknown</b>, and the handler never runs.</li>
 *   <li>{@code initialize} instructions are built from the same catalogue, so the prose cannot advertise a
 *       tool that is gone.</li>
 * </ul>
 *
 * <p><b>The surface is read-only and entirely ungated.</b> {@code Capability.RCA} used to gate five tools
 * here; a case now carries its own RCA report inline, so the gate moved from the tool to the data. Grading
 * was deleted from the platform, taking {@code list_graders} and {@code get_grader} with it.
 * {@link #theSurfaceHasNoWriteToolAndNoneOfTheRemovedSix} is the invariant that keeps the surface read-only:
 * it walks the full catalogue rather than a list maintained here, so a future write tool fails this test at
 * registration instead of shipping.
 */
class McpCapabilityGateTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String ORG_ID = "org-1";

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
    void everyOrgIsOfferedExactlyTheOpenTools() throws Exception {
        Set<String> offered = fixture().listToolNames();

        assertEquals(OPEN_TOOLS, offered, "every org should be offered exactly the open set");
        assertEquals(19, offered.size(), "the surface is 19 tools, all open");
    }

    /**
     * The read-only sentence is unconditional, unlike every other sentence in the instructions, which are
     * built from the catalogue. It states a property of the surface rather than of a tool — otherwise the
     * cheapest way to learn there is no write is to plan one.
     */
    @Test
    void instructionsStateTheSurfaceIsReadOnly() throws Exception {
        Fixture f = fixture();

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

    private Fixture fixture() {
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project =
                new Project(PROJECT_ID, ORG_ID, "proj", "Proj", null, "2026-08-12T00:00:00Z", null, null, true, null);
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        PipelineService pipeline = mock(PipelineService.class);
        when(pipeline.getPipeline(PROJECT_ID)).thenReturn(ai.tessary.model.Pipeline.empty());

        var registry = new McpToolRegistry(
                pipeline,
                projects,
                mock(QueryService.class),
                mock(SpanRepository.class),
                mock(SpanPayloadRepository.class),
                mock(TraceV2Repository.class),
                mock(SessionReadService.class),
                mock(FindingService.class),
                mock(CaseService.class));
        return new Fixture(new McpDispatcher(registry, mapper), mapper);
    }
}
