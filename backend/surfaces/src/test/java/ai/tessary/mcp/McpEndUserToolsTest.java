// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static ai.tessary.mcp.McpToolHarness.PROJECT_ID;
import static ai.tessary.mcp.McpToolHarness.errorText;
import static ai.tessary.mcp.McpToolHarness.registryWith;
import static ai.tessary.mcp.McpToolHarness.structured;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.Verdict;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssue;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssueList;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssueNumbers;
import ai.tessary.endusers.read.EndUserDtos.EndUserIssueRow;
import ai.tessary.endusers.read.EndUserDtos.EndUserOverview;
import ai.tessary.endusers.read.EndUserReadService;
import ai.tessary.endusers.read.IssueDiagnostics;
import ai.tessary.model.Pipeline;
import ai.tessary.open.errors.EndUserError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three Users view tools over a mocked {@link EndUserReadService}, the seam {@code EndUserController} also uses.
 * Pins project scoping, the case id passed through untouched, the service view on the wire, and error mapping.
 */
class McpEndUserToolsTest {

    private EndUserReadService endUsers;
    private McpToolHarness mcp;

    @BeforeEach
    void setup() {
        this.endUsers = mock(EndUserReadService.class);
        this.mcp = registryWith().pipeline(Pipeline.empty()).endUsers(endUsers).build();
    }

    @Test
    void getEndUsersOverview_returnsServiceView() throws Exception {
        when(endUsers.overview(PROJECT_ID)).thenReturn(overview());

        JsonNode body = structured(mcp.callTool("get_end_users_overview", "{}"));

        verify(endUsers).overview(PROJECT_ID);
        assertEquals("ok", body.get("page_state").asText());
        assertEquals(20_400, body.get("as_of").asInt());
        assertEquals(1186, body.get("counted").asInt());
        JsonNode stages = body.get("stages");
        assertEquals(1014, stages.get("active").asInt());
        assertEquals(61, stages.get("new").asInt());
        assertEquals(47, stages.get("silent").asInt());
        assertEquals(125, stages.get("stopped_named").asInt());
        assertEquals(141.0, stages.get("stopped_expected").asDouble());
        assertTrue(body.get("trust").isNull(), "null means not computed and must reach the agent as null");
    }

    @Test
    void listEndUserIssues_returnsRows() throws Exception {
        when(endUsers.issues(PROJECT_ID))
                .thenReturn(new EndUserIssueList(
                        PageState.OK,
                        20_400,
                        List.of(
                                row("case-118", "C-118", Verdict.EVIDENCE_OF_LOSS, 1, 40),
                                row("case-121", "C-121", Verdict.NOT_ENOUGH_EVIDENCE, 2, 6))));

        JsonNode body = structured(mcp.callTool("list_end_user_issues", "{}"));

        verify(endUsers).issues(PROJECT_ID);
        JsonNode issues = body.get("issues");
        assertEquals(2, issues.size());
        assertEquals("C-118", issues.get(0).get("case_ref").asText());
        assertEquals(
                "evidence_of_loss", issues.get(0).get("issue").get("verdict").asText());
        assertEquals(40, issues.get(0).get("issue").get("user_ids_hit").asInt());
        assertEquals("C-121", issues.get(1).get("case_ref").asText());
        assertEquals(
                "not_enough_evidence", issues.get(1).get("issue").get("verdict").asText());
        assertEquals(2, issues.get(1).get("issue").get("rank").asInt());
    }

    @Test
    void getEndUserIssue_passesTheCaseIdThroughUntouched() throws Exception {
        when(endUsers.issue(PROJECT_ID, "C-118")).thenReturn(issue());

        JsonNode body = structured(mcp.callTool("get_end_user_issue", "{\"case_id\":\"C-118\"}"));

        verify(endUsers).issue(PROJECT_ID, "C-118");
        assertEquals("case-118", body.get("case_id").asText());
        assertEquals(12, body.get("silent_now").asInt());
        assertEquals(9.0, body.get("issue").get("lost").asDouble());
    }

    @Test
    void getEndUserIssue_unknownCase_isToolError() throws Exception {
        when(endUsers.issue(PROJECT_ID, "C-999")).thenThrow(new TessaryException(EndUserError.NOT_FOUND));

        String text = errorText(mcp.callTool("get_end_user_issue", "{\"case_id\":\"C-999\"}"));

        assertEquals(EndUserError.NOT_FOUND.template(), text);
    }

    private static EndUserOverview overview() {
        return new EndUserOverview(
                PageState.OK,
                null,
                Map.of(),
                20_400,
                20_300,
                1186,
                Map.of(),
                new StageCounts(1014, 61, 47, 0, 125, 141.0, 128.0, 155.0, 1186),
                null,
                null,
                List.of(),
                List.of(),
                null);
    }

    private static EndUserIssueRow row(String caseId, String caseRef, Verdict verdict, int rank, int hit) {
        return new EndUserIssueRow(
                caseId,
                caseRef,
                "Tool error rate up",
                "tool_error",
                "search_orders",
                20_380,
                numbers(verdict, rank, hit));
    }

    private static EndUserIssue issue() {
        return new EndUserIssue(
                PageState.OK,
                20_400,
                "case-118",
                "C-118",
                "Tool error rate up",
                "tool_error",
                "search_orders",
                "open",
                null,
                20_380,
                12,
                numbers(Verdict.EVIDENCE_OF_LOSS, 1, 40));
    }

    private static EndUserIssueNumbers numbers(Verdict verdict, int rank, int hit) {
        return new EndUserIssueNumbers(
                verdict,
                true,
                rank,
                hit,
                35,
                9.0,
                4.0,
                14.0,
                null,
                new IssueDiagnostics(15.0, 6.0, 20.0, 28, 2, 3, 18, null, null, 38, null, null),
                List.of());
    }
}
