// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static ai.tessary.mcp.McpToolHarness.MAPPER;
import static ai.tessary.mcp.McpToolHarness.ORG_ID;
import static ai.tessary.mcp.McpToolHarness.PROJECT_ID;
import static ai.tessary.mcp.McpToolHarness.errorText;
import static ai.tessary.mcp.McpToolHarness.registryWith;
import static ai.tessary.mcp.McpToolHarness.req;
import static ai.tessary.mcp.McpToolHarness.structured;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.model.Pipeline;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.promptlib.PromptBody;
import ai.tessary.promptlib.PromptLabelRow;
import ai.tessary.promptlib.PromptListRow;
import ai.tessary.promptlib.PromptRow;
import ai.tessary.promptlib.PromptService;
import ai.tessary.promptlib.PromptService.CreateVia;
import ai.tessary.promptlib.PromptService.PromptListQuery;
import ai.tessary.promptlib.PromptService.ResolvedVersion;
import ai.tessary.promptlib.PromptType;
import ai.tessary.promptlib.PromptVersionRow;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The {@code list_prompts} and {@code get_prompt} MCP tools over a mocked {@link PromptService}, the seam the Prompts
 * UI routes also use. Pins the argument mapping, the snake_case UI wire shape, the per-call capability check, and that
 * every refusal is a tool error the agent can read rather than a JSON-RPC internal error.
 */
class McpPromptToolsTest {

    private static final Instant AT = Instant.parse("2026-10-01T12:00:00Z");

    private PromptService prompts;
    private CapabilityService capabilities;
    private McpToolHarness mcp;

    @BeforeEach
    void setup() {
        prompts = mock(PromptService.class);
        capabilities = mock(CapabilityService.class);
        when(prompts.authors(List.of("user-1"))).thenReturn(new PromptService.Authors(Map.of("user-1", "Ana Lima")));
        mcp = registryWith()
                .pipeline(Pipeline.empty())
                .prompts(prompts)
                .capabilities(capabilities)
                .build();
    }

    /** Bug: the folder or label filter is dropped, or the page is not the MCP one (page 1, 100 rows, newest change). */
    @Test
    void listPromptsPassesFolderAndLabel() throws Exception {
        when(prompts.list(eq(PROJECT_ID), any())).thenReturn(page());

        mcp.callTool("list_prompts", "{\"folder\":\"team\",\"label\":\"production\"}");

        ArgumentCaptor<PromptListQuery> q = ArgumentCaptor.forClass(PromptListQuery.class);
        verify(prompts).list(eq(PROJECT_ID), q.capture());
        assertEquals("team", q.getValue().folder());
        assertEquals("production", q.getValue().label());
        assertEquals(100, q.getValue().limit());
        assertEquals(1, q.getValue().page());
        assertEquals(PromptListQuery.Order.UPDATED, q.getValue().order());
    }

    /** Bug: the tool returns the service record (camelCase, nested rows) instead of the UI's snake_case page. */
    @Test
    void listPromptsReturnsTheDto() throws Exception {
        when(prompts.list(eq(PROJECT_ID), any())).thenReturn(page());

        JsonNode out = structured(mcp.callTool("list_prompts", "{}"));

        JsonNode item = out.get("items").get(0);
        assertEquals(2, item.get("latest_version").asInt());
        assertEquals(2, item.get("version_count").asInt());
        assertEquals("team/a", item.get("name").asText());
        assertEquals("production", item.get("labels").get(0).get("label").asText());
        assertEquals(1, out.get("total").asLong());
    }

    /**
     * Bug: with neither version nor label, the tool picks a version itself instead of letting the service read
     * production, or names the author by principal id alone.
     */
    @Test
    void getPromptDefaultsToProduction() throws Exception {
        when(prompts.resolve(PROJECT_ID, "p", null, null))
                .thenReturn(resolved(new PromptBody.Text("Hello {{name}}"), PromptType.TEXT, 2, "production"));

        JsonNode out = structured(mcp.callTool("get_prompt", "{\"name\":\"p\"}"));

        verify(prompts).resolve(PROJECT_ID, "p", null, null);
        assertEquals("production", out.get("labels").get(0).asText());
        assertEquals("Hello {{name}}", out.get("body").asText());
        assertEquals("commit one", out.get("commit_message").asText());
        assertEquals("Ana Lima", out.get("created_by_label").asText());
    }

