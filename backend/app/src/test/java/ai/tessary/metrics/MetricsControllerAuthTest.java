// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.testsupport.AuthEnforcedContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The metrics routes under the app's real auth posture and its real request validation. */
@AuthEnforcedContext
record MetricsControllerAuthTest(MockMvc mvc, OrganizationRepository orgs, ProjectRepository projects) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/orgs/acme/projects/web/metrics";
    private static final String QUERY = "{\"view\":\"traces\",\"measures\":[\"traces\"],"
            + "\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-09-08T00:00:00Z\"}";

    /** The bug: a metrics route that answers without a credential. */
    @Test
    @DisplayName("every metrics route is 401 without a session or key")
    void everyMetricsRouteIs401WithoutASessionOrKey() throws Exception {
        List<RequestBuilder> routes = List.of(
                get(BASE + "/catalog"),
                json(post(BASE + "/query"), QUERY),
                get(BASE + "/dashboards"),
                get(BASE + "/dashboards/default"),
                json(post(BASE + "/dashboards"), "{\"name\":\"Mine\"}"),
                json(put(BASE + "/dashboards/x"), "{\"name\":\"Mine\"}"),
                delete(BASE + "/dashboards/x").header("X-Requested-With", "XMLHttpRequest"));
        for (RequestBuilder route : routes) {
            mvc.perform(route)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    /** The bug: a null filter or widget passes Jakarta and the validator's dereference answers 500, not 400. */
    @Test
    @DisplayName("a null filter or widget element is 400 VALIDATION_FAILED")
    void aNullFilterOrWidgetElementIs400() throws Exception {
        MockHttpServletResponse signup = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(
                                Map.of("email", "metrics-null-elements@example.com", "password", "a-good-password"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        Cookie session = signup.getCookie("tessary-session");
        assertNotNull(session);
        Organization org = orgs.findById(MAPPER.readTree(signup.getContentAsString())
                        .path("data")
                        .path("orgId")
                        .asText())
                .orElseThrow();
        Project project = projects.findDefaultForOrg(org.id()).orElseThrow();
        String base = "/api/orgs/" + org.slug() + "/projects/" + project.slug() + "/metrics";
        String widget = "{\"id\":\"a\",\"chart\":\"number\",\"view\":\"traces\",\"measures\":[\"traces\"],"
                + "\"filters\":[null],\"x\":0,\"y\":0,\"w\":3,\"h\":2}";
        List<MockHttpServletRequestBuilder> requests = List.of(
                json(post(base + "/query"), QUERY.replace("}", ",\"filters\":[null]}")),
                json(post(base + "/dashboards"), definition("[null]")),
                json(post(base + "/dashboards"), definition("[" + widget + "]")));
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request.cookie(session))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.error.code").value("COMMON.VALIDATION_FAILED"));
        }
    }

    private static String definition(String widgets) {
        return "{\"name\":\"Mine\",\"definition\":{\"version\":1,\"default_range\":\"7d\",\"widgets\":" + widgets
                + "}}";
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
