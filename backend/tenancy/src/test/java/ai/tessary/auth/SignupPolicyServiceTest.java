// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.open.errors.AuthError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.AuditLogRepository;
import ai.tessary.tenant.InvitationRepository;
import ai.tessary.tenant.OrgInvitation;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.OrganizationRepository;
import ai.tessary.tenant.PrincipalRepository;
import ai.tessary.tenant.SignupPolicy;
import ai.tessary.tenant.TenantService;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignupPolicyServiceTest {

    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private final PrincipalRepository users = mock(PrincipalRepository.class);
    private final InvitationRepository invitations = mock(InvitationRepository.class);
    private final SignupPolicyService service = new SignupPolicyService(
            orgs, users, invitations, mock(AuditLogRepository.class), mock(TenantService.class));

    @BeforeEach
    void defaults() {
        when(users.findByWorkosId(anyString())).thenReturn(Optional.empty());
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.anyHumanExists()).thenReturn(true);
        when(invitations.findPendingByEmail(anyString())).thenReturn(List.of());
    }

    private void policy(String mode, @Nullable List<String> domains) {
        String settings = SignupPolicy.of(mode, domains).intoSettings(null);
        when(orgs.findOldest())
                .thenReturn(Optional.of(
                        new Organization("org", null, "org", "Org", "2026-01-01T00:00:00Z", null, settings)));
    }

    private void refused(String email) {
        TessaryException e = assertThrows(TessaryException.class, () -> service.admit(email, null));
        assertEquals(AuthError.SIGNUP_REFUSED, e.error());
    }

    @Test
    void inviteModeRefusesStrangersAndAdmitsInvitations() {
        policy("invite", null);
        refused("stranger@example.com");
        when(invitations.findPendingByEmail("guest@example.com"))
                .thenReturn(List.of(new OrgInvitation(
                        "inv", "org", "guest@example.com", "member", "owner", "", "pending", "t", "", "")));
        assertDoesNotThrow(() -> service.admit("Guest@Example.com", null));
    }

    @Test
    void domainModeAdmitsListedDomainsAndInvitations() {
        policy("domain", List.of("acme.com"));
        assertDoesNotThrow(() -> service.admit("dev@acme.com", null));
        refused("dev@other.com");
        when(invitations.findPendingByEmail("contractor@other.com"))
                .thenReturn(List.of(new OrgInvitation(
                        "inv", "org", "contractor@other.com", "member", "owner", "", "pending", "t", "", "")));
        assertDoesNotThrow(() -> service.admit("contractor@other.com", null));
    }
}
