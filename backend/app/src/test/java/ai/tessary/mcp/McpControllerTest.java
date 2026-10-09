// SPDX-License-Identifier: Apache-2.0
package ai.tessary.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.auth.AuthFilter;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.AuthEnforcedContext;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Exercises the MCP transport end-to-end through MockMvc. Auth is enforced
 * so AuthFilter validates Bearer tokens against the api_key table. The test
 * bootstraps an org/project/user + issues a real token via
 * {@link ApiKeyService}, then uses that token on every request.
 */
@AuthEnforcedContext
class McpControllerTest {

    @Autowired
    WebApplicationContext wac;

    @Autowired
    TenantService tenants;

    @Autowired
    ApiKeyService mcpTokens;

    @Autowired
    AuthFilter authFilter;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    JdbcClient jdbc;

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

    /**
     * The frustration classifier keys a conversation on the session, and RCA hands the agent those session ids. A
     * thread id is only a column: keyed on it, one user's thread spanning two sessions read as one conversation and
     * a session read dropped its threaded turns.
     */
    @Test
    void toolsCallGetConversationReadsOneSessionsTurnsWhateverTheirThread() throws Exception {
        var fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
        String pid = project.id();
        Instant t0 = Instant.parse("2026-09-01T10:00:00Z");
        String monday = SubstrateV2Fixtures.sessionId();
        String thursday = SubstrateV2Fixtures.sessionId();
        String userThread = "whatsapp-" + SubstrateV2Fixtures.traceId();
        String a1 = SubstrateV2Fixtures.traceId();
        String b1 = SubstrateV2Fixtures.traceId();
        String a2 = SubstrateV2Fixtures.traceId();
        String bare = SubstrateV2Fixtures.traceId();
        String subAgent = SubstrateV2Fixtures.traceId();
        String later = SubstrateV2Fixtures.traceId();
        fx.trace(pid, a1, monday, userThread, null, t0);
        fx.trace(pid, b1, monday, "side-" + userThread, null, t0.plusSeconds(1));
        fx.trace(pid, a2, monday, userThread, null, t0.plusSeconds(2));
        fx.trace(pid, bare, monday, null, null, t0.plusSeconds(3));
        fx.trace(pid, subAgent, monday, null, null, t0.plusSeconds(4));
        fx.trace(pid, later, thursday, userThread, null, t0.plusSeconds(3 * 86_400));
        jdbc.sql("UPDATE trace SET parent_trace_id = :parent WHERE project_id = :pid AND id = :child")
                .param("parent", bare)
                .param("pid", pid)
                .param("child", subAgent)
                .update();

        JsonNode first = getConversation(monday);
        assertEquals(monday, first.get("id").asText());
        assertEquals(
                List.of(a1, b1, a2, bare),
                traceIds(first),
                "every top-level turn of the session, oldest first, never a sub-agent trace or another session's");
        assertEquals(false, first.get("traces_truncated").asBoolean());

        assertEquals(List.of(later), traceIds(getConversation(thursday)), "the same thread in a later session");

        JsonNode byThread = call(String.format(Locale.ROOT, """
            {"jsonrpc":"2.0","id":28,"method":"tools/call",
             "params":{"name":"get_conversation","arguments":{"id":"%s"}}}
            """, userThread)).get("result");
        assertEquals(true, byThread.get("isError").asBoolean(), "a thread id names no conversation");
    }

    @Test
    void toolsCallGetConversationUnknownIdIsCleanToolError() throws Exception {
        JsonNode result = call("""
            {"jsonrpc":"2.0","id":27,"method":"tools/call",
             "params":{"name":"get_conversation","arguments":{"id":"no-such-conversation"}}}
            """).get("result");
        assertEquals(true, result.get("isError").asBoolean());
        assertEquals(
                "conversation not found: no-such-conversation",
                result.get("content").get(0).get("text").asText());
    }

    private JsonNode getConversation(String id) throws Exception {
        JsonNode result = call(String.format(Locale.ROOT, """
            {"jsonrpc":"2.0","id":26,"method":"tools/call",
             "params":{"name":"get_conversation","arguments":{"id":"%s"}}}
            """, id)).get("result");
        assertEquals(false, result.get("isError").asBoolean(), result::toString);
        return result.get("structuredContent");
    }

    private static List<String> traceIds(JsonNode conversation) {
        List<String> ids = new ArrayList<>();
        conversation.get("traces").forEach(t -> ids.add(t.get("id").asText()));
        return ids;
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
