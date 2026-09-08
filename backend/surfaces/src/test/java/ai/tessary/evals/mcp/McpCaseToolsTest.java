// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.cases.CaseDtos.CaseDetailView;
import ai.tessary.evals.cases.CaseDtos.CaseView;
import ai.tessary.evals.cases.CaseDtos.CasesPage;
import ai.tessary.evals.cases.CaseDtos.WatchingView;
import ai.tessary.evals.cases.CaseService;
import ai.tessary.evals.classifier.finding.FindingService;
import ai.tessary.evals.model.Pipeline;
import ai.tessary.evals.open.errors.CaseError;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.pipeline.PipelineService;
import ai.tessary.evals.plan.Capability;
import ai.tessary.evals.plan.CapabilityService;
import ai.tessary.evals.plan.CapabilityService.CapabilitySet;
import ai.tessary.evals.query.QueryService;
import ai.tessary.evals.rca.RcaDtos.Hypothesis;
import ai.tessary.evals.rca.RcaDtos.RcaReportView;
import ai.tessary.evals.rca.RcaDtos.RuledOutCheck;
import ai.tessary.evals.storage.SpanPayloadRepository;
import ai.tessary.evals.storage.SpanRepository;
import ai.tessary.evals.storage.TraceV2Repository;
import ai.tessary.evals.tenant.Project;
import ai.tessary.evals.tenant.ProjectRepository;
import ai.tessary.evals.traces.SessionReadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code list_cases} / {@code get_case} MCP tools, read through {@link CaseService} — the same seam
 * {@code CaseController} uses, so a case reads identically here and in the UI. The service is mocked; this
 * pins the MCP wrapper contract (project scoping, arg mapping and defaults, page clamping, verbatim view
 * rendering, error mapping), not the case logic.
 *
 * <p>A case is what the launch product produces — the three default-on classifiers sweep a partner's traffic
 * and open cases — and until these tools existed an agent holding a partner's token could read raw spans and
 * query aggregates but could not ask what was wrong with the project. That is why the pair is ungated, which
 * {@link McpCapabilityGateTest} pins separately.
 *
 * <p><b>The {@code watching} coverage block is asserted here too, on {@code get_project}.</b> It used to ride
 * along with {@code list_cases}, and it is the only signal separating "nothing is wrong" from "nothing is
 * arriving" (launch requirement E5) — so the test that the flat page dropped it and the test that the project
 * read picked it up belong side by side. Either one alone would pass while the signal was lost.
 */
class McpCaseToolsTest {

    private static final String PROJECT_ID = "proj-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private CaseService cases;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setup() {
        this.cases = mock(CaseService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        Project project =
                new Project(PROJECT_ID, "org-1", "proj", "Proj", null, "2026-08-12T00:00:00Z", null, null, true, null);
        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        PipelineService pipelines = mock(PipelineService.class);
        when(pipelines.getPipeline(PROJECT_ID)).thenReturn(Pipeline.empty());

        // Every capability on: this exercises the tools, not the gate (McpCapabilityGateTest owns that).
        Map<Capability, Boolean> allOn = new EnumMap<>(Capability.class);
        for (Capability c : Capability.values()) allOn.put(c, true);
        CapabilityService capabilities = mock(CapabilityService.class);
        when(capabilities.resolve(any())).thenReturn(new CapabilitySet(allOn));
        when(capabilities.isEnabled(any(), any())).thenReturn(true);

        var registry = new McpToolRegistry(
                pipelines,
                projects,
                mock(QueryService.class),
                mock(SpanRepository.class),
                mock(SpanPayloadRepository.class),
                mock(TraceV2Repository.class),
                mock(SessionReadService.class),
                capabilities,
                mock(FindingService.class),
                cases);
        this.dispatcher = new McpDispatcher(registry, mapper);
    }

    @Test
    void bothCaseToolsAreListed() {
        List<String> names = toolNames();
        assertTrue(names.contains("list_cases"), names.toString());
        assertTrue(names.contains("get_case"), names.toString());
    }

