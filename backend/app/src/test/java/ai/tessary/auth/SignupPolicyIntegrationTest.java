// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.tessary.tenant.AuditLogRepository;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.PrincipalRepository;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The sign-up policy end to end through the real {@link PasswordAuthProvider}: an owner
 * tightens the policy, strangers are refused with nothing created, invitations win in every mode,
 * a listed domain is admitted, and existing members keep signing in. The policy is instance-wide
 * (the install's first organization's), so the methods run in order on one shared database.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// Own context on purpose: it holds the instance-wide signup policy at `invite`/`domain` across ordered methods, which
// would refuse every other class's signup while it is held.
@TestPropertySource(properties = "test.context-group=signup-policy")
class SignupPolicyIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.auth.cookie-password", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("workos.api-key", () -> "");
        r.add("workos.client-id", () -> "");
        r.add("tessary.auth.disabled", () -> "false");
    }

    private static final String OWNER = "owner-1226@example.com";
    private static final String PASSWORD = "a-good-password";

    @Autowired
    WebApplicationContext wac;

    @Autowired
    AuthFilter authFilter;

    @Autowired
    PrincipalRepository users;

    @Autowired
    OrganizationRepository orgs;

    @Autowired
    OrgMembershipRepository memberships;

    @Autowired
    ProjectRepository projects;

    @Autowired
    AuditLogRepository audits;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(authFilter).build();
    }

    private String credentials(String email) throws Exception {
        return mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD));
    }

    private MockHttpServletResponse signup(String email) throws Exception {
        return mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email)))
                .andReturn()
                .getResponse();
    }

    private Cookie session(MockHttpServletResponse res) {
        Cookie session = res.getCookie("tessary-session");
        assertNotNull(session, "a successful sign-in sets the tessary-session cookie");
        return session;
    }

    /** The owner's session: the first account on the install, created once and signed in after. */
    private Cookie owner() throws Exception {
        if (users.findByEmail(OWNER).isEmpty()) {
            MockHttpServletResponse res = signup(OWNER);
            assertEquals(200, res.getStatus());
            return session(res);
        }
        MockHttpServletResponse res = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(OWNER)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        return session(res);
    }

    private Organization installOrg() {
        return orgs.findOldest().orElseThrow();
    }

    private void setPolicy(Cookie owner, String mode, List<String> domains) throws Exception {
        mvc.perform(put("/api/orgs/" + installOrg().slug() + "/signup-policy")
                        .cookie(owner)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("mode", mode, "domains", domains))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value(mode));
    }

    private void invite(Cookie owner, String email) throws Exception {
        mvc.perform(post("/api/orgs/" + installOrg().slug() + "/members")
                        .cookie(owner)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "role", "member"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("invited"));
    }

    private void assertRefusedWithNothingCreated(String email) throws Exception {
        long orgsBefore = orgs.countAll();
        MockHttpServletResponse res = signup(email);
        assertEquals(403, res.getStatus(), res.getContentAsString());
        assertTrue(res.getContentAsString().contains("AUTH.SIGNUP_REFUSED"), res.getContentAsString());
        assertTrue(users.findByEmail(email).isEmpty(), "a refused sign-up leaves no principal");
        assertEquals(orgsBefore, orgs.countAll(), "a refused sign-up mints no organization");
    }

    @Test
    @Order(1)
    void freshInstallIsOpenAndTheFirstAccountIsAlwaysAdmitted() throws Exception {
        mvc.perform(get("/auth/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupPolicy").value("open"));
        Cookie owner = owner();
        mvc.perform(get("/api/orgs/" + installOrg().slug() + "/signup-policy").cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("open"));
        assertEquals(200, signup("anyone-1226@example.com").getStatus(), "open admits a stranger");
    }

    @Test
    @Order(2)
    void inviteModeRefusesStrangersAndAdmitsTheInvited() throws Exception {
        Cookie owner = owner();
        setPolicy(owner, "invite", List.of());
        mvc.perform(get("/auth/mode")).andExpect(jsonPath("$.data.signupPolicy").value("invite"));

        assertRefusedWithNothingCreated("stranger-1226@example.com");

        String guest = "guest-1226@example.com";
        invite(owner, guest);
        MockHttpServletResponse res = signup(guest);
        assertEquals(200, res.getStatus(), res.getContentAsString());
        String guestId = users.findByEmail(guest).orElseThrow().id();
        assertTrue(
                memberships.find(installOrg().id(), guestId).isPresent(),
                "the invitation is consumed into a membership of the inviting organization");
    }

    @Test
    @Order(3)
    void domainModeAdmitsListedDomainsRefusesOthersAndStillHonoursInvitations() throws Exception {
        Cookie owner = owner();
        setPolicy(owner, "domain", List.of("Allowed.example"));

        assertEquals(200, signup("dev-1226@allowed.example").getStatus());
        assertRefusedWithNothingCreated("dev-1226@other.example");

        String contractor = "contractor-1226@other.example";
        invite(owner, contractor);
        assertEquals(200, signup(contractor).getStatus());

        // A case-mismatched invitee is admitted AND joined: invitations are stored lower-cased,
        // and consumption matches the same way the gate does.
        invite(owner, "Mixed-Case-1226@other.example");
        MockHttpServletResponse mixed = signup("MIXED-CASE-1226@Other.example");
        assertEquals(200, mixed.getStatus(), mixed.getContentAsString());
        String mixedId =
                users.findByEmail("MIXED-CASE-1226@Other.example").orElseThrow().id();
        assertTrue(
                memberships.find(installOrg().id(), mixedId).isPresent(),
                "the invitation is consumed despite the case");
    }

    @Test
    @Order(4)
    void existingMembersKeepSigningInAfterThePolicyTightens() throws Exception {
        Cookie owner = owner();
        setPolicy(owner, "invite", List.of());
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("dev-1226@allowed.example")))
                .andExpect(status().isOk());
        assertRefusedWithNothingCreated("late-1226@allowed.example");
    }

    @Test
    @Order(5)
    void onlyMembersManagersReadOrWriteThePolicyAndEveryChangeIsAudited() throws Exception {
        Cookie owner = owner();
        MockHttpServletResponse guestLogin = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("guest-1226@example.com")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        Cookie guest = session(guestLogin);
        String slug = installOrg().slug();
        mvc.perform(get("/api/orgs/" + slug + "/signup-policy").cookie(guest)).andExpect(status().isForbidden());
        mvc.perform(put("/api/orgs/" + slug + "/signup-policy")
                        .cookie(guest)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"open\",\"domains\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/orgs/" + slug + "/signup-policy")
                        .cookie(owner)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"domain\",\"domains\":[]}"))
                .andExpect(status().isBadRequest());

        // The raw settings PATCH cannot smuggle a policy change past validation and audit.
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/orgs/" + slug)
                                .cookie(owner)
                                .header("X-Requested-With", "XMLHttpRequest")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"Renamed\",\"settings\":\"{\\\"signupPolicy\\\":{\\\"mode\\\":\\\"open\\\",\\\"domains\\\":[]}}\"}"))
                .andExpect(status().isBadRequest());
        // A blob that omits the key keeps the stored policy instead of dropping it.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/orgs/" + slug)
                        .cookie(owner)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"settings\":\"{\\\"theme\\\":\\\"dark\\\"}\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/auth/mode")).andExpect(jsonPath("$.data.signupPolicy").value("invite"));

        // The policy is instance-wide: a second organization on the install reads it as governed elsewhere.
        String guestOrgSlug = orgs.findByUserId(users.findByEmail("anyone-1226@example.com")
                        .orElseThrow()
                        .id())
                .get(0)
                .slug();
        MockHttpServletResponse anyoneLogin = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("anyone-1226@example.com")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        Cookie anyone = session(anyoneLogin);
        mvc.perform(get("/api/orgs/" + guestOrgSlug + "/signup-policy").cookie(anyone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.governing").value(false))
                .andExpect(jsonPath("$.data.governing_org_slug").value(slug));
        mvc.perform(put("/api/orgs/" + guestOrgSlug + "/signup-policy")
                        .cookie(anyone)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"open\",\"domains\":[]}"))
                .andExpect(status().isConflict());

        Organization org = installOrg();
        String projectId = projects.findDefaultForOrg(org.id()).orElseThrow().id();
        int before =
                audits.findBySubject(projectId, "organization", org.id(), 50).size();
        setPolicy(owner, "open", List.of());
        List<?> after = audits.findBySubject(projectId, "organization", org.id(), 50);
        assertEquals(before + 1, after.size(), "each policy change writes one audit row");
        assertFalse(before == 0, "the earlier changes in this class were audited too");
        assertEquals(200, signup("reopened-1226@example.com").getStatus(), "open again admits a stranger");
    }
}
