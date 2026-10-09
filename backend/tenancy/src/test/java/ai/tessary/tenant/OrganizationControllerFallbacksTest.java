// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.auth.AuthProvider;
import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The {@link OrganizationController} paths only a concurrent writer or a failing WorkOS reaches: a row
 * that vanishes between the permission check and the read-back, an owner whose own ownership was revoked
 * mid-request, and an invitation revoke whose WorkOS call fails. The repositories are mocks so each
 * interleaving can be staged exactly; everything else about this controller runs against the database in
 * {@code RbacEnforcementIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationControllerFallbacksTest {

    private static final Organization ORG =
            new Organization("org-1", null, "acme", "Acme", "2026-09-01T00:00:00Z", null, null);
    private static final Project PROJECT =
            new Project("p-1", "org-1", "app", "App", null, "2026-09-01T00:00:00Z", null, null, false, null);
    private static final TenantContext OWNER = new TenantContext("u-owner", null, null, null, null, null);

    @Mock
    OrganizationRepository orgs;

    @Mock
    OrgMembershipRepository memberships;

    @Mock
    ProjectRepository projects;

    @Mock
    InvitationRepository invitations;

    @Mock
    TenantPathResolver resolver;

    private final FailingWorkos workos = new FailingWorkos();

    private OrganizationController controller() {
        return new OrganizationController(
                null, orgs, memberships, projects, null, invitations, resolver, workos, null, null);
    }

    private void ownerOfOrg() {
        when(resolver.requireOrg(OWNER, "acme")).thenReturn(new TenantPathResolver.OrgResolved(ORG, "owner"));
    }

    private static HttpStatus statusOf(Runnable call) {
        return HttpStatus.valueOf(assertThrows(ResponseStatusException.class, call::run)
                .getStatusCode()
                .value());
    }

    /**
     * The bugs: a row deleted between the permission check and the read-back answers 500 from a bare
     * {@code orElseThrow}, instead of 404 for an organization or project that is gone, and 500 with a
     * cause for a membership that vanished after its role was written.
     */
    @Test
    void aRowThatVanishesMidUpdateAnswersItsOwnStatus() {
        ownerOfOrg();
        when(orgs.findById("org-1")).thenReturn(Optional.empty());
        when(resolver.requireProject(OWNER, "acme", "app"))
                .thenReturn(new TenantPathResolver.Resolved(ORG, PROJECT, "owner"));
        when(projects.findById("p-1")).thenReturn(Optional.empty());
        when(memberships.find("org-1", "u-member"))
                .thenReturn(Optional.of(OrgMembership.of("org-1", "u-member", "member", "2026-09-01T00:00:00Z")));
        when(memberships.findByOrgWithUsers("org-1")).thenReturn(List.of());

        OrganizationController c = controller();
        assertEquals(
                HttpStatus.NOT_FOUND,
                statusOf(
                        () -> c.updateOrg(OWNER, "acme", new OrganizationController.UpdateOrgRequest("Acme 2", null))));
        assertEquals(
                HttpStatus.NOT_FOUND,
                statusOf(() -> c.updateProject(
                        OWNER, "acme", "app", new OrganizationController.UpdateProjectRequest("App 2", null, null))));
        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                statusOf(() -> c.updateMember(
                        OWNER, "acme", "u-member", new OrganizationController.UpdateMemberRequest("admin"))));
    }

    /**
     * The bug: an owner whose own ownership was revoked concurrently removes the other owner, now the
     * last, and orphans the organization.
     */
    @Test
    void removingTheLastOwnerIsRefusedEvenWhenTheCallerResolvedAsOwner() {
        ownerOfOrg();
        when(memberships.find("org-1", "u-other"))
                .thenReturn(Optional.of(OrgMembership.of("org-1", "u-other", "owner", "2026-09-01T00:00:00Z")));
        when(memberships.countOwners("org-1")).thenReturn(1L);

        assertEquals(HttpStatus.CONFLICT, statusOf(() -> controller().removeMember(OWNER, "acme", "u-other")));
        verify(memberships, never()).delete(anyString(), anyString());
    }

    /**
     * The bug: a WorkOS outage turns an invitation revoke into a 500 after the local row was already
     * revoked, so the admin retries a revoke that has in fact happened.
     */
    @Test
    void aFailedWorkosRevokeStillRevokesTheInvitationLocally() {
        ownerOfOrg();
        when(invitations.findById("inv-1"))
                .thenReturn(Optional.of(new OrgInvitation(
                        "inv-1",
                        "org-1",
                        "new@example.com",
                        "member",
                        "u-owner",
                        "wo_inv_1",
                        OrgInvitation.PENDING,
                        "2026-09-01T00:00:00Z",
                        null,
                        null)));

        assertNull(controller().revokeInvitation(OWNER, "acme", "inv-1").data());
        assertEquals(List.of("wo_inv_1"), workos.revokeAttempts);
        verify(invitations).markRevoked(eq("inv-1"), anyString());
    }

    /** A WorkOS stand-in whose invitation revoke always fails, and which remembers what it was asked to revoke. */
    private static final class FailingWorkos implements AuthProvider {
        final List<String> revokeAttempts = new ArrayList<>();

        @Override
        public void revokeInvitation(String invitationId) {
            revokeAttempts.add(invitationId);
            throw new IllegalStateException("workos unavailable");
        }

        @Override
        public String authorizationUrl(String state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthResult authenticateWithCode(String code) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthResult refresh(String refreshToken, String organizationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Invitation createInvitation(String email) {
            throw new UnsupportedOperationException();
        }
    }
}
