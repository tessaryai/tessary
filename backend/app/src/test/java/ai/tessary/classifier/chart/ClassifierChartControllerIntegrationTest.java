// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.auth.AuthFilter;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.frustration.FrustrationScopeRepository;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.testsupport.AuthEnforcedContext;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.RateClassifierFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /classifiers/chart-scopes} and {@code GET /classifiers/charts} over HTTP: the ranges and scopes they
 * refuse, another project's charts, and one call site's cards and chips.
 */
@AuthEnforcedContext
class ClassifierChartControllerIntegrationTest {

    @Autowired
    WebApplicationContext wac;

    @Autowired
    AuthFilter authFilter;

    @Autowired
    OrganizationRepository orgs;

    @Autowired
    ProjectRepository projects;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    RateClassifierFixture fixture;

    @Autowired
    FrustrationScopeRepository frustrationScopes;

    @Autowired
    JdbcClient jdbc;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(authFilter).build();
    }

    @Test
    void days30_is422InvalidChartDays() throws Exception {
        Signed me = signUp("chart-days@example.com");
        declare(me.project().id(), "cs-a", null);

        assertError(me, "/charts?callSiteId=cs-a&days=30", 422, "CLASSIFIER.INVALID_CHART_DAYS");
        assertError(me, "/chart-scopes?days=30", 422, "CLASSIFIER.INVALID_CHART_DAYS");
    }

    @Test
    void bothOrNeitherScope_is422ChartScope() throws Exception {
        Signed me = signUp("chart-scope@example.com");
        declare(me.project().id(), "cs-a", null);

        assertError(me, "/charts?callSiteId=cs-a&tool=tool:search&days=7", 422, "CLASSIFIER.CHART_SCOPE");
        assertError(me, "/charts?days=7", 422, "CLASSIFIER.CHART_SCOPE");
    }

    @Test
    void unknownCallSite_is422UnknownCallSite() throws Exception {
        Signed me = signUp("chart-unknown@example.com");
        declare(me.project().id(), "cs-a", null);

        assertError(me, "/charts?callSiteId=cs-typo&days=7", 422, "CLASSIFIER.UNKNOWN_CALL_SITE");
    }

    @Test
    void otherProject_cannotReadCharts() throws Exception {
        Signed me = signUp("chart-me@example.com");
        Signed them = signUp("chart-them@example.com");
        declare(them.project().id(), "cs-theirs", null);
        String theirs =
                "/api/orgs/" + them.org().slug() + "/projects/" + them.project().slug() + "/classifiers";

        for (String path : List.of("/charts?callSiteId=cs-theirs&days=7", "/chart-scopes?days=7")) {
            MockHttpServletResponse response = mvc.perform(get(theirs + path).cookie(me.session()))
                    .andReturn()
                    .getResponse();
            assertEquals(403, response.getStatus(), path);
            assertFalse(response.getContentAsString().contains("cs-theirs"), path);
        }
    }

    /**
     * Frustration enabled but scoped elsewhere is an off chip, not an empty card; Malformed Output on a call site
     * with no schema waits; the menu says how many call sites each runs on.
     */
    @Test
    void callSiteCharts_chipTheClassifiersThatCannotDrawACard() throws Exception {
        Signed me = signUp("chart-chips@example.com");
        String pid = me.project().id();
        capabilities.grant(me.org().id(), Capability.FRUSTRATION);
        capabilities.grant(me.org().id(), Capability.MALFORMED_OUTPUT);
        declare(pid, "cs-a", null);
        declare(pid, "cs-b", "{\"type\":\"object\"}");
        ClassifierRow frustration = fixture.builtIn(pid, BuiltInDetector.Kind.FRUSTRATION);
        jdbc.sql("UPDATE classifier SET enabled = TRUE WHERE id = :id")
                .param("id", frustration.id())
                .update();
        frustrationScopes.replace(pid, frustration.id(), List.of("cs-b"));

        JsonNode charts = data(me, "/charts?callSiteId=cs-a&days=7");

        assertEquals("call_site", charts.path("scope").asText());
        assertEquals("cs-a", charts.path("scope_id").asText());
        assertEquals(7, charts.path("days").asInt());
        assertEquals(
                Map.of("state", "off", "reason", "null"),
                chip(charts, BuiltInDetector.Kind.FRUSTRATION),
                "enabled, but not on this call site");
        assertEquals(
                Map.of("state", "waiting", "reason", "no_schema"), chip(charts, BuiltInDetector.Kind.MALFORMED_OUTPUT));

        JsonNode scopes = data(me, "/chart-scopes?days=7");
        JsonNode menu = item(scopes.path("classifiers"), BuiltInDetector.Kind.FRUSTRATION);
        assertEquals("on", menu.path("status").asText());
        assertEquals(1, menu.path("call_site_count").asInt());
        assertFalse(menu.path("all_call_sites").asBoolean(), "frustration is always a pick list");
        assertEquals(List.of("cs-a", "cs-b"), scopes.path("call_sites").findValuesAsText("call_site_id"));
    }

    /** A waiting classifier judges nothing, so it alone does not make a call site "New, learning". */
    @Test
    void callSiteLearning_countsOnlyClassifiersThatAreOnNotWaiting() throws Exception {
        Signed me = signUp("chart-learning@example.com");
        String pid = me.project().id();
        capabilities.grant(me.org().id(), Capability.MALFORMED_OUTPUT);
        declare(pid, "cs-a", null);
        declare(pid, "cs-b", "{\"type\":\"object\"}");
        fixture.builtIn(pid, BuiltInDetector.Kind.MALFORMED_OUTPUT);
        jdbc.sql("UPDATE classifier SET enabled = FALSE WHERE project_id = :pid AND detector <> :malformed")
                .param("pid", pid)
                .param("malformed", BuiltInDetector.Kind.MALFORMED_OUTPUT)
                .update();

        JsonNode scopes = data(me, "/chart-scopes?days=7");

        assertFalse(callSite(scopes, "cs-a").path("learning").asBoolean(), "Malformed Output waits for a schema here");
        assertTrue(callSite(scopes, "cs-b").path("learning").asBoolean(), "Malformed Output is on and has no baseline");
    }

    private static JsonNode callSite(JsonNode scopes, String id) {
        for (JsonNode n : scopes.path("call_sites")) {
            if (id.equals(n.path("call_site_id").asText())) return n;
        }
        throw new AssertionError("no " + id + " in " + scopes.path("call_sites"));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private record Signed(Cookie session, Organization org, Project project) {}

    private Signed signUp(String email) throws Exception {
        MockHttpServletResponse signup = mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "a-good-password"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        Cookie session = Objects.requireNonNull(signup.getCookie("tessary-session"));
        String orgId = mapper.readTree(signup.getContentAsString())
                .path("data")
                .path("orgId")
                .asText();
        Organization org = orgs.findById(orgId).orElseThrow();
        Project project = projects.findDefaultForOrg(org.id()).orElseThrow();
        return new Signed(session, org, project);
    }

    private String base(Signed who) {
        return "/api/orgs/" + who.org().slug() + "/projects/" + who.project().slug() + "/classifiers";
    }

    private JsonNode data(Signed who, String path) throws Exception {
        String body = mvc.perform(get(base(who) + path).cookie(who.session()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return mapper.readTree(body).path("data");
    }

    private void assertError(Signed who, String path, int status, String code) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(base(who) + path).cookie(who.session()))
                .andReturn()
                .getResponse();
        assertEquals(status, response.getStatus(), path);
        assertEquals(
                code,
                mapper.readTree(response.getContentAsString())
                        .path("meta")
                        .path("error")
                        .path("code")
                        .asText(),
                path);
    }

    private static Map<String, String> chip(JsonNode charts, String key) {
        JsonNode chip = item(charts.path("chips"), key);
        return Map.of(
                "state",
                chip.path("state").asText(),
                "reason",
                chip.path("reason").asText());
    }

    private static JsonNode item(JsonNode list, String key) {
        for (JsonNode n : list) {
            if (key.equals(n.path("classifier_key").asText())) return n;
        }
        throw new AssertionError("no " + key + " in " + list);
    }

    private void declare(String pid, String callSite, @Nullable String schema) {
        jdbc.sql("INSERT INTO call_site (project_id, id, output_schema) VALUES (:pid, :id, :schema)")
                .param("pid", pid)
                .param("id", callSite)
                .param("schema", schema)
                .update();
    }
}
