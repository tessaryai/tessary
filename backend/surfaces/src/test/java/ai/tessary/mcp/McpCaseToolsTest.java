// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static ai.tessary.mcp.McpToolHarness.PROJECT_ID;
import static ai.tessary.mcp.McpToolHarness.errorText;
import static ai.tessary.mcp.McpToolHarness.registryWith;
import static ai.tessary.mcp.McpToolHarness.structured;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.cases.CaseDtos.CaseDetailView;
import ai.tessary.cases.CaseDtos.CaseExemplarView;
import ai.tessary.cases.CaseDtos.CaseView;
import ai.tessary.cases.CaseDtos.CasesPage;
import ai.tessary.cases.CaseDtos.WatchingView;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.secretleak.SecretLeakEvidence;
import ai.tessary.classifier.toolerror.ToolErrorEvidence;
import ai.tessary.model.Pipeline;
import ai.tessary.rca.RcaDtos.Cause;
import ai.tessary.rca.RcaDtos.RcaReportView;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code list_cases} and {@code get_case} MCP tools over a mocked {@link CaseService}, the seam {@code
 * CaseController} also uses. Pins the wrapper: project scoping, arg mapping, page clamping, verbatim rendering, and
 * error mapping.
 *
 * <p>The {@code watching} block is asserted here on {@code get_project} too: it is the only signal separating
 * "nothing is wrong" from "nothing is arriving" (launch requirement E5), so the test that the page dropped it sits
 * beside the test that the project read picked it up.
 */
class McpCaseToolsTest {

    private CaseService cases;
    private McpToolHarness mcp;

    @BeforeEach
    void setup() {
        this.cases = mock(CaseService.class);
        this.mcp = registryWith().pipeline(Pipeline.empty()).cases(cases).build();
    }

    /** Filters and the cursor reach the service under the service's own names, untranslated. */
    @Test
    void listCases_passesStateDetectorCallSiteAndCursorThrough() throws Exception {
        when(cases.page(eq(PROJECT_ID), any(), any(), any(), anyInt(), any()))
                .thenReturn(new CasesPage(List.of(), null));

        JsonNode body = structured(mcp.callTool(
                "list_cases",
                "{\"state\":\"resolved\",\"detector\":\"tool_error\",\"call_site_id\":\"cs-7\","
                        + "\"limit\":10,\"cursor\":\"tok\"}"));

        verify(cases).page(PROJECT_ID, "resolved", "tool_error", "cs-7", 10, "tok");
        // Last page: the key is present and null, not absent.
        assertTrue(body.has("next_cursor"));
        assertTrue(body.get("next_cursor").isNull());
    }

    /** Clamped to the MCP cap, not the REST one: an unclamped {@code limit: 500} spends a context window on a list. */
    @Test
    void listCases_clampsAnOversizedLimitToTheSurfaceCap() throws Exception {
        when(cases.page(eq(PROJECT_ID), any(), any(), any(), anyInt(), any()))
                .thenReturn(new CasesPage(List.of(), null));

        mcp.callTool("list_cases", "{\"limit\":500}");

        verify(cases).page(PROJECT_ID, "open", null, null, 100, null);
    }

    /** An unknown state is an error, not an empty page, which would read as "nothing is wrong". */
    @Test
    void listCases_refusesAStateThatIsNotAStateInsteadOfReturningNothing() throws Exception {
        String text = errorText(mcp.callTool("list_cases", "{\"state\":\"closed\"}"));

        assertTrue(text.contains("closed"), text);
        assertTrue(text.contains("resolved"), "the error must name the states that do exist: " + text);
    }

    /**
     * Coverage leaves the page and appears on the project read. Per page it would read as a measurement of the page;
     * dropped, an agent reports an all-clear for a project that stopped sending traffic. Either half alone passes
     * while the signal is lost.
     */
    @Test
    void theWatchingCoverageBlockMovedFromTheCasePageToGetProject() throws Exception {
        when(cases.page(eq(PROJECT_ID), any(), any(), any(), anyInt(), any()))
                .thenReturn(new CasesPage(List.of(), null));
        when(cases.watching(PROJECT_ID)).thenReturn(new WatchingView(3, 7, 1200, 48_000L, 2L));

        JsonNode page = structured(mcp.callTool("list_cases", "{}"));
        assertNull(page.get("watching"), "a page of cases must not carry project-wide coverage");

        JsonNode project = structured(mcp.callTool("get_project", "{}"));

        verify(cases).watching(PROJECT_ID);
        assertEquals(3, project.get("watching").get("classifiers").asInt());
        assertEquals(7, project.get("watching").get("call_sites").asInt());
        assertEquals(1200, project.get("watching").get("traces_last_day").asInt());
        // The 1-arg overload always counts, so the block does not depend on the case queue being empty.
        assertEquals(48_000, project.get("watching").get("traces_total").asInt());
        assertEquals(2, project.get("watching").get("open_findings").asInt());
    }

