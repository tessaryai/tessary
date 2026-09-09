// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.auth.AuthFilter;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Exercises the MCP transport end-to-end through MockMvc. Auth is enabled
 * (WORKOS_* configured) so AuthFilter validates Bearer tokens against the
 * api_key table. The test bootstraps an org/project/user + issues a real
 * token via {@link ApiKeyService}, then uses that token on every request.
 */
@SpringBootTest
class McpControllerTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        Path yaml = tmp.resolve("evals.yaml");
        Files.writeString(yaml, """
            version: "0.0.1"
            product_hint: "test"
            call_sites:
              - id: cs_test
                use_case: test_case
                provider: openai
                model: gpt-4
                shape: extract
                shape_confidence: high
                intent: extracts a value
                constraints: []
                sample_count: 1
            graders: []
            failure_modes: []
            chains: []
            taxonomy: []
            """);
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        // Enable auth so MCP bearer-token verification runs. Say so directly rather than
        // configuring a fake external-provider key as an indirect toggle -- see
        // TestAuthDisabledInitializer's javadoc for why (#852/#996).
        r.add("tessary.auth.disabled", () -> "false");
    }

    @Autowired
    WebApplicationContext wac;

    @Autowired
    TenantService tenants;

    @Autowired
    ApiKeyService mcpTokens;

    @Autowired
    AuthFilter authFilter;

    final ObjectMapper mapper = new ObjectMapper();
    MockMvc mvc;
    String bearer;
    Project project;
    Principal user;

    @BeforeEach
    void setup() {
        // Boot 4 MockMvc doesn't auto-register OncePerRequestFilter beans;
        // wire AuthFilter explicitly so /mcp goes through the same auth path
        // it would in production.
        this.mvc =
                MockMvcBuilders.webAppContextSetup(wac).addFilters(authFilter).build();
        var fix = TenantFixture.bootstrap(tenants, "mcp");
        this.user = fix.user();
        this.project = fix.project();
        this.bearer = mcpTokens.issue(project.id(), user.id(), "test-token").plaintext();
    }

    @Test
    void rejectsMissingAuth() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongToken() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer tsy_w_doesnotexist123")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void initializeReturnsServerInfo() throws Exception {
        JsonNode body = call("""
            {"jsonrpc":"2.0","id":1,"method":"initialize",
             "params":{"protocolVersion":"2025-06-18","capabilities":{}}}
            """);
        assertEquals("2.0", body.get("jsonrpc").asText());
        assertEquals(1, body.get("id").asInt());
        JsonNode result = body.get("result");
        assertEquals("tessary-mcp", result.get("serverInfo").get("name").asText());
        assertNotNull(result.get("capabilities").get("tools"));
    }

    /**
     * The six tools the read-only cutover deleted — five triage/RCA tools plus the last write. Named here
     * so the transport test fails if one comes back, the same invariant
     * {@code McpCapabilityGateTest} pins on the registry, asserted end-to-end over the real
     * catalogue a client actually receives.
     */
    private static final Set<String> REMOVED_TOOLS = Set.of(
            "propose_grader_edit", "run_triage", "get_triage", "latest_triage", "list_rca_reports", "get_rca_report");

    /**
     * Names that would mean a write. Matched as a prefix, because the risk is not one tool returning — it is
     * the next write being added as one more registration.
     */
    private static final List<String> WRITE_PREFIXES = List.of("propose_", "run_", "create_", "update_", "delete_");

    @Test
    void toolsListContainsCoreToolsAndNoWriteTool() throws Exception {
        JsonNode body = call("""
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}
            """);
        JsonNode tools = body.get("result").get("tools");
        assertTrue(tools.isArray() && tools.size() >= 5, "expected several tools, got " + tools);
        boolean hasListCallSites = false, hasGetProject = false;
        for (JsonNode t : tools) {
            String name = t.get("name").asText();
            if ("list_call_sites".equals(name)) hasListCallSites = true;
            if ("get_project".equals(name)) hasGetProject = true;
            assertFalse(REMOVED_TOOLS.contains(name), "removed tool advertised again: " + name);
            for (String prefix : WRITE_PREFIXES) {
                assertFalse(name.startsWith(prefix), "write-shaped tool advertised: " + name);
            }
            assertNotNull(t.get("description"));
            assertNotNull(t.get("inputSchema"));
        }
        assertTrue(hasListCallSites, "missing list_call_sites");
        assertTrue(hasGetProject, "missing get_project");
    }

    @Test
    void toolsListContainsQueryTools() throws Exception {
        JsonNode body = call("""
            {"jsonrpc":"2.0","id":21,"method":"tools/list"}
            """);
        JsonNode tools = body.get("result").get("tools");
        boolean hasCount = false, hasTimeseries = false, hasFacets = false, hasSearch = false;
        for (JsonNode t : tools) {
            String name = t.get("name").asText();
            if ("query_count".equals(name)) hasCount = true;
            if ("query_timeseries".equals(name)) hasTimeseries = true;
            if ("query_facets".equals(name)) hasFacets = true;
            if ("query_search".equals(name)) hasSearch = true;
        }
        assertTrue(hasCount && hasTimeseries && hasFacets && hasSearch, "missing one of the query_* tools: " + tools);
    }

    /**
     * The triage question — "what is wrong with this project" — is answered by the case pair now. The
     * {@code run_triage} / {@code get_triage} / {@code latest_triage} trio it replaced spent money and
     * returned a diagnosis nothing else could see; a case carries its RCA report inline instead.
     */
    @Test
    void toolsListContainsCaseTools() throws Exception {
        JsonNode body = call("""
            {"jsonrpc":"2.0","id":24,"method":"tools/list"}
            """);
        JsonNode tools = body.get("result").get("tools");
        boolean hasList = false, hasGet = false;
        for (JsonNode t : tools) {
            String name = t.get("name").asText();
            if ("list_cases".equals(name)) hasList = true;
            if ("get_case".equals(name)) hasGet = true;
        }
        assertTrue(hasList && hasGet, "missing one of the case tools: " + tools);
    }

    @Test
    void toolsCallGetCaseUnknownIdIsCleanToolError() throws Exception {
        // No such case in this project -> NOT_FOUND surfaces as a tool error (isError=true), not -32603.
        JsonNode body = call("""
            {"jsonrpc":"2.0","id":25,"method":"tools/call",
             "params":{"name":"get_case","arguments":{"id":"does-not-exist"}}}
            """);
        assertNull(body.get("error"), "must be a tool error inside result, not a JSON-RPC error");
        JsonNode result = body.get("result");
        assertEquals(true, result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("does-not-exist"), "expected missing id named in: " + text);
    }

    /**
     * The evidence door end-to-end: a token minted for one project, over the real transport, asking for a
     * finding id that belongs to nobody here. Whether the id is another tenant's or fictional, the answer
     * has to be the same clean tool error — a distinguishable 403 would confirm that the id is real, which
     * is the one thing a cross-tenant probe is trying to learn.
     */
    @Test
    void toolsCallGetFindingEvidenceForeignIdIsCleanToolError() throws Exception {
        JsonNode body = call("""
            {"jsonrpc":"2.0","id":31,"method":"tools/call",
             "params":{"name":"get_finding_evidence","arguments":{"finding_id":"other-tenants-finding"}}}
            """);
        assertNull(body.get("error"), "must be a tool error inside result, not a JSON-RPC error");
        JsonNode result = body.get("result");
        assertEquals(true, result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("other-tenants-finding"), "expected the id named in: " + text);
    }

    @Test
    void toolsCallListCasesReturnsEmptyOpenPageForFreshProject() throws Exception {
        // A pure read: no classifier has opened a case on the bootstrapped project, so the open page is empty
        // and unpaged — no error, no LLM call, no write.
        JsonNode body = call("""
            {"jsonrpc":"2.0","id":26,"method":"tools/call",
             "params":{"name":"list_cases","arguments":{}}}
            """);
        JsonNode result = body.get("result");
        assertEquals(false, result.get("isError").asBoolean());
        JsonNode page = result.get("structuredContent");
        assertTrue(page.get("cases").isArray(), "expected a cases array in: " + page);
        assertEquals(0, page.get("cases").size(), "fresh project must have no open cases: " + page);
        assertTrue(page.get("next_cursor").isNull(), "empty page must not offer a cursor: " + page);
    }

    @Test
    void toolsCallQueryCountReturnsProjectScopedResult() throws Exception {
        // The bootstrapped project has no ingested rows, so count is 0 — but the call must succeed
        // end-to-end through auth -> dispatcher -> QueryService, proving the wrapper is wired and scoped.
        JsonNode body = call("""
            {"jsonrpc":"2.0","id":22,"method":"tools/call",
             "params":{"name":"query_count","arguments":{"dataset":"spans"}}}
            """);
        JsonNode result = body.get("result");
        assertEquals(false, result.get("isError").asBoolean());
        assertEquals(0, result.get("structuredContent").get("count").asLong());
    }

    @Test
    void toolsCallQueryBadDatasetIsCleanToolError() throws Exception {
        // A bad dataset must surface as a tool error (isError=true), not a -32603 internal error.
        JsonNode body = call("""
            {"jsonrpc":"2.0","id":23,"method":"tools/call",
             "params":{"name":"query_count","arguments":{"dataset":"not_a_dataset"}}}
            """);
        assertNull(body.get("error"), "must be a tool error inside result, not a JSON-RPC error");
        JsonNode result = body.get("result");
        assertEquals(true, result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("not_a_dataset"), "expected bad dataset named in: " + text);
    }

    @Test
    void toolsCallGetProjectReturnsBoundProjectFlat() throws Exception {
        JsonNode body = call("""
            {"jsonrpc":"2.0","id":3,"method":"tools/call",
             "params":{"name":"get_project","arguments":{}}}
            """);
        JsonNode result = body.get("result");
        assertEquals(false, result.get("isError").asBoolean());
        // Flat, not a one-element "pipelines" list. A token binds to exactly one project, so the old
        // list_pipelines shape made every caller index [0] into a collection that could never hold two.
        JsonNode found = result.get("structuredContent");
        assertNull(found.get("pipelines"), "get_project returns the project itself, not a wrapper list");
        assertEquals(project.id(), found.get("id").asText());
        assertEquals(project.slug(), found.get("slug").asText());
    }

    @Test
    void toolsCallPropagatesToolErrorWithoutCrashing() throws Exception {
        JsonNode body = call("""
            {"jsonrpc":"2.0","id":4,"method":"tools/call",
             "params":{"name":"get_trace",
                       "arguments":{"trace_id":"does-not-exist"}}}
            """);
        JsonNode result = body.get("result");
        assertEquals(true, result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("not found"), "expected 'not found' in: " + text);
    }

    @Test
    void notificationsReturnNoContent() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearer)
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void revokedTokenIsRejected() throws Exception {
        // Issue + revoke + ensure subsequent call gets 401.
        ApiKeyService.Issued issued = mcpTokens.issue(project.id(), user.id(), "to-revoke");
        // The token bcrypt-validates first, then we revoke and try again.
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + issued.plaintext())
                        .content("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"ping\"}"))
                .andExpect(status().isOk());
        mcpTokens.revoke(issued.token().id());
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + issued.plaintext())
                        .content("{\"jsonrpc\":\"2.0\",\"id\":100,\"method\":\"ping\"}"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode call(String json) throws Exception {
        MvcResult res = mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearer)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(res.getResponse().getContentAsString());
    }
}
