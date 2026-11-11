// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.plan.Capability;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.testsupport.AuthEnforcedContext;
import ai.tessary.testsupport.CapabilityFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The playground route over HTTP, where the capability interceptor, Spring's path matching and body binding run: a
 * withheld capability is refused before the handler, the literal {@code playground} segment is not read as a prompt
 * id, and a config that is not an object never reaches the service.
 */
@AuthEnforcedContext
record PlaygroundControllerMvcTest(
        MockMvc mvc, OrganizationRepository orgs, ProjectRepository projects, CapabilityFixture capabilities) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String runBody(String config) {
        return "{\"provider\":\"OPENAI\",\"model\":\"gpt-5.5\",\"messages\":[{\"role\":\"user\",\"content\":\"Say hi.\"}],"
                + "\"config\":" + config + "}";
    }

    private record Signed(Cookie session, Organization org, Project project) {}

    @Test
    void promptManagementOff_is403() throws Exception {
        // Bug: the controller carries no capability gate, so an org that turned prompts off still spends its key.
        Signed me = signUp("playground-off@example.com");
        capabilities.withhold(me.org().id(), Capability.PROMPT_MANAGEMENT);

        MockHttpServletResponse response = run(me, runBody("{}"));

        assertEquals(403, response.getStatus());
        assertEquals("CAPABILITY.DISABLED", errorCode(response));
    }

    @Test
    void theLiteralPlaygroundSegment_winsOverPromptId() throws Exception {
        // Bug: POST /prompts/playground/runs routes to a /{promptId} handler and answers 404 or 405. Reaching the
        // playground with no provider key answers its own 412.
        Signed me = signUp("playground-literal@example.com");

        MockHttpServletResponse response = run(me, runBody("{\"temperature\":0.2}"));

        assertEquals(412, response.getStatus());
        assertEquals("MODEL_CONFIG.MISSING_CREDENTIALS", errorCode(response));
    }

    @Test
    void aNonObjectConfigIs400AtBinding() throws Exception {
        // Bug: a config that is not a JSON object is coerced or reaches the service as something other than a map.
        Signed me = signUp("playground-config@example.com");

        MockHttpServletResponse response = run(me, runBody("5"));

        assertEquals(400, response.getStatus());
        assertEquals("COMMON.INVALID_BODY", errorCode(response));
    }

    @Test
    void chunkedBodyOverTheCapIs400BeforeTheProviderCheck() throws Exception {
        // Bug: the cap read only Content-Length, so a chunked run body declared -1 and Jackson buffered every message.
        // Each character is a six-byte JSON escape, so the body is over 1 MiB with each message under its own cap.
        Signed me = signUp("playground-chunked@example.com");
        String message = "{\"role\":\"user\",\"content\":\"" + "\\u0041".repeat(100_000) + "\"}";
        String body = "{\"provider\":\"OPENAI\",\"model\":\"gpt-5.5\",\"messages\":[" + message + "," + message
                + "],\"config\":{}}";

        MockHttpServletResponse response = mvc.perform(new ChunkedPost(
                                "/api/orgs/" + me.org().slug() + "/projects/"
                                        + me.project().slug() + "/prompts/playground/runs",
                                body)
                        .cookie(me.session())
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andReturn()
                .getResponse();

        assertEquals(400, response.getStatus());
        assertEquals("COMMON.INVALID_BODY", errorCode(response));
    }

    private MockHttpServletResponse run(Signed me, String body) throws Exception {
        return mvc.perform(post("/api/orgs/" + me.org().slug() + "/projects/"
                                + me.project().slug() + "/prompts/playground/runs")
                        .cookie(me.session())
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    private Signed signUp(String email) throws Exception {
        MockHttpServletResponse signup = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("email", email, "password", "a-good-password"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        Cookie session = Objects.requireNonNull(signup.getCookie("tessary-session"));
        String orgId = body(signup).path("data").path("orgId").asText();
        Organization org = orgs.findById(orgId).orElseThrow();
        Project project = projects.findDefaultForOrg(org.id()).orElseThrow();
        return new Signed(session, org, project);
    }

    private static String errorCode(MockHttpServletResponse response) throws Exception {
        return body(response).path("meta").path("error").path("code").asText();
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return MAPPER.readTree(response.getContentAsString());
    }
}
