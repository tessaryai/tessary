// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * The usage routes over HTTP, where the capability interceptor and Spring's path matching run: a withheld capability
 * is refused before the handler, and the literal {@code usage} segment is not read as a prompt id.
 */
@AuthEnforcedContext
record PromptUsageControllerMvcTest(
        MockMvc mvc, OrganizationRepository orgs, ProjectRepository projects, CapabilityFixture capabilities) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Signed(Cookie session, Organization org, Project project) {}

    @Test
    void capabilityOffIs403() throws Exception {
        // Bug: the controller carries no capability gate, so an org that turned prompts off still reads usage.
        Signed me = signUp("prompt-usage-off@example.com");
        capabilities.withhold(me.org().id(), Capability.PROMPT_MANAGEMENT);

        MockHttpServletResponse response = mvc.perform(
                        get(base(me) + "/usage/summary").cookie(me.session()))
                .andReturn()
                .getResponse();

        assertEquals(403, response.getStatus());
        assertEquals(
                "CAPABILITY.DISABLED",
                body(response).path("meta").path("error").path("code").asText());
    }

    @Test
    void literalUsageSummaryWinsOverPromptId() throws Exception {
        // Bug: GET /prompts/usage/summary routes to /{promptId}/usage with promptId "usage" and answers 404.
        Signed me = signUp("prompt-usage-literal@example.com");

        MockHttpServletResponse response = mvc.perform(
                        get(base(me) + "/usage/summary").cookie(me.session()))
                .andReturn()
                .getResponse();

        assertEquals(200, response.getStatus());
        assertEquals(7, body(response).path("data").path("days").asInt());
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

    private static String base(Signed who) {
        return "/api/orgs/" + who.org().slug() + "/projects/" + who.project().slug() + "/prompts";
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return MAPPER.readTree(response.getContentAsString());
    }
}
