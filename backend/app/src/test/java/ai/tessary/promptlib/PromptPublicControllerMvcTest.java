// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.tessary.plan.Capability;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.AuthEnforcedContext;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code /v1/prompts} over HTTP with real keys, for what a direct call skips: the credential checks in front of the
 * handler, Jackson's naming of {@code isActive}, a slashed name in a query parameter, and the bare route being the list.
 */
@AuthEnforcedContext
record PromptPublicControllerMvcTest(
        MockMvc mvc, TenantService tenants, ApiKeyService keys, CapabilityFixture capabilities) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Caller(TenantFixture.Setup fix, String bearer) {}

    private Caller admin(String name) {
        var fix = TenantFixture.bootstrap(tenants, name);
        return new Caller(
                fix,
                keys.issue(fix.project().id(), fix.user().id(), name, KeyScope.ADMIN)
                        .plaintext());
    }

    private MockHttpServletResponse send(Caller who, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + who.bearer()))
                .andReturn()
                .getResponse();
    }

    private void create(Caller who, String json) throws Exception {
        MockHttpServletResponse r = send(
                who, post("/v1/prompts").contentType(MediaType.APPLICATION_JSON).content(json));
        assertEquals(201, r.getStatus(), r.getContentAsString());
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return MAPPER.readTree(response.getContentAsString());
    }

    @Test
    void chunkedBodyOverTheCapIs400() throws Exception {
        // Bug: the cap read only Content-Length, so a chunked SDK push declared -1 and Jackson buffered all of it.
        // Each character is a six-byte JSON escape, so the body is over 1 MiB while its text is far shorter.
        Caller me = admin("prompt-public-chunked");
        String big = "{\"name\":\"big\",\"body\":\"" + "\\u0041".repeat(190_000) + "\"}";

        MockHttpServletResponse response = mvc.perform(
                        new ChunkedPost("/v1/prompts", big).header("Authorization", "Bearer " + me.bearer()))
                .andReturn()
                .getResponse();

        assertEquals(400, response.getStatus());
        assertEquals(
                "COMMON.INVALID_BODY",
                json(response).path("meta").path("error").path("code").asText());
    }

    /**
     * A create answers 201, for a new name and for a next version alike, and so does the
     * envelope's status. The bug: a client that checks for 201 treats every create as failed.
     */
    @Test
    void createAnswers201() throws Exception {
        Caller me = admin("prompt-pub-mvc-201");

        for (String body : new String[] {"one", "two"}) {
            MockHttpServletResponse response = send(
                    me,
                    post("/v1/prompts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"p\",\"body\":\"" + body + "\"}"));
            assertEquals(201, response.getStatus(), response.getContentAsString());
            assertEquals(201, json(response).path("meta").path("status").asInt());
        }
    }

    @Test
    void noKeyIs401_sessionIs403() throws Exception {
        assertEquals(
                401,
                mvc.perform(get("/v1/prompts/fetch").param("name", "p"))
                        .andReturn()
                        .getResponse()
                        .getStatus());

        MockHttpServletResponse signup = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "email",
                                "prompt-session-" + UUID.randomUUID() + "@example.com",
                                "password",
                                "a-good-password"))))
                .andReturn()
                .getResponse();
        Cookie session = Objects.requireNonNull(signup.getCookie("tessary-session"));
        MockHttpServletResponse response = mvc.perform(
                        get("/v1/prompts/fetch").param("name", "p").cookie(session))
                .andReturn()
                .getResponse();

        assertEquals(403, response.getStatus());
        assertEquals(
                "PROMPTS.TOKEN_REQUIRED",
                json(response).path("meta").path("error").path("code").asText());
    }

    @Test
    void fetch_usesThePromptsPageFieldNames() throws Exception {
        // Bug: the template comes back as "prompt" here but "body" on the Prompts page and MCP, or a field another
        // tool defines, such as is_active, rides along.
        Caller me = admin("prompt-pub-mvc-fields");
        create(me, "{\"name\":\"p\",\"body\":\"hi\",\"labels\":[\"production\"],\"commit_message\":\"first\"}");

        JsonNode data =
                json(send(me, get("/v1/prompts/fetch").param("name", "p"))).path("data");

        assertEquals("hi", data.path("body").asText());
        assertEquals("first", data.path("commit_message").asText());
        assertTrue(data.path("prompt").isMissingNode());
        assertTrue(data.path("is_active").isMissingNode());
    }

    @Test
    void capabilityOffIs403() throws Exception {
        Caller me = admin("prompt-pub-mvc-capability");
        capabilities.withhold(me.fix().org().id(), Capability.PROMPT_MANAGEMENT);

        MockHttpServletResponse response = send(me, get("/v1/prompts/fetch").param("name", "p"));

        assertEquals(403, response.getStatus());
        assertEquals(
                "CAPABILITY.DISABLED",
                json(response).path("meta").path("error").path("code").asText());
    }

    @Test
    void folderNameRoundTripsUnencoded() throws Exception {
        Caller me = admin("prompt-pub-mvc-folder");
        create(me, "{\"name\":\"team/a/b\",\"body\":\"hi\",\"labels\":[\"production\"]}");

        JsonNode data = json(send(me, get("/v1/prompts/fetch").param("name", "team/a/b")))
                .path("data");

        assertEquals("team/a/b", data.path("name").asText());
    }

    @Test
    void listIsTheBareRoute_andShape() throws Exception {
        Caller me = admin("prompt-pub-mvc-list");
        create(me, "{\"name\":\"b\",\"body\":\"one\",\"config\":{\"a\":1},\"labels\":[\"staging\"]}");
        create(me, "{\"name\":\"b\",\"body\":\"two\",\"config\":{\"a\":2}}");
        create(me, "{\"name\":\"a\",\"body\":\"x\"}");

        JsonNode body = json(send(me, get("/v1/prompts"))).path("data");

        // Bug: a second data/meta envelope nested inside Tessary's own, a shape no other Tessary list has.
        assertEquals(2, body.path("total").asInt());
        assertTrue(body.path("meta").isMissingNode());
        assertEquals("a", body.path("items").path(0).path("name").asText());
        JsonNode b = body.path("items").path(1);
        assertEquals("b", b.path("name").asText());
        assertEquals(MAPPER.readTree("[1,2]"), b.path("versions"));
        assertEquals(MAPPER.readTree("[\"staging\",\"latest\"]"), b.path("labels"));
        assertEquals(2, b.path("latest_config").path("a").asInt());
    }
}
