// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link TenantService} announces every organization it creates, on each of its three creation paths, and
 * never one it only looked up. A build that provisions an org's starting state (a plan, a credit grant) off
 * {@link OrganizationCreatedEvent} would otherwise miss the orgs created on whichever path forgot to publish.
 */
@ExtendWith(MockitoExtension.class)
class TenantServiceEventsTest {

    private static final Principal USER =
            Principal.human("usr_1", "wos_usr_1", "ada@example.com", null, null, "2026-09-01T00:00:00Z", null);

    @Mock
    OrganizationRepository orgs;

    @Mock
    OrgMembershipRepository memberships;

    @Mock
    ProjectRepository projects;

    @Mock
    ApplicationEventPublisher events;

    @Mock
    TenantService self;

    private TenantService service() {
        return new TenantService(null, orgs, memberships, projects, null, events, null, null, null, self);
    }

    /** The default project already exists, so ensureDefaultOrg's second step does nothing worth stubbing. */
    private void orgHasItsDefaultProject() {
        when(projects.findDefaultForOrg(anyString()))
                .thenReturn(Optional.of(new Project(
                        "prj_1", "org", "default", "Default", null, "2026-09-01T00:00:00Z", null, null, true, null)));
    }

    @Test
    void aFirstSignInsPersonalOrgIsAnnounced() {
        when(orgs.findByUserId(USER.id())).thenReturn(List.of());
        when(orgs.findBySlug(anyString())).thenReturn(Optional.empty());
        orgHasItsDefaultProject();

        Organization org = service().ensureDefaultOrg(USER, null);

        verify(events).publishEvent(new OrganizationCreatedEvent(org.id()));
    }

    @Test
    void theFirstMirrorOfAWorkosOrgIsAnnounced() {
        when(orgs.findByWorkosOrgId("org_wos_1")).thenReturn(Optional.empty());
        when(orgs.findBySlug(anyString())).thenReturn(Optional.empty());
        orgHasItsDefaultProject();

        Organization org = service().ensureDefaultOrg(USER, "org_wos_1");

        verify(events).publishEvent(new OrganizationCreatedEvent(org.id()));
    }

    @Test
    void aBootstrappedOrgIsAnnounced() {
        Organization org = new Organization("org_1", null, "acme", "Acme", "2026-09-01T00:00:00Z", null, null);

        service().bootstrapOrg(org, USER.id(), 1);

        verify(events).publishEvent(new OrganizationCreatedEvent("org_1"));
    }

    @Test
    void anOrgThatAlreadyExistedIsNotAnnounced() {
        Organization existing = new Organization("org_1", null, "acme", "Acme", "2026-09-01T00:00:00Z", null, null);
        when(orgs.findByUserId(USER.id())).thenReturn(List.of(existing));
        orgHasItsDefaultProject();

        service().ensureDefaultOrg(USER, null);

        verify(events, never()).publishEvent(any(OrganizationCreatedEvent.class));
    }
}