    /**
     * The lifecycle writes are deliberately absent. {@code resolve} / {@code absorb} / {@code mute} /
     * {@code unmute} all exist on {@link CaseService} and each records a human judgement — {@code absorb}
     * additionally moves the detector's reference so the level that fired becomes the new baseline. Adding one
     * should be a decision argued in a diff, not a tool that appears because the service method was there.
     */
    @Test
    void caseLifecycleWritesAreNotExposedAsTools() {
        for (String name : toolNames()) {
            assertNull(
                    name.matches("(resolve|absorb|mute|unmute)_case|case_(resolve|absorb|mute|unmute)") ? name : null,
                    "case lifecycle writes must stay in the UI, found tool: " + name);
        }
    }

    /**
     * The default page is the open cases. "What is wrong with this project" is not a question about closures,
     * and a caller that omitted {@code state} must not get a first page of last month's history.
     */
    @Test
    void listCases_defaultsToOpenAtTheHousePageSizeAndRendersThePageFlat() throws Exception {
        when(cases.page(eq(PROJECT_ID), any(), any(), any(), anyInt(), any()))
                .thenReturn(new CasesPage(List.of(sampleCase("case-1", "C-1", "open")), "cursor-2"));

        JsonNode body = structured(callTool("list_cases", "{}"));

        verify(cases).page(PROJECT_ID, "open", null, null, 50, null);
        assertEquals(1, body.get("cases").size());
        assertEquals("C-1", body.get("cases").get(0).get("reference").asText());
        assertEquals("cursor-2", body.get("next_cursor").asText());
    }

    /** Filters and the cursor reach the service under the service's own names, untranslated. */
    @Test
    void listCases_passesStateDetectorCallSiteAndCursorThrough() throws Exception {
        when(cases.page(eq(PROJECT_ID), any(), any(), any(), anyInt(), any()))
                .thenReturn(new CasesPage(List.of(), null));

        JsonNode body = structured(callTool(
                "list_cases",
                "{\"state\":\"resolved\",\"detector\":\"tool_error\",\"call_site_id\":\"cs-7\","
                        + "\"limit\":10,\"cursor\":\"tok\"}"));

        verify(cases).page(PROJECT_ID, "resolved", "tool_error", "cs-7", 10, "tok");
        // Last page: the key is present and null rather than absent, so a caller has one thing to test.
        assertTrue(body.has("next_cursor"));
        assertTrue(body.get("next_cursor").isNull());
    }

    /**
     * The page is clamped to the MCP cap, not to the REST one. These rows carry a title and a basis sentence
     * each, so an unclamped {@code limit: 500} is a context window spent on a list.
     */
    @Test
    void listCases_clampsAnOversizedLimitToTheSurfaceCap() throws Exception {
        when(cases.page(eq(PROJECT_ID), any(), any(), any(), anyInt(), any()))
                .thenReturn(new CasesPage(List.of(), null));

        callTool("list_cases", "{\"limit\":500}");

        verify(cases).page(PROJECT_ID, "open", null, null, 100, null);
    }

    /**
     * A state nobody has is an error rather than an empty page. {@code state: "closed"} matches no row, so a
     * page would come back clean and empty — and an empty page of cases reads as "nothing is wrong with this
     * project". A wrong answer that looks like good news is worth spending the caller a turn on.
     */
    @Test
    void listCases_refusesAStateThatIsNotAStateInsteadOfReturningNothing() throws Exception {
        String text = errorText(callTool("list_cases", "{\"state\":\"closed\"}"));

        assertTrue(text.contains("closed"), text);
        assertTrue(text.contains("resolved"), "the error must name the states that do exist: " + text);
    }

