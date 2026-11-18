// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ai.tessary.plan.Capability;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.AuthEnforcedContext;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The Prompts page API over HTTP with a real ADMIN key, for what a direct call skips: the capability interceptor, the
 * body-size interceptor that runs before binding, Jackson binding of the free-form body, element validation, and
 * Spring's literal-over-variable route choice.
 */
@AuthEnforcedContext
record PromptControllerMvcTest(MockMvc mvc, TenantService tenants, ApiKeyService keys, CapabilityFixture capabilities) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Caller(TenantFixture.Setup fix, String bearer) {
        String base() {
            return "/api/orgs/" + fix.org().slug() + "/projects/"
                    + fix.project().slug() + "/prompts";
        }
    }

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

    private MockHttpServletResponse sendJson(Caller who, MockHttpServletRequestBuilder req, String json)
            throws Exception {
        return send(who, req.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return MAPPER.readTree(response.getContentAsString());
    }

    private static String code(MockHttpServletResponse response) throws Exception {
        return json(response).path("meta").path("error").path("code").asText();
    }

    @Test
    void literalFolderRouteWinsOverPromptId() throws Exception {
        Caller me = admin("prompt-mvc-folders");
        MockHttpServletResponse response =
                send(me, delete(me.base() + "/folders").param("path", "x"));

        assertEquals(200, response.getStatus());
        assertEquals(0, json(response).path("data").path("deleted_count").asInt(-1));
    }

    @Test
    void capabilityOffIs403() throws Exception {
        Caller me = admin("prompt-mvc-capability");
        capabilities.withhold(me.fix().org().id(), Capability.PROMPT_MANAGEMENT);

        MockHttpServletResponse response = send(me, get(me.base()));

        assertEquals(403, response.getStatus());
        assertEquals("CAPABILITY.DISABLED", code(response));
    }

    @Test
    void bodyArrivesAsJsonNotString() throws Exception {
        Caller me = admin("prompt-mvc-body");
        String id =
                json(sendJson(me, post(me.base()), """
                {"name":"chat","type":"chat","body":[{"role":"system","content":"Be brief."},
                 {"type":"placeholder","name":"history"}],"config":{"temperature":0.2}}
                """)).path("data").path("id").asText();

        JsonNode version =
                json(send(me, get(me.base() + "/" + id + "/versions/1"))).path("data");

        assertEquals("system", version.path("body").path(0).path("role").asText());
        assertEquals("placeholder", version.path("body").path(1).path("type").asText());
        assertEquals(0.2, version.path("config").path("temperature").asDouble());
        assertEquals("ui", version.path("created_via").asText());
    }

    /**
     * The bug: the version header and the versions rail showed {@code created_by}, a raw principal id. A key's write
     * is its owner's, so the label is the owner's name.
     */
    @Test
    void versionsCarryTheAuthorsNameNotTheirId() throws Exception {
        Caller me = admin("prompt-mvc-author");
        String id = json(sendJson(me, post(me.base()), "{\"name\":\"p\",\"type\":\"text\",\"body\":\"hi\"}"))
                .path("data")
                .path("id")
                .asText();

        JsonNode summary = json(send(me, get(me.base() + "/" + id)))
                .path("data")
                .path("versions")
                .path(0);
        JsonNode version =
                json(send(me, get(me.base() + "/" + id + "/versions/1"))).path("data");

        String name = me.fix().user().displayName();
        assertEquals(me.fix().user().id(), summary.path("created_by").asText());
        assertEquals(name, summary.path("created_by_label").asText());
        assertEquals(name, version.path("created_by_label").asText());
    }

    @Test
    void oversizeBodyIs400BeforeBinding() throws Exception {
        Caller me = admin("prompt-mvc-oversize");
        String big = "{\"name\":\"big\",\"type\":\"text\",\"body\":\"" + "x".repeat(1_200_000) + "\"}";

        MockHttpServletResponse response = sendJson(me, post(me.base()), big);

        assertEquals(400, response.getStatus());
        assertEquals("COMMON.INVALID_BODY", code(response));
        assertEquals(
                0, json(send(me, get(me.base()))).path("data").path("total").asInt(-1));
    }

    @Test
    void chunkedBodyOverTheCapIs400() throws Exception {
        // Bug: the cap read only Content-Length, so a chunked body declared -1 and Jackson buffered all of it. Each
        // character here is a six-byte JSON escape on the wire, so the body is over 1 MiB yet under the
        // 200,000-character body cap that would otherwise refuse it.
        Caller me = admin("prompt-mvc-chunked");
        String big = "{\"name\":\"big\",\"type\":\"text\",\"body\":\"" + "\\u0041".repeat(190_000) + "\"}";

        MockHttpServletResponse response = mvc.perform(
                        new ChunkedPost(me.base(), big).header("Authorization", "Bearer " + me.bearer()))
                .andReturn()
                .getResponse();

        assertEquals(400, response.getStatus());
        assertEquals("COMMON.INVALID_BODY", code(response));
        assertEquals(
                0, json(send(me, get(me.base()))).path("data").path("total").asInt(-1));
    }

    @Test
    void chunkedBodyUnderTheCapIsRead() throws Exception {
        // Bug: the stream cap refuses or truncates a chunked body that is within the limit.
        Caller me = admin("prompt-mvc-chunked-small");

        MockHttpServletResponse response = mvc.perform(
                        new ChunkedPost(me.base(), "{\"name\":\"small\",\"type\":\"text\",\"body\":\"Hi {{name}}\"}")
                                .header("Authorization", "Bearer " + me.bearer()))
                .andReturn()
                .getResponse();

        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertEquals("small", json(response).path("data").path("name").asText());
    }

    @Test
    void nullLabelElementIs400() throws Exception {
        Caller me = admin("prompt-mvc-null-label");

        MockHttpServletResponse response =
                sendJson(me, put(me.base() + "/any-id/versions/1/labels"), "{\"labels\":[null]}");

        assertEquals(400, response.getStatus());
        assertEquals("COMMON.VALIDATION_FAILED", code(response));
    }
}
