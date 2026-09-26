// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // Enable auth so MCP bearer-token verification runs. Say so directly rather than
        // configuring a fake external-provider key as an indirect toggle -- see
        // TestAuthDisabledInitializer's javadoc for why.
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
    void rejectsWrongToken() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer tsy_w_doesnotexist123")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isUnauthorized());
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
    void notificationsReturnNoContent() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearer)
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isNoContent());
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