    /**
     * <b>The coverage block is gone from the page and present on the project read.</b> Flattened into pages,
     * a per-page copy of "3 classifiers, 7 call sites, 1200 traces yesterday" would read as a measurement of
     * the page; dropped outright, an agent would report an all-clear for a project that stopped sending
     * traffic a week ago. Both halves are asserted together because either alone passes while the signal is
     * lost (watch-out 1 of the MCP v2 plan).
     */
    @Test
    void theWatchingCoverageBlockMovedFromTheCasePageToGetProject() throws Exception {
        when(cases.page(eq(PROJECT_ID), any(), any(), any(), anyInt(), any()))
                .thenReturn(new CasesPage(List.of(), null));
        when(cases.watching(PROJECT_ID)).thenReturn(new WatchingView(3, 7, 1200, 48_000L, 2L));

        JsonNode page = structured(callTool("list_cases", "{}"));
        assertNull(page.get("watching"), "a page of cases must not carry project-wide coverage");

        JsonNode project = structured(callTool("get_project", "{}"));

        verify(cases).watching(PROJECT_ID);
        assertEquals(3, project.get("watching").get("classifiers").asInt());
        assertEquals(7, project.get("watching").get("call_sites").asInt());
        // The one number that separates the two silences a reader must never confuse.
        assertEquals(1200, project.get("watching").get("traces_last_day").asInt());
        // get_project goes through the 1-arg overload, which always counts: an agent asking what a
        // project looks like gets the same block whether or not the case queue happens to be empty.
        assertEquals(48_000, project.get("watching").get("traces_total").asInt());
        assertEquals(2, project.get("watching").get("open_findings").asInt());
    }

    @Test
    void getCase_passesTheIdThroughUntouchedSoAHumanReferenceResolves() throws Exception {
        // The service accepts the stored id OR the display reference; the tool must not "normalise" either,
        // or a case number quoted by a person stops resolving.
        when(cases.detail(eq(PROJECT_ID), eq("C-118"))).thenReturn(detail("rca-4", null));

        JsonNode body = structured(callTool("get_case", "{\"id\":\"C-118\"}"));

        verify(cases).detail(PROJECT_ID, "C-118");
        assertEquals("C-118", body.get("case").get("reference").asText());
        assertEquals("find-9", body.get("finding_id").asText());
    }

    /**
     * <b>The finished RCA report arrives inline, whole.</b> The report IS the answer to "why is this case
     * open", and reaching it used to mean a second, capability-gated tool call — which is how a written
     * investigation goes unread. The assertions reach the deep fields (the agent's markdown, a hypothesis, a
     * ruled-out check) on purpose: an {@code rca} object carrying only the summary columns would satisfy a
     * shallower test and still lose the investigation.
     */
    @Test
    void getCase_inlinesTheFinishedRcaReportInFull() throws Exception {
        when(cases.detail(eq(PROJECT_ID), any())).thenReturn(detail("rca-4", report()));

        JsonNode body = structured(callTool("get_case", "{\"id\":\"case-118\"}"));

        // The id stays beside the report, for provenance and for the poll.
        assertEquals("rca-4", body.get("rca_report_id").asText());
        JsonNode rca = body.get("rca");
        assertEquals("model_change", rca.get("verdict").asText());
        assertEquals(
                "## Why\nThe judge model changed.", rca.get("detailed_report").asText());
        assertEquals(
                "Model swap on 2026-08-14",
                rca.get("hypotheses").get(0).get("title").asText());
        assertEquals("traffic_mix", rca.get("ruled_out").get(0).get("check").asText());
        assertTrue(rca.get("ruled_out").get(0).get("passed").asBoolean());
    }

    /**
     * A report still running is named and not rendered: {@code rca_report_id} is there to poll, {@code rca} is
     * null. Rendering the shell would show an object whose every interesting field is null, which reads as
     * "the analysis concluded nothing" rather than "the analysis has not finished".
     */
    @Test
    void getCase_leavesRcaNullWhileTheReportIsStillRunning() throws Exception {
        when(cases.detail(eq(PROJECT_ID), any())).thenReturn(detail("rca-5", null));

        JsonNode body = structured(callTool("get_case", "{\"id\":\"case-118\"}"));

        assertEquals("rca-5", body.get("rca_report_id").asText());
        assertTrue(body.get("rca").isNull(), "a pending report must not render as a report");
        assertTrue(body.get("rca_available").asBoolean(), "rca_available is about whether one CAN be run");
    }

    @Test
    void getCase_missingIdIsACleanToolError() throws Exception {
        String text = errorText(callTool("get_case", "{}"));
        assertTrue(text.contains("id"), text);
    }

