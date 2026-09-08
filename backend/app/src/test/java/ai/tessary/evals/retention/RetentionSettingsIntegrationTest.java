// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.evals.auth.AuthFilter;
import ai.tessary.evals.config.RetentionProperties;
import ai.tessary.evals.tenant.Organization;
import ai.tessary.evals.tenant.OrganizationRepository;
import ai.tessary.evals.tenant.Project;
import ai.tessary.evals.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Settings → Data retention (#1205): the page reads the install default, a project override is
 * what the sweeper's own {@link RetentionResolver} then reports, clearing it restores the default,
 * {@code 0} keeps forever, and a negative number is refused.
 */
@SpringBootTest
class RetentionSettingsIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("evals.auth.cookie-password", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("workos.api-key", () -> "");
        r.add("workos.client-id", () -> "");
        r.add("evals.auth.disabled", () -> "false");
    }

    @Autowired
    WebApplicationContext wac;

    @Autowired
    AuthFilter authFilter;

    @Autowired
    OrganizationRepository orgs;

    @Autowired
    ProjectRepository projects;

    @Autowired
    RetentionResolver resolver;

    @Autowired
    RetentionProperties props;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(authFilter).build();
    }

    private String path(Organization org, Project project) {
        return "/api/orgs/" + org.slug() + "/projects/" + project.slug() + "/retention";
    }

    private String body(Integer traces, Integer detections) throws Exception {
        Map<String, Integer> m = new HashMap<>();
        m.put("traces", traces);
        m.put("detections", detections);
        return mapper.writeValueAsString(m);
    }

    @Test
    void overrideClearAndBounds() throws Exception {
        MockHttpServletResponse signup = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("email", "retention-1205@example.com", "password", "a-good-password"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        Cookie session = signup.getCookie("evals-session");
        assertNotNull(session);
        String orgId = mapper.readTree(signup.getContentAsString())
                .path("data")
                .path("orgId")
                .asText();
        Organization org = orgs.findById(orgId).orElseThrow();
        Project project = projects.findDefaultForOrg(org.id()).orElseThrow();
        String path = path(org, project);

        int traceDefault = props.getTraceTtlDays();
        int detectionDefault = props.getDetectionTtlDays();
        mvc.perform(get(path).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_manage").value(true))
                .andExpect(jsonPath("$.data.classes[0].data_class").value("traces"))
                .andExpect(jsonPath("$.data.classes[0].ttl_days").value(traceDefault))
                .andExpect(jsonPath("$.data.classes[0].from_policy").value(false))
                .andExpect(jsonPath("$.data.classes[0].platform_default_days").value(traceDefault))
                .andExpect(jsonPath("$.data.classes[1].data_class").value("detections"))
                .andExpect(jsonPath("$.data.classes[1].ttl_days").value(detectionDefault));

        mvc.perform(put(path)
                        .cookie(session)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(30, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.classes[0].ttl_days").value(30))
                .andExpect(jsonPath("$.data.classes[0].from_policy").value(true))
                .andExpect(jsonPath("$.data.classes[1].ttl_days").value(0))
                .andExpect(jsonPath("$.data.classes[1].from_policy").value(true));
        var effective = resolver.resolveByDataClass(project.id());
        assertEquals(30, effective.get(RetentionResolver.DataClass.TRACES).ttlDays(), "the sweeper sees the override");
        assertEquals(
                false, effective.get(RetentionResolver.DataClass.DETECTIONS).bounded(), "0 keeps forever");

        mvc.perform(put(path)
                        .cookie(session)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, 14)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.classes[0].ttl_days").value(traceDefault))
                .andExpect(jsonPath("$.data.classes[0].from_policy").value(false))
                .andExpect(jsonPath("$.data.classes[1].ttl_days").value(14));

        mvc.perform(put(path)
                        .cookie(session)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(-1, null)))
                .andExpect(status().isBadRequest());
        assertEquals(
                traceDefault,
                resolver.resolveByDataClass(project.id())
                        .get(RetentionResolver.DataClass.TRACES)
                        .ttlDays());

        // A member reads the page but cannot change it: deletion is owner/admin business.
        mvc.perform(post("/api/orgs/" + org.slug() + "/members")
                        .cookie(session)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("email", "member-1205@example.com", "role", "member"))))
                .andExpect(status().isOk());
        MockHttpServletResponse memberSignup = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("email", "member-1205@example.com", "password", "a-good-password"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        Cookie member = memberSignup.getCookie("evals-session");
        assertNotNull(member);
        mvc.perform(get(path).cookie(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_manage").value(false));
        mvc.perform(put(path)
                        .cookie(member)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(7, 7)))
                .andExpect(status().isForbidden());
    }
}
