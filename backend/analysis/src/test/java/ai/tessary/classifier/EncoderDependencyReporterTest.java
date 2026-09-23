// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.catalog.BuiltInDetector.Kind;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link EncoderDependencyReporter} counts a project as depending on the encoder when its groundedness row
 * is enabled and its org holds the capability, and nothing else: whether the model answers right now is
 * not an input, because a model that is down is still depended on.
 */
class EncoderDependencyReporterTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ClassifierRepository classifiers = mock(ClassifierRepository.class);
    private final CapabilityService capabilities = mock(CapabilityService.class);
    private EncoderDependencyReporter reporter;

    @BeforeEach
    void setup() {
        reporter = new EncoderDependencyReporter(projects, classifiers, capabilities);
    }

    @Test
    void anEnabledRowUnderAHeldCapabilityIsADependency() {
        when(projects.findActive()).thenReturn(List.of(project("p1", "o1"), project("p2", "o1")));
        when(classifiers.listEnabled("p1")).thenReturn(List.of(row("p1", Kind.GROUNDEDNESS, "groundedness")));
        when(classifiers.listEnabled("p2")).thenReturn(List.of(row("p2", Kind.SECRET_LEAK, "secret_leak")));
        when(capabilities.isEnabled("o1", Capability.GROUNDEDNESS)).thenReturn(true);

        EncoderDependencyReporter.Dependency d = reporter.report();

        assertEquals(1, d.projects());
        assertEquals(1, d.orgs());
        assertEquals(Set.of("groundedness"), d.classifiers());
        assertFalse(d.decommissionable());
    }

    @Test
    void anOrgThatTurnedTheCapabilityOffDoesNotDepend() {
        when(projects.findActive()).thenReturn(List.of(project("p1", "o1")));
        when(classifiers.listEnabled("p1")).thenReturn(List.of(row("p1", Kind.GROUNDEDNESS, "groundedness")));
        when(capabilities.isEnabled("o1", Capability.GROUNDEDNESS)).thenReturn(false);

        EncoderDependencyReporter.Dependency d = reporter.report();

        assertEquals(0, d.projects());
        assertTrue(d.decommissionable());
    }

    @Test
    void noEnabledGroundednessRowIsDecommissionable() {
        when(projects.findActive()).thenReturn(List.of(project("p1", "o1")));
        when(classifiers.listEnabled("p1")).thenReturn(List.of());

        assertTrue(reporter.report().decommissionable());
    }

    private static Project project(String id, String orgId) {
        return new Project(id, orgId, id, id, null, "2026-01-01T00:00:00Z", null, null, false, null);
    }

    private static ClassifierRow row(String projectId, String detector, String key) {
        return new ClassifierRow(
                "c-" + key,
                projectId,
                key,
                key,
                null,
                detector,
                null,
                true,
                1,
                true,
                ClassifierRow.Mode.TRACKING,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z");
    }
}