    /** Bug: the version argument is dropped, or a chat body reaches the agent as a quoted JSON string. */
    @Test
    void getPromptByVersion() throws Exception {
        PromptBody chat = new PromptBody.Chat(List.of(new PromptBody.Message("system", "Be brief.")));
        when(prompts.resolve(PROJECT_ID, "p", 3, null)).thenReturn(resolved(chat, PromptType.CHAT, 3));

        JsonNode out = structured(mcp.callTool("get_prompt", "{\"name\":\"p\",\"version\":3}"));

        verify(prompts).resolve(PROJECT_ID, "p", 3, null);
        assertTrue(out.get("body").isArray(), "body must be an array: " + out.get("body"));
        assertEquals("system", out.get("body").get(0).get("role").asText());
        assertEquals(3, out.get("version").asInt());
    }

    /** Bug: both arguments reach the service, which then answers with one of them silently ignored or a 400 code. */
    @Test
    void getPromptRejectsVersionAndLabel() throws Exception {
        String text = errorText(mcp.callTool("get_prompt", "{\"name\":\"p\",\"version\":3,\"label\":\"staging\"}"));

        assertEquals("pass version or label, not both", text);
        verifyNoInteractions(prompts);
    }

    /** Bug: a call with no name reaches the service with a null name. */
    @Test
    void getPromptRequiresAName() throws Exception {
        String text = errorText(mcp.callTool("get_prompt", "{\"label\":\"staging\"}"));

        assertEquals("missing required argument: name", text);
        verifyNoInteractions(prompts);
    }

    /** Bug: a missing prompt or label surfaces as a JSON-RPC internal error instead of a message the agent can act on. */
    @Test
    void getPromptMissingIsAToolError() throws Exception {
        when(prompts.resolve(PROJECT_ID, "p", null, null)).thenThrow(new TessaryException(PromptsError.NOT_FOUND, "p"));
        when(prompts.resolve(PROJECT_ID, "p", null, "production"))
                .thenThrow(new TessaryException(PromptsError.LABEL_NOT_FOUND, "p", "production"));

        assertEquals("Prompt p not found", errorText(mcp.callTool("get_prompt", "{\"name\":\"p\"}")));
        assertEquals(
                "Prompt p has no label production",
                errorText(mcp.callTool("get_prompt", "{\"name\":\"p\",\"label\":\"production\"}")));
    }

    /**
     * Bug: an org that turned prompt management off can still read prompt bodies over MCP, or the tools vanish from
     * the listing so a client cannot tell why its call fails.
     */
    @Test
    void capabilityOffIsAToolError() throws Exception {
        doThrow(new TessaryException(CapabilityError.DISABLED, Capability.PROMPT_MANAGEMENT.wire()))
                .when(capabilities)
                .require(ORG_ID, Capability.PROMPT_MANAGEMENT);

        String list = errorText(mcp.callTool("list_prompts", "{}"));
        String get = errorText(mcp.callTool("get_prompt", "{\"name\":\"p\"}"));

        String expected = "The 'prompt_management_enabled' capability is not available for this organization";
        assertEquals(expected, list);
        assertEquals(expected, get);
        verifyNoInteractions(prompts);
        assertTrue(mcp.listToolNames().containsAll(List.of("list_prompts", "get_prompt")));
    }

    /** Bug: a key context with no org reaches the capability check with a null org and fails as an internal error. */
    @Test
    void nullOrgIsAToolError() throws Exception {
        TenantContext noOrg = new TenantContext("user-1", null, null, PROJECT_ID, "member", "tok-1");
        JsonNode params = MAPPER.readTree("{\"name\":\"list_prompts\",\"arguments\":{}}");

        JsonRpc.Response r = mcp.dispatcher().dispatch(req(1, "tools/call", params), noOrg);

        assertNotNull(r);
        assertNull(Objects.requireNonNull(r).error(), "expected a tool result, not a JSON-RPC error");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Objects.requireNonNull(r.result());
        assertEquals("no organization bound to this token", errorText(result));
        verifyNoInteractions(prompts, capabilities);
    }

    private static PromptRow prompt(PromptType type) {
        return new PromptRow("pr-1", PROJECT_ID, "team/a", type, List.of("billing"), 1, "user-1", AT, AT);
    }

    private static PromptService.PromptPage page() {
        PromptListRow row = new PromptListRow(prompt(PromptType.TEXT), List.of(1, 2), "{}");
        PromptLabelRow label = new PromptLabelRow(PROJECT_ID, "pr-1", "production", "v-2", 2, "user-1", AT);
        return new PromptService.PromptPage(
                List.of(row), Map.of("pr-1", List.of(label)), Map.of("pr-1", Map.of()), 1, 100, 1);
    }

    private static ResolvedVersion resolved(PromptBody body, PromptType type, int version, String... labels) {
        PromptVersionRow row = new PromptVersionRow(
                "v-" + version, PROJECT_ID, "pr-1", version, "{}", "{}", "commit one", "user-1", CreateVia.UI, AT);
        return new ResolvedVersion(prompt(type), row, body, Map.of(), List.of(labels), false);
    }
}