    @Test
    void getCase_passesTheIdThroughUntouchedSoAHumanReferenceResolves() throws Exception {
        // The stored id or the display reference: the tool must not normalise either.
        when(cases.detail(eq(PROJECT_ID), eq("C-118"))).thenReturn(detail("rca-4", null));

        JsonNode body = structured(mcp.callTool("get_case", "{\"id\":\"C-118\"}"));

        verify(cases).detail(PROJECT_ID, "C-118");
        assertEquals("C-118", body.get("case").get("reference").asText());
        assertEquals("find-9", body.get("latest_finding_id").asText());
    }

    /**
     * The finished RCA report arrives inline and whole: a second, gated call is how a written investigation goes
     * unread. The assertions reach deep fields on purpose; summary columns alone would still lose the investigation.
     */
    @Test
    void getCase_inlinesTheFinishedRcaReportInFull() throws Exception {
        when(cases.detail(eq(PROJECT_ID), any())).thenReturn(detail("rca-4", report()));

        JsonNode body = structured(mcp.callTool("get_case", "{\"id\":\"case-118\"}"));

        assertEquals("rca-4", body.get("rca_report_id").asText());
        JsonNode rca = body.get("rca");
        assertEquals("model_change", rca.get("verdict").asText());
        assertEquals(
                "## Why\nThe judge model changed.", rca.get("detailed_report").asText());
        assertEquals(
                "Model swap on 2026-08-14",
                rca.get("causes").get(0).get("title").asText());
        assertEquals(
                "The provider rotated the default.",
                rca.get("causes").get(0).get("what_changed").asText());
        assertFalse(rca.has("hypotheses"), "every case type answers in causes now");
        assertEquals(
                "Did the traffic mix change?",
                rca.get("ruled_out").get(0).get("question").asText());
        assertEquals("traffic_mix", rca.get("ruled_out").get(0).get("check").asText());
        assertTrue(rca.get("ruled_out").get(0).get("passed").asBoolean());
    }

    /** A running report is named, not rendered: an all-null shell reads as "concluded nothing", not "not finished". */
    @Test
    void getCase_leavesRcaNullWhileTheReportIsStillRunning() throws Exception {
        when(cases.detail(eq(PROJECT_ID), any())).thenReturn(detail("rca-5", null));

        JsonNode body = structured(mcp.callTool("get_case", "{\"id\":\"case-118\"}"));

        assertEquals("rca-5", body.get("rca_report_id").asText());
        assertTrue(body.get("rca").isNull(), "a pending report must not render as a report");
        assertTrue(body.get("rca_available").asBoolean(), "rca_available is about whether one CAN be run");
    }

    /**
     * {@code get_case} strips ids as {@code get_finding} does, or an agent reads the withheld sample one tool over.
     */
    @Test
    void getCase_toolError_keepsTheNumbersAndDropsFailingTracesAndExemplars() throws Exception {
        when(cases.detail(eq(PROJECT_ID), any())).thenReturn(detail(toolErrorRate(), null, exemplar()));

        JsonNode body = structured(mcp.callTool("get_case", "{\"id\":\"case-118\"}"));

        JsonNode toolError = body.get("tool_error");
        assertEquals(5_000, toolError.get("nCur").asLong());
        assertEquals(400, toolError.get("failuresCur").asLong());
        assertEquals(0, toolError.get("failingTraces").size(), "the agent view carries no trace ids");
        assertEquals(0, body.get("exemplars").size(), "exemplars are paged through get_finding_evidence");
        assertEquals("find-9", body.get("latest_finding_id").asText());
        assertFalse(body.toString().contains("trace-failing-1"), "a failing trace id reached the agent");
        assertFalse(body.toString().contains("trace-exemplar-1"), "an exemplar trace id reached the agent");
    }

