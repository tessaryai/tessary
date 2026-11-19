// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.cases.CaseService;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.featureflags.OrgFeatureFlagRepository;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The scheduled sweep that runs RCA without a person pressing the button. The bugs: spending on an org that did not
 * turn it on, analysing windows from before it was turned on, running more often than once per six hours, acting as
 * nobody when the org has no owner, and one case's failure stopping the rest.
 */
@ExtendWith(MockitoExtension.class)
class RcaAutoTriggerTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final Instant TURNED_ON = Instant.parse("2026-11-18T09:00:00Z");

    @Mock
    ProjectRepository projects;

    @Mock
    CapabilityService capabilities;

    @Mock
    OrgFeatureFlagRepository overrides;

    @Mock
    OrgMembershipRepository memberships;

    @Mock
    RcaReportRepository reports;

    @Mock
    CaseService cases;

    @Mock
    Tracer tracer;

    @Test
    void anOrgWithAutomaticRcaOffIsNeverScanned() {
        when(projects.findActive()).thenReturn(List.of(project("p-1", "org-a")));
        when(capabilities.isEnabled("org-a", Capability.RCA)).thenReturn(true);
        when(capabilities.isEnabled("org-a", Capability.RCA_AUTOMATIC)).thenReturn(false);

        trigger().tick();

        verifyNoInteractions(reports, cases);
    }

    @Test
    void anUnresolvableCapabilityCountsAsOff() {
        when(projects.findActive()).thenReturn(List.of(project("p-1", "org-a")));
        when(capabilities.isEnabled("org-a", Capability.RCA)).thenThrow(new IllegalStateException("flags down"));

        trigger().tick();

        verifyNoInteractions(reports, cases);
    }

    /** Only windows after the toggle count, and the newest run must be six hours old. */
    @Test
    void theScanCountsWindowsAfterTheToggleAndRequiresSixHoursSinceTheLastRun() {
        enabled("p-1", "org-a");
        when(overrides.enabledSince("org-a", "rca_automatic_enabled")).thenReturn(Optional.of(TURNED_ON));
        when(reports.dueForAutomaticRca("p-1", TURNED_ON, Instant.parse("2026-11-19T06:00:00Z"), 100))
                .thenReturn(List.of("case-1"));
        when(memberships.findByOrg("org-a")).thenReturn(List.of(owner("org-a", "user-owner")));

        trigger().tick();

        verify(cases).runRcaAutomatically("p-1", "case-1", "user-owner");
    }

    /** On with no override row is on by default since the org began, so every window counts. */
    @Test
    void onWithNoOverrideCountsEveryWindow() {
        enabled("p-1", "org-a");
        when(overrides.enabledSince("org-a", "rca_automatic_enabled")).thenReturn(Optional.empty());
        when(reports.dueForAutomaticRca(eq("p-1"), eq(Instant.EPOCH), any(Instant.class), anyInt()))
                .thenReturn(List.of());

        trigger().tick();

        verifyNoInteractions(cases);
    }

    @Test
    void anOrgWithNoOwnerRunsNothing() {
        enabled("p-1", "org-a");
        when(overrides.enabledSince("org-a", "rca_automatic_enabled")).thenReturn(Optional.of(TURNED_ON));
        when(reports.dueForAutomaticRca(eq("p-1"), eq(TURNED_ON), any(Instant.class), anyInt()))
                .thenReturn(List.of("case-1"));
        when(memberships.findByOrg("org-a"))
                .thenReturn(List.of(OrgMembership.of("org-a", "user-member", OrgMembership.MEMBER, "2026-01-01")));

        trigger().tick();

        verify(cases, never()).runRcaAutomatically(anyString(), anyString(), anyString());
    }

    @Test
    void oneCasesFailureDoesNotStopTheRest() {
        enabled("p-1", "org-a");
        when(overrides.enabledSince("org-a", "rca_automatic_enabled")).thenReturn(Optional.of(TURNED_ON));
        when(reports.dueForAutomaticRca(eq("p-1"), eq(TURNED_ON), any(Instant.class), anyInt()))
                .thenReturn(List.of("case-bad", "case-good"));
        when(memberships.findByOrg("org-a")).thenReturn(List.of(owner("org-a", "user-owner")));
        when(cases.runRcaAutomatically("p-1", "case-bad", "user-owner"))
                .thenThrow(new IllegalStateException("finding gone"));

        trigger().tick();

        verify(cases).runRcaAutomatically("p-1", "case-good", "user-owner");
    }

    private void enabled(String projectId, String orgId) {
        when(projects.findActive()).thenReturn(List.of(project(projectId, orgId)));
        when(capabilities.isEnabled(orgId, Capability.RCA)).thenReturn(true);
        when(capabilities.isEnabled(orgId, Capability.RCA_AUTOMATIC)).thenReturn(true);
    }

    private RcaAutoTrigger trigger() {
        return new RcaAutoTrigger(
                projects,
                capabilities,
                overrides,
                memberships,
                reports,
                cases,
                new TraceMdcBridge(tracer),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OrgMembership owner(String orgId, String principalId) {
        return OrgMembership.of(orgId, principalId, OrgMembership.OWNER, "2026-01-01T00:00:00Z");
    }

    private static Project project(String id, String orgId) {
        return new Project(id, orgId, id, id, null, "2026-09-01T00:00:00Z", null, null, false, null);
    }
}
