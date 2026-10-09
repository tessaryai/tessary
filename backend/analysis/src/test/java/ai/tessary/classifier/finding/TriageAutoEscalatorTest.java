// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import io.micrometer.tracing.Tracer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The scheduled sweep that hands eligible findings to triage without a human pressing the button. It runs
 * over every active project, so the bug is one project's failure (its capability lookup or its escalation)
 * stopping the rest of the sweep, or a failed project scan escalating anything at all.
 */
@ExtendWith(MockitoExtension.class)
class TriageAutoEscalatorTest {

    @Mock
    ProjectRepository projects;

    @Mock
    CapabilityService capabilities;

    @Mock
    TriageSource source;

    @Mock
    FindingService drift;

    @Mock
    Tracer tracer;

    @Test
    void aFailedProjectScanEscalatesNothing() {
        when(projects.findActive()).thenThrow(new IllegalStateException("db down"));

        escalator().tick();

        verifyNoInteractions(capabilities, drift);
    }

    /**
     * Three projects: the first's capability cannot be resolved (treated as off, so nothing of its is
     * escalated), the second's escalation throws, and the third must still be escalated.
     */
    @Test
    void oneProjectsFailureDoesNotStopTheSweepForTheRest() {
        Project unresolved = project("p-unresolved", "org-a");
        Project failing = project("p-failing", "org-b");
        Project healthy = project("p-healthy", "org-c");
        when(projects.findActive()).thenReturn(List.of(unresolved, failing, healthy));
        when(capabilities.isEnabled("org-a", Capability.TRIAGE_AUTOMATIC)).thenThrow(new IllegalStateException("x"));
        when(capabilities.isEnabled("org-b", Capability.TRIAGE_AUTOMATIC)).thenReturn(true);
        when(capabilities.isEnabled("org-c", Capability.TRIAGE_AUTOMATIC)).thenReturn(true);
        when(source.listAutoEscalatable(eq("p-failing"), anyLong(), anyInt()))
                .thenReturn(List.of(new TriageSource.Escalatable("f-failing")));
        when(source.listAutoEscalatable(eq("p-healthy"), anyLong(), anyInt()))
                .thenReturn(List.of(new TriageSource.Escalatable("f-healthy")));
        when(drift.analyze("p-failing", "f-failing", null)).thenThrow(new IllegalStateException("launcher down"));

        escalator().tick();

        verify(drift).analyze("p-healthy", "f-healthy", null);
    }

    private TriageAutoEscalator escalator() {
        return new TriageAutoEscalator(
                projects, capabilities, List.of(source), drift, new ClassifierProperties(), new TraceMdcBridge(tracer));
    }

    private static Project project(String id, String orgId) {
        return new Project(id, orgId, id, id, null, "2026-09-01T00:00:00Z", null, null, false, null);
    }
}
