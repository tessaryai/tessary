// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.testsupport.AuthEnforcedContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The metrics routes under the app's real auth posture. The bug: a metrics route that answers without a credential. */
@AuthEnforcedContext
record MetricsControllerAuthTest(MockMvc mvc) {

    private static final String BASE = "/api/orgs/acme/projects/web/metrics";
    private static final String QUERY = "{\"view\":\"traces\",\"measures\":[\"traces\"],"
            + "\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-09-08T00:00:00Z\"}";

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

    private static RequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