    @Test
    void unknownCaseIsACleanToolErrorNotAn32603() throws Exception {
        when(cases.detail(eq(PROJECT_ID), any())).thenThrow(new EvalsException(CaseError.NOT_FOUND, "nope"));

        JsonRpc.Response r = dispatcher.dispatch(
                req(1, "tools/call", mapper.readTree("{\"name\":\"get_case\",\"arguments\":{\"id\":\"nope\"}}")),
                ctx());

        assertNotNull(r);
        assertNull(Objects.requireNonNull(r).error(), "must be a tool error inside result, not a JSON-RPC error");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Objects.requireNonNull(r.result());
        assertEquals(Boolean.TRUE, result.get("isError"));
    }

    // ------------------------------------------------------------------ helpers

    private static CaseDetailView detail(String rcaReportId, @Nullable RcaReportView rca) {
        return new CaseDetailView(
                sampleCase("case-118", "C-118", "open"),
                List.of(),
                "find-9",
                null,
                List.of(),
                rcaReportId,
                rca,
                // No measured shift on this fixture: these tools are about the RCA payload, and a
                // detector whose shift has no drawable shape sends both as null anyway.
                null,
                null,
                true,
                true,
                true);
    }

    private static RcaReportView report() {
        return new RcaReportView(
                "rca-4",
                "job-4",
                "behavior_profile",
                "profile-1",
                "Checkout summariser",
                "cs-1",
                "pass_rate",
                "2026-08-01T00:00:00Z",
                "2026-08-08T00:00:00Z",
                "2026-08-15T00:00:00Z",
                0.55,
                0.95,
                -0.4,
                "done",
                "model_change",
                "The judge model changed mid-window.",
                List.of(RuledOutCheck.assessed(
                        "traffic_mix", RuledOutCheck.Assessment.RULED_OUT, "Mix held flat.", "chi2 = 0.4")),
                List.of(new Hypothesis(
                        "Model swap on 2026-08-14", "high", "The provider rotated the default.", List.of("tr-1"))),
                "## Why\nThe judge model changed.",
                "agentic",
                "2026-08-15T01:00:00Z",
                "2026-08-15T01:20:00Z");
    }

    private static CaseView sampleCase(String id, String reference, String state) {
        return new CaseView(
                id,
                reference,
                "cost_drift",
                "call_site",
                "cs-1",
                "Checkout summariser",
                "cs-1",
                "cost",
                state,
                "Cost per trace up 40%",
                "p50 cost 0.004 -> 0.0056 over 7d",
                0.62,
                "2026-08-10T00:00:00Z",
                0.0056,
                0.004,
                0.0016,
                "2026-08-11T00:00:00Z",
                "2026-08-16T00:00:00Z",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                // cause + rca_verdict: this fixture is a case nothing has analysed, which is what
                // almost every case in the queue is.
                null,
                null);
    }

    private List<String> toolNames() {
        JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/list", null), ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>)
                Objects.requireNonNull(Objects.requireNonNull(r).result());
        JsonNode tools = mapper.valueToTree(Objects.requireNonNull(result.get("tools")));
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode t : tools) names.add(t.get("name").asText());
        return names;
    }

    private TenantContext ctx() {
        return new TenantContext("user-1", null, "org-1", PROJECT_ID, "member", "tok-1");
    }

    private JsonRpc.Request req(int id, String method, @Nullable JsonNode params) {
        return new JsonRpc.Request("2.0", IntNode.valueOf(id), method, params);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(String name, String argsJson) throws Exception {
        JsonNode params = mapper.readTree("{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}");
        JsonRpc.Response r = dispatcher.dispatch(req(1, "tools/call", params), ctx());
        assertNotNull(r);
        assertNull(Objects.requireNonNull(r).error(), "expected a tool result, not a JSON-RPC error");
        return (Map<String, Object>) Objects.requireNonNull(r.result());
    }

    private JsonNode structured(Map<String, Object> result) {
        assertEquals(Boolean.FALSE, result.get("isError"));
        return mapper.valueToTree(Objects.requireNonNull(result.get("structuredContent")));
    }

    private static String errorText(Map<String, Object> result) {
        assertEquals(Boolean.TRUE, result.get("isError"), "expected isError=true");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) Objects.requireNonNull(result.get("content"));
        return Objects.requireNonNull(content.get(0).get("text")).toString();
    }
}