    @Test
    void getCase_secretLeak_keepsTheMaskedKeyAndDropsLeakIdsAndExemplars() throws Exception {
        when(cases.detail(eq(PROJECT_ID), any())).thenReturn(detail(null, secretLeak(), exemplar()));

        JsonNode body = structured(mcp.callTool("get_case", "{\"id\":\"case-118\"}"));

        JsonNode secretLeak = body.get("secret_leak");
        assertEquals(3, secretLeak.get("leakCount").asLong());
        assertEquals(2, secretLeak.get("traceCount").asLong());
        JsonNode leak = secretLeak.get("leaks").get(0);
        assertTrue(leak.get("traceId").isNull(), "the agent view carries no trace id");
        assertTrue(leak.get("spanId").isNull(), "the agent view carries no span id");
        assertEquals("AKIA…WXYZ", leak.get("masked").asText());
        assertEquals(0, body.get("exemplars").size(), "exemplars are paged through get_finding_evidence");
        assertFalse(body.toString().contains("trace-secret-9"), "a witness trace id reached the agent");
        assertFalse(body.toString().contains("trace-exemplar-1"), "an exemplar trace id reached the agent");
    }

    private static CaseDetailView detail(String rcaReportId, @Nullable RcaReportView rca) {
        return detail(rcaReportId, rca, null, null, List.of());
    }

    private static CaseDetailView detail(
            ToolErrorEvidence.@Nullable RateDetail toolError,
            SecretLeakEvidence.@Nullable SecretLeakDetail secretLeak,
            CaseExemplarView exemplar) {
        return detail(null, null, toolError, secretLeak, List.of(exemplar));
    }

    private static CaseDetailView detail(
            @Nullable String rcaReportId,
            @Nullable RcaReportView rca,
            ToolErrorEvidence.@Nullable RateDetail toolError,
            SecretLeakEvidence.@Nullable SecretLeakDetail secretLeak,
            List<CaseExemplarView> exemplars) {
        return new CaseDetailView(
                sampleCase("case-118", "C-118", "open"),
                List.of(),
                "find-9",
                null,
                exemplars,
                rcaReportId,
                rca,
                null,
                toolError,
                null,
                secretLeak,
                null,
                null,
                true,
                true,
                true);
    }

    private static CaseExemplarView exemplar() {
        return new CaseExemplarView(
                "trace-exemplar-1", "exemplar", 1, "search_orders", "cs-1", 120L, 0.002, "2026-08-12T00:00:00Z");
    }

    private static ToolErrorEvidence.RateDetail toolErrorRate() {
        return new ToolErrorEvidence.RateDetail(
                "search_orders",
                0.01,
                0.08,
                7.0,
                20_000,
                5_000,
                400,
                List.of(),
                false,
                List.of("trace-failing-1", "trace-failing-2"),
                "2026-08-12T00:00:00Z",
                null,
                null,
                "up",
                9.0,
                5.0,
                0.3,
                0.8);
    }

    private static SecretLeakEvidence.SecretLeakDetail secretLeak() {
        return new SecretLeakEvidence.SecretLeakDetail(
                "aws-access-token",
                FindingRow.Confidence.HIGH,
                3L,
                2L,
                "2026-08-12T00:00:00Z",
                "2026-08-13T00:00:00Z",
                List.of(new SecretLeakEvidence.SecretLeakKeyView("AKIA…WXYZ", 3L, 2L, "2026-08-13T00:00:00Z", false)),
                List.of(new SecretLeakEvidence.SecretLeakLeakView(
                        "2026-08-13T00:00:00Z", "AKIA…WXYZ", "masked", "trace-secret-9", "span-1")),
                "event_count",
                1L,
                86_400L,
                "2026-08-12T00:00:00Z",
                "2026-08-13T00:00:00Z");
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
                "metric_movement",
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
                        "traffic_mix",
                        "Did the traffic mix change?",
                        RuledOutCheck.Assessment.RULED_OUT,
                        "Mix held flat.",
                        "chi2 = 0.4")),
                List.of(new Cause(
                        "Model swap on 2026-08-14",
                        "high",
                        "The provider rotated the default.",
                        null,
                        null,
                        null,
                        List.of("tr-1"),
                        List.of(),
                        0)),
                "## Why\nThe judge model changed.",
                "agentic",
                true,
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
                1L,
                "fnd-1",
                null,
                // cause + rca_verdict: an unanalysed case, like most in the queue.
                null,
                null);
    }
}
