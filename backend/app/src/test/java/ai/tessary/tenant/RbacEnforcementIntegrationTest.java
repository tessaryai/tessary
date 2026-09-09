// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.billing.BillingController;
import ai.tessary.classifier.ClassifierController;
import ai.tessary.classifier.ClassifierDtos.SetEnabledRequest;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

/**
 * End-to-end RBAC enforcement at the controller boundary against a real (Testcontainers)
 * Postgres: a viewer cannot mutate, a non-billing role cannot
 * reach billing, admins can manage members, owner-only escalations are blocked, and every
 * check survives a direct call (we invoke the controller methods straight, bypassing any
 * UI affordance). The fixture owner is reused as the actor; collaborators are minted with
 * explicit roles via the membership repository so each role's policy is exercised for real.
 */
@SpringBootTest
class RbacEnforcementIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    OrganizationController orgController;

    @Autowired
    BillingController billingController;

    @Autowired
    ClassifierController signalController;

    @Autowired
    ai.tessary.classifier.ClassifierService signalService;

    @Autowired
    TenantService tenants;

    @Autowired
    PrincipalRepository users;

    @Autowired
    OrgMembershipRepository memberships;

    private TenantContext session(Principal u) {
        return new TenantContext(u.id(), u.email(), null, null, null, null);
    }

    /** Mint a fresh user and join them to {@code orgId} with {@code role}. */
    private Principal memberWithRole(String orgId, String role, String tag) {
        Principal u = tenants.upsertUserFromWorkos(
                "user_rbac_" + tag + "_" + System.nanoTime(),
                "rbac+" + tag + "+" + System.nanoTime() + "@example.com",
                tag,
                null);
        memberships.insert(OrgMembership.of(orgId, u.id(), role, Instant.now().toString()));
        return u;
    }

    @Test
    void viewer_cannotCreateProject_butCanView() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-viewer");
        Principal viewer = memberWithRole(fix.org().id(), OrgMembership.VIEWER, "viewer");

        // View is allowed.
        assertTrue(orgController
                        .listProjects(session(viewer), fix.org().slug())
                        .data()
                        .size()
                >= 1);

        // Any mutation is 403.
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> orgController.createProject(
                        session(viewer),
                        fix.org().slug(),
                        new OrganizationController.CreateProjectRequest("nope", null)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void member_canCreateProject_butCannotManageMembers() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-member");
        Principal member = memberWithRole(fix.org().id(), OrgMembership.MEMBER, "member");

        // Content management is allowed for a plain member.
        var created = orgController.createProject(
                session(member), fix.org().slug(), new OrganizationController.CreateProjectRequest("by-member", null));
        assertEquals("by-member", created.data().name());

        // But managing members is not.
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> orgController.addMember(
                        session(member),
                        fix.org().slug(),
                        new OrganizationController.AddMemberRequest("x@example.com", "member")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void admin_canManageMembers_butNotBillingOrLifecycle() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-admin");
        Principal admin = memberWithRole(fix.org().id(), OrgMembership.ADMIN, "admin");

        // Admin can invite a (non-owner) member.
        var result = orgController.addMember(
                session(admin),
                fix.org().slug(),
                new OrganizationController.AddMemberRequest("invitee@example.com", "viewer"));
        assertTrue(result.data().status().equals("invited")
                || result.data().status().equals("added"));

        // Admin cannot reach billing.
        ResponseStatusException billing = assertThrows(
                ResponseStatusException.class,
                () -> billingController.getBilling(session(admin), fix.org().slug(), null, null));
        assertEquals(HttpStatus.FORBIDDEN, billing.getStatusCode());

        // Admin cannot perform owner-only lifecycle (rename the organization). This RBAC check
        // exercises updateOrg, which runs through the same requireOwner()/ORG_ADMIN gate as the
        // rest of the org lifecycle actions on this controller.
        ResponseStatusException del = assertThrows(
                ResponseStatusException.class,
                () -> orgController.updateOrg(
                        session(admin),
                        fix.org().slug(),
                        new OrganizationController.UpdateOrgRequest("renamed-by-admin", null)));
        assertEquals(HttpStatus.FORBIDDEN, del.getStatusCode());
    }

    @Test
    void admin_cannotGrantOwnerRole() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-escalate");
        Principal admin = memberWithRole(fix.org().id(), OrgMembership.ADMIN, "admin2");
        Principal target = memberWithRole(fix.org().id(), OrgMembership.MEMBER, "target");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> orgController.updateMember(
                        session(admin),
                        fix.org().slug(),
                        target.id(),
                        new OrganizationController.UpdateMemberRequest("owner")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode(), "non-owner must not be able to mint an owner");
    }

    @Test
    void billingRole_reachesBilling_butNotOrgContent() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-billing");
        Principal billing = memberWithRole(fix.org().id(), OrgMembership.BILLING, "billing");

        // Billing role can reach billing.
        var summary = billingController.getBilling(session(billing), fix.org().slug(), null, null);
        assertEquals(fix.org().id(), summary.data().orgId());

        // But cannot see organization content (listing projects requires ORG_VIEW).
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> orgController.listProjects(session(billing), fix.org().slug()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void owner_canDoEverything() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-owner");
        TenantContext owner = session(fix.user());

        // Members management, billing, and content all succeed for the owner.
        orgController.members(owner, fix.org().slug());
        billingController.getBilling(owner, fix.org().slug(), null, null);
        var created = orgController.createProject(
                owner, fix.org().slug(), new OrganizationController.CreateProjectRequest("owner-proj", null));
        assertEquals("owner-proj", created.data().name());
    }

    // ---- Project-scoped resource controllers: classifiers ----

    @Test
    void viewer_cannotToggleSignal_butCanList() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-sig-viewer");
        Principal viewer = memberWithRole(fix.org().id(), OrgMembership.VIEWER, "sig-viewer");

        // Seeding happens on the first successful generation run; simulate it, then list as a viewer.
        signalService.seedBuiltIns(fix.project().id());
        var signals = signalController
                .list(session(viewer), fix.org().slug(), fix.project().slug())
                .data();
        assertTrue(!signals.isEmpty(), "seeded built-in signals are listable");
        String classifierId = signals.get(0).id();

        // Toggling enabled is a mutation → 403.
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> signalController.setEnabled(
                        session(viewer),
                        fix.org().slug(),
                        fix.project().slug(),
                        classifierId,
                        new SetEnabledRequest(false)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void member_canToggleSignal() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-sig-member");
        Principal member = memberWithRole(fix.org().id(), OrgMembership.MEMBER, "sig-member");

        signalService.seedBuiltIns(fix.project().id()); // the generation-run trigger's effect
        var signals = signalController
                .list(session(member), fix.org().slug(), fix.project().slug())
                .data();
        assertTrue(!signals.isEmpty(), "seeded built-in signals are listable");
        String classifierId = signals.get(0).id();

        var updated = signalController.setEnabled(
                session(member), fix.org().slug(), fix.project().slug(), classifierId, new SetEnabledRequest(false));
        assertEquals(classifierId, updated.data().id());
    }
}
