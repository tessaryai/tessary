// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * The paths of {@link TenantService} a real database cannot reach on demand: the loser of two concurrent
 * first logins, and a slug base whose every suffix is taken. The repositories are mocks so the race and the
 * exhaustion can be staged exactly; the database-backed behaviour is {@code TenantServiceTest}'s.
 */
@ExtendWith(MockitoExtension.class)
class TenantServiceFallbacksTest {

    private static final String ORG = "org-1";

    @Mock
    OrganizationRepository orgs;

    @Mock
    ProjectRepository projects;

    /** The Spring proxy {@code TenantService} calls itself through; here, the transaction that loses the race. */
    @Mock
    TenantService self;

    private TenantService service() {
        return new TenantService(null, orgs, null, projects, null, null, null, null, null, self);
    }

    private static Project project(String id, boolean isDefault) {
        return new Project(id, ORG, id, id, null, "2026-09-01T00:00:00Z", null, null, isDefault, null);
    }

    /**
     * The bug: two first logins race to mint the org's default project, and the loser's unique-index
     * violation reaches the login as a 500 instead of resolving to the winner's project.
     */
    @Test
    void theLoserOfTheDefaultProjectRaceResolvesToTheWinnersProject() {
        Project winner = project("p-winner", true);
        when(projects.findDefaultForOrg(ORG)).thenReturn(Optional.empty(), Optional.of(winner));
        when(projects.findByOrg(ORG)).thenReturn(List.of());
        when(self.createProject(ORG, "Default", null, true))
                .thenThrow(new DuplicateKeyException("ux_project_one_default_per_org"));

        assertEquals(winner, service().ensureDefaultProject(ORG));
    }

    /**
     * The bug: a unique violation that did not come from a winning racer (no default exists afterwards) is
     * swallowed, and the login carries on with no default project instead of failing on the real error.
     */
    @Test
    void aDuplicateWithNoWinnerIsRethrown() {
        DuplicateKeyException raced = new DuplicateKeyException("ux_project_one_default_per_org");
        when(projects.findDefaultForOrg(ORG)).thenReturn(Optional.empty());
        when(projects.findByOrg(ORG)).thenReturn(List.of());
        when(self.createProject(ORG, "Default", null, true)).thenThrow(raced);

        assertSame(
                raced, assertThrows(DuplicateKeyException.class, () -> service().ensureDefaultProject(ORG)));
    }

    /**
     * The bug: once every numbered suffix of a slug base is taken, the search returns a slug that already
     * exists (a unique violation later, far from the cause) or never ends, instead of failing here.
     */
    @Test
    @Timeout(10)
    void slugSearchesFailLoudlyOnceEverySuffixIsTaken() {
        when(orgs.findBySlug(anyString()))
                .thenReturn(
                        Optional.of(new Organization(ORG, null, "acme", "Acme", "2026-09-01T00:00:00Z", null, null)));
        when(projects.findByOrgAndSlug(eq(ORG), anyString())).thenReturn(Optional.of(project("p-taken", false)));

        TenantService service = service();
        assertThrows(IllegalStateException.class, () -> service.uniqueSlug("acme"));
        assertThrows(IllegalStateException.class, () -> service.uniqueProjectSlug(ORG, "default"));
    }
}
