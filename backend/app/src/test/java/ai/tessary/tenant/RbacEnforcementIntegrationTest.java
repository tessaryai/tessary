// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.billing.BillingController;
import ai.tessary.classifier.ClassifierController;
import ai.tessary.classifier.ClassifierDtos.SetEnabledRequest;
import ai.tessary.tenant.rbac.Role;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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

    @Autowired
    InvitationRepository invitations;

    @Autowired
    ProjectRepository projects;

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

    private static HttpStatusCode statusOf(Executable call) {
        return assertThrows(ResponseStatusException.class, call).getStatusCode();
    }

    @Test
    void viewer_cannotCreateProject_butCanView() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-viewer");
        Principal viewer = memberWithRole(fix.org().id(), Role.VIEWER.wire(), "viewer");

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
        Principal admin = memberWithRole(fix.org().id(), Role.ADMIN.wire(), "admin");

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
        Principal admin = memberWithRole(fix.org().id(), Role.ADMIN.wire(), "admin2");
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
        Principal billing = memberWithRole(fix.org().id(), Role.BILLING.wire(), "billing");

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
        Principal viewer = memberWithRole(fix.org().id(), Role.VIEWER.wire(), "sig-viewer");

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

    // ---- Organization membership, invitations and project lifecycle ----

    /**
     * The bug: an org created through the API leaves its creator without the owner membership, or the org
     * without its default project, so the creator is locked out of what they just made.
     */
    @Test
    void createOrg_makesTheCreatorItsOwnerWithADefaultProject() {
        long n = System.nanoTime();
        Principal creator =
                tenants.upsertUserFromWorkos("user_rbac_create_" + n, "rbac+create+" + n + "@example.com", "c", null);

        Organization org = orgController
                .createOrg(session(creator), new OrganizationController.CreateOrgRequest("Created Org"))
                .data();

        assertEquals("Created Org", org.name());
        assertEquals(
                OrgMembership.OWNER,
                memberships.find(org.id(), creator.id()).orElseThrow().role());
        assertTrue(projects.findDefaultForOrg(org.id()).isPresent(), "the org starts with its default project");
    }

    /**
     * The bugs: adding an existing account parks a pending invite it will never consume instead of joining
     * it, the email is matched case- or whitespace-sensitively, an omitted role is not the least-privilege
     * member, or the same person can be added twice.
     */
    @Test
    void addMember_joinsAnExistingAccountDirectly_once() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-add-existing");
        long n = System.nanoTime();
        Principal existing = tenants.upsertUserFromWorkos(
                "user_rbac_existing_" + n, "rbac+existing+" + n + "@example.com", "e", null);
        TenantContext owner = session(fix.user());

        var added = orgController
                .addMember(
                        owner,
                        fix.org().slug(),
                        new OrganizationController.AddMemberRequest(
                                " " + existing.email().toUpperCase(Locale.ROOT) + " ", null))
                .data();

        assertEquals("added", added.status());
        assertEquals(existing.id(), added.member().userId());
        assertEquals(OrgMembership.MEMBER, added.member().role(), "an omitted role defaults to member");
        assertEquals(
                OrgMembership.MEMBER,
                memberships.find(fix.org().id(), existing.id()).orElseThrow().role());
        assertEquals(
                fix.org(),
                orgController.getOrg(session(existing), fix.org().slug()).data());
        assertEquals(
                HttpStatus.CONFLICT,
                statusOf(() -> orgController.addMember(
                        owner,
                        fix.org().slug(),
                        new OrganizationController.AddMemberRequest(existing.email(), "viewer"))));
    }

    /**
     * The bugs: an invitation can be revoked through another organization's URL (a cross-tenant write), a
     * revoked invitation stays listed as pending, or a WorkOS-linked invitation is not revoked locally.
     */
    @Test
    void invitations_areListedAndRevokedOnlyWithinTheirOrg() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-invites");
        var other = TenantFixture.bootstrap(tenants, "rbac-invites-other");
        TenantContext owner = session(fix.user());
        long n = System.nanoTime();
        var invited = orgController
                .addMember(
                        owner,
                        fix.org().slug(),
                        new OrganizationController.AddMemberRequest("invitee+" + n + "@example.com", "viewer"))
                .data()
                .invitation();
        OrgInvitation linked = new OrgInvitation(
                Ids.ulid(),
                fix.org().id(),
                "linked+" + n + "@example.com",
                OrgMembership.MEMBER,
                fix.user().id(),
                "wo_inv_" + n,
                OrgInvitation.PENDING,
                Instant.now().toString(),
                null,
                null);
        invitations.upsertPending(linked);

        assertEquals(linked, invitations.findById(linked.id()).orElseThrow(), "every column round-trips");
        assertEquals(
                Set.of(invited, OrganizationController.InviteView.of(linked)),
                Set.copyOf(orgController.invitations(owner, fix.org().slug()).data()));

        assertEquals(
                HttpStatus.NOT_FOUND,
                statusOf(() -> orgController.revokeInvitation(
                        session(other.user()), other.org().slug(), linked.id())));
        assertEquals(
                OrgInvitation.PENDING,
                invitations.findById(linked.id()).orElseThrow().state());

        orgController.revokeInvitation(owner, fix.org().slug(), linked.id());
        orgController.revokeInvitation(owner, fix.org().slug(), invited.id());
        assertEquals(
                List.of(), orgController.invitations(owner, fix.org().slug()).data());
        OrgInvitation revoked = invitations.findById(linked.id()).orElseThrow();
        assertEquals("revoked", revoked.state());
        assertNotNull(revoked.revokedAt());
    }

    /**
     * The bugs: re-sending a member's current role errors, a role change is not persisted, or the org's only
     * owner can demote themselves and orphan it.
     */
    @Test
    void updateMember_changesRolesButNeverLeavesTheOrgWithoutAnOwner() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-roles");
        String slug = fix.org().slug();
        TenantContext owner = session(fix.user());
        Principal m = memberWithRole(fix.org().id(), OrgMembership.MEMBER, "roles");

        var unchanged = orgController
                .updateMember(owner, slug, m.id(), new OrganizationController.UpdateMemberRequest("member"))
                .data();
        assertEquals(m.id(), unchanged.userId());
        assertEquals(OrgMembership.MEMBER, unchanged.role());
        assertEquals(
                "admin",
                orgController
                        .updateMember(owner, slug, m.id(), new OrganizationController.UpdateMemberRequest("admin"))
                        .data()
                        .role());
        assertEquals(
                "admin", memberships.find(fix.org().id(), m.id()).orElseThrow().role());

        assertEquals(
                HttpStatus.CONFLICT,
                statusOf(() -> orgController.updateMember(
                        owner, slug, fix.user().id(), new OrganizationController.UpdateMemberRequest("admin"))),
                "the only owner cannot step down");

        orgController.updateMember(owner, slug, m.id(), new OrganizationController.UpdateMemberRequest("owner"));
        assertEquals(
                "admin",
                orgController
                        .updateMember(
                                owner, slug, fix.user().id(), new OrganizationController.UpdateMemberRequest("admin"))
                        .data()
                        .role(),
                "with a second owner, the first may step down");
    }

    /**
     * The bugs: an admin strips an owner, a member removes someone else, the last owner leaves and orphans
     * the org, or a member cannot leave on their own.
     */
    @Test
    void removeMember_enforcesOwnerAndSelfLeaveRules() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-remove");
        String orgId = fix.org().id();
        String slug = fix.org().slug();
        TenantContext owner = session(fix.user());
        Principal admin = memberWithRole(orgId, Role.ADMIN.wire(), "rm-admin");
        Principal member = memberWithRole(orgId, OrgMembership.MEMBER, "rm-member");
        Principal leaver = memberWithRole(orgId, Role.VIEWER.wire(), "rm-leaver");

        assertEquals(
                HttpStatus.FORBIDDEN,
                statusOf(() -> orgController.removeMember(
                        session(admin), slug, fix.user().id())));
        assertEquals(
                HttpStatus.FORBIDDEN, statusOf(() -> orgController.removeMember(session(member), slug, admin.id())));
        assertEquals(
                HttpStatus.CONFLICT,
                statusOf(
                        () -> orgController.removeMember(owner, slug, fix.user().id())));
        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> orgController.removeMember(owner, slug, "not-a-member")));

        orgController.removeMember(session(leaver), slug, leaver.id());
        orgController.removeMember(session(admin), slug, member.id());
        assertTrue(memberships.find(orgId, leaver.id()).isEmpty(), "a viewer may leave on their own");
        assertTrue(memberships.find(orgId, member.id()).isEmpty(), "an admin removes a member");

        memberships.updateRole(orgId, admin.id(), OrgMembership.OWNER);
        orgController.removeMember(owner, slug, admin.id());
        assertTrue(memberships.find(orgId, admin.id()).isEmpty(), "an owner removes another owner while one remains");
    }

    /**
     * The bugs: the default project can be archived, leaving the org nowhere to route; an archived project
     * can be made the default; archive, unarchive or default changes do not persist; or an update drops a
     * field.
     */
    @Test
    void projectLifecycle_keepsTheDefaultRoutable() {
        var fix = TenantFixture.bootstrap(tenants, "rbac-projects");
        String slug = fix.org().slug();
        TenantContext owner = session(fix.user());
        Project dflt = projects.findDefaultForOrg(fix.org().id()).orElseThrow();
        Project p = fix.project();

        assertEquals(p, orgController.getProject(owner, slug, p.slug()).data());
        assertEquals(
                new Project(
                        p.id(),
                        p.orgId(),
                        p.slug(),
                        "Renamed",
                        "desc",
                        p.createdAt(),
                        null,
                        "{\"theme\":\"dark\"}",
                        false,
                        null),
                orgController
                        .updateProject(
                                owner,
                                slug,
                                p.slug(),
                                new OrganizationController.UpdateProjectRequest(
                                        "Renamed", "desc", "{\"theme\":\"dark\"}"))
                        .data());

        assertEquals(HttpStatus.CONFLICT, statusOf(() -> orgController.archiveProject(owner, slug, dflt.slug())));
        assertNotNull(orgController.archiveProject(owner, slug, p.slug()).data().archivedAt());
        assertEquals(HttpStatus.CONFLICT, statusOf(() -> orgController.makeProjectDefault(owner, slug, p.slug())));
        assertNull(orgController.unarchiveProject(owner, slug, p.slug()).data().archivedAt());

        assertTrue(
                orgController.makeProjectDefault(owner, slug, p.slug()).data().isDefault());
        assertFalse(projects.findById(dflt.id()).orElseThrow().isDefault(), "the previous default is demoted");
        assertTrue(orgController.ensureSampleProject(owner, slug).data().isSample());
    }
}
